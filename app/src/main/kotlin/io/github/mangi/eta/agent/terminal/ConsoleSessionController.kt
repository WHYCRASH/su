package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import kotlin.concurrent.thread

/**
 * Console session controller: the PTY byte stream passes through untouched, with no status-line protocol and no output truncation.
 *
 * Unlike [UserTerminalController], the interaction model targets fullscreen TUIs and interactive CLIs:
 * input goes straight to stdin (including arrow-key and Ctrl-combo escape bytes), and the caller feeds output to
 * [TerminalScreenBuffer] to maintain the screen grid. Multiple console sessions coexist, routed by sessionId.
 */
internal class ConsoleSessionController(
    private val logger: AgentLogger,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
) : AutoCloseable {

    private companion object {
        const val DEFAULT_ANDROID_CWD = "/data/local/tmp/eta"
        const val MAX_SESSIONS = 6
    }

    sealed interface OpenResult {
        data class Ready(val sessionId: String) : OpenResult
        data class Failed(val code: String, val message: String) : OpenResult
    }

    data class SessionInfo(
        val id: String,
        val environment: TerminalEnvironment,
        val alive: Boolean,
    )

    private val sessionLock = Any()
    private val sessions = LinkedHashMap<String, PtySession>()
    private var nextSessionNumber = 0

    fun listSessions(): List<SessionInfo> = synchronized(sessionLock) {
        sessions.map { (id, session) ->
            SessionInfo(
                id = id,
                environment = session.environment,
                alive = !session.closed && session.process.isAlive,
            )
        }
    }

    fun sessionAlive(sessionId: String): Boolean = synchronized(sessionLock) {
        sessions[sessionId]?.let { !it.closed && it.process.isAlive } == true
    }

    fun open(
        environment: TerminalEnvironment,
        cols: Int,
        rows: Int,
        onOutput: (sessionId: String, chunk: ByteArray) -> Unit,
        onExit: (sessionId: String) -> Unit,
        identity: String? = null,
    ): OpenResult {
        synchronized(sessionLock) {
            pruneDeadSessionsLocked()
            if (sessions.size >= MAX_SESSIONS) {
                return OpenResult.Failed("SESSION_LIMIT_REACHED", "Session count has reached the limit")
            }
            val environmentRootfsPath = rootfsPath(environment)
            val identity = identity ?: if (environment.isLinux) TerminalRuntime.defaultIdentity(environment, environmentRootfsPath) else if (rootAvailable()) "root" else "user"
            if (identity !in setOf("root", "user")) return OpenResult.Failed("INVALID_ARGUMENT", "Invalid execution identity")
            if (environment.isLinux && identity == "user" && LinuxEnvironmentPaths.backendOf(environmentRootfsPath) != LinuxExecutionBackend.PROOT) return OpenResult.Failed("LINUX_ENVIRONMENT_REQUIRES_ROOT", "The selected Linux environment requires root")
            if (environment.isLinux && identity == "root" && LinuxEnvironmentPaths.backendOf(environmentRootfsPath) == LinuxExecutionBackend.PROOT) return OpenResult.Failed("INVALID_IDENTITY", "Rootless Linux uses the regular app identity")
            if (identity == "root" && !rootAvailable()) return OpenResult.Failed("ROOT_REQUIRED", "Root authorization is unavailable")
            if (environment.isLinux &&
                !LinuxEnvironmentPaths.rootfsReady(environmentRootfsPath)
            ) {
                return OpenResult.Failed("LINUX_ENVIRONMENT_NOT_READY", "The Linux tool environment is not installed yet")
            }
            val process = processSupervisor.startShellProcess(
                identity = identity,
                command = null,
                mergeStderr = true,
                environment = environment,
                linuxRootfsPath = environmentRootfsPath,
                linuxSharedMounts = if (environment.isLinux) {
                    linuxSharedMountsProvider()
                } else {
                    emptyList()
                },
                pty = true,
                ptyCols = cols,
                ptyRows = rows,
            ) ?: return OpenResult.Failed("PROCESS_START_FAILED", "Cannot start the console process; check the selected environment and terminal components")

            val sessionId = "c${++nextSessionNumber}"
            val newSession = PtySession(environment, process, identity)
            sessions[sessionId] = newSession
            newSession.readerThread = thread(name = "console-pty-reader", isDaemon = true) {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                try {
                    while (true) {
                        val read = process.inputStream.read(buffer)
                        if (read < 0) break
                        onOutput(sessionId, buffer.copyOf(read))
                    }
                } catch (_: Exception) {
                    // When the process dies or closes, the read end disconnects; the waiter reports the exit.
                }
            }
            newSession.waiterThread = thread(name = "console-pty-waiter", isDaemon = true) {
                runCatching { process.waitFor() }
                newSession.closed = true
                processSupervisor.retireExitedProcess(process)
                onExit(sessionId)
            }
            // Land in the environment's default working directory; clear wipes this bootstrap command's own echo.
            // User-tool installers often write PATH into profile; the console shell is not a login shell, so source it explicitly here.
            val defaultCwd = if (environment.isLinux) "/workspace" else TerminalRuntime.workspace(identity)
            val bootstrap = "mkdir -p ${shellQuote(defaultCwd)}; " +
                "[ -f /etc/profile ] && . /etc/profile; " +
                "[ -f \"\$HOME/.profile\" ] && . \"\$HOME/.profile\"; " +
                "cd ${shellQuote(defaultCwd)} && clear\n"
            runCatching {
                process.outputStream.write(bootstrap.toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
            logger.info("Console action=open outcome=succeeded environment=${environment.wireName} cols=$cols rows=$rows")
            return OpenResult.Ready(sessionId)
        }
    }

    /** Write input bytes (typed text, arrow/function-key escape sequences) to the given console session. Silently dropped when the session is unavailable. */
    fun write(sessionId: String, bytes: ByteArray) {
        val current = synchronized(sessionLock) { sessions[sessionId] } ?: return
        if (current.closed || !current.process.isAlive) return
        if (current.identity == "root" && !rootAvailable()) {
            closeSession(sessionId)
            return
        }
        runCatching {
            synchronized(current.stdinLock) {
                current.process.outputStream.write(bytes)
                current.process.outputStream.flush()
            }
        }
    }

    fun write(sessionId: String, text: String) = write(sessionId, text.toByteArray(Charsets.UTF_8))

    /** Close a single session; reclaims only that process tree without affecting other sessions or triggering a global supervisor shutdown. */
    fun closeSession(sessionId: String) {
        synchronized(sessionLock) {
            closeSessionLocked(sessionId)
        }
    }

    override fun close() {
        processSupervisor.beginClosing()
        synchronized(sessionLock) {
            sessions.keys.toList().forEach { closeSessionLocked(it) }
        }
    }

    private fun closeSessionLocked(sessionId: String) {
        val current = sessions.remove(sessionId) ?: return
        current.closed = true
        runCatching { current.process.outputStream.close() }
        processSupervisor.terminateAndReap(current.process)
        runCatching { current.readerThread.join(500) }
        runCatching { current.waiterThread.join(500) }
        processSupervisor.unregisterProcess(current.process)
    }

    /** Reclaim exited session slots so dead sessions don't occupy the concurrency limit. */
    private fun pruneDeadSessionsLocked() {
        val deadIds = sessions.filterValues { it.closed || !it.process.isAlive }.keys.toList()
        deadIds.forEach { closeSessionLocked(it) }
    }

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private class PtySession(
        val environment: TerminalEnvironment,
        val process: Process,
        val identity: String,
    ) {
        val stdinLock = Any()

        @Volatile
        var closed: Boolean = false

        lateinit var readerThread: Thread
        lateinit var waiterThread: Thread
    }
}
