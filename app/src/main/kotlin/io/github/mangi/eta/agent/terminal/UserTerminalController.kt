package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import kotlin.concurrent.thread

/**
 * Session controller for the user-driven terminal: multiple persistent shell sessions coexist, each keeping its cwd and environment across commands.
 *
 * Layered separately from the model-facing [RootShellTerminalController]:
 * - No execution timeout--the user decides when a command ends ("Stop" terminates the corresponding session);
 * - Output streams through the onDelta callback instead of the model-tool JSON contract and truncation policy;
 * - Lifetime belongs to app-level UI state and is not reclaimed with a single run.
 *
 * Process startup, ownership detection, and process-tree termination reuse [ShellProcessSupervisor]; background child processes started inside a session
 * live with the session and are cleaned up by the launcher on session exit, matching AI terminal semantics.
 */
internal class UserTerminalController(
    private val logger: AgentLogger,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
    private val onSessionExit: (String) -> Unit = {},
) : AutoCloseable {

    private companion object {
        const val DEFAULT_CWD = "/data/local/tmp/eta"
        const val LINUX_DEFAULT_CWD = "/workspace"
        const val MAX_SESSIONS = 6
        const val STREAM_MAX_BYTES = 1024 * 1024
        const val POLL_INTERVAL_MS = 50L
        const val SETUP_TIMEOUT_MS = 5_000L
    }

    sealed interface OpenResult {
        data class Ready(
            val sessionId: String,
            val environment: TerminalEnvironment,
            val cwd: String,
        ) : OpenResult

        data class Failed(val code: String, val message: String) : OpenResult
    }

    /** A null exitCode means the session was interrupted (stopped or the process died) and the command never returned an exit code. */
    data class ExecResult(
        val exitCode: Int?,
        val cwd: String,
        val sessionClosed: Boolean,
        val interrupted: Boolean,
    )

    data class SessionInfo(
        val id: String,
        val environment: TerminalEnvironment,
        val cwd: String,
        val alive: Boolean,
    )

    private val sessionLock = Any()
    private val sessions = LinkedHashMap<String, Session>()
    private var nextSessionNumber = 0

    fun listSessions(): List<SessionInfo> = synchronized(sessionLock) {
        sessions.map { (id, session) ->
            SessionInfo(
                id = id,
                environment = session.environment,
                cwd = session.cwd,
                alive = !session.closed && session.process.isAlive,
            )
        }
    }

    fun sessionAlive(sessionId: String): Boolean = synchronized(sessionLock) {
        sessions[sessionId]?.let { !it.closed && it.process.isAlive } == true
    }

    /** Open a new session; existing sessions stay alive. identity is reserved for degraded no-root environments and tests. */
    fun openSession(
        environment: TerminalEnvironment,
        cwd: String? = null,
        identity: String? = null,
    ): OpenResult {
        val environmentRootfsPath = rootfsPath(environment)
        val selectedIdentity = identity ?: if (environment.isLinux) {
            TerminalRuntime.defaultIdentity(environment, environmentRootfsPath)
        } else if (rootAvailable()) "root" else "user"
        synchronized(sessionLock) {
            pruneDeadSessionsLocked()
            if (sessions.size >= MAX_SESSIONS) {
                return OpenResult.Failed("SESSION_LIMIT_REACHED", "Session count has reached the limit")
            }
            if (selectedIdentity != "root" && selectedIdentity != "user") {
                return OpenResult.Failed("INVALID_ARGUMENT", "identity only supports root/user")
            }
            if (environment.isLinux && selectedIdentity != "root" && LinuxEnvironmentPaths.backendOf(environmentRootfsPath) != LinuxExecutionBackend.PROOT) {
                return OpenResult.Failed("LINUX_ENVIRONMENT_REQUIRES_ROOT", "The Linux tool environment only supports the root identity")
            }
            if (selectedIdentity == "root" && !rootAvailable()) return OpenResult.Failed("ROOT_REQUIRED", "Root authorization is unavailable")
            if (environment.isLinux &&
                !LinuxEnvironmentPaths.rootfsReady(environmentRootfsPath)
            ) {
                return OpenResult.Failed("LINUX_ENVIRONMENT_NOT_READY", "The Linux tool environment is not installed yet")
            }
            val safeCwd = cwd?.takeIf { it.isNotBlank() } ?: if (environment.isLinux) LINUX_DEFAULT_CWD else TerminalRuntime.workspace(selectedIdentity)
            val process = processSupervisor.startShellProcess(
                identity = selectedIdentity,
                command = null,
                mergeStderr = false,
                environment = environment,
                linuxRootfsPath = environmentRootfsPath,
                linuxSharedMounts = if (environment.isLinux) {
                    linuxSharedMountsProvider()
                } else {
                    emptyList()
                },
            ) ?: return OpenResult.Failed("PROCESS_START_FAILED", "Cannot start the terminal process")
            val newSession = Session(
                identity = selectedIdentity,
                environment = environment,
                cwd = safeCwd,
                process = process,
                stdout = ByteArrayOutputCollector(),
                stderr = ByteArrayOutputCollector(),
            )
            newSession.stdoutThread = thread(name = "user-terminal-stdout", isDaemon = true) {
                process.inputStream.use { input -> newSession.stdout.readFrom(input, STREAM_MAX_BYTES) }
            }
            newSession.stderrThread = thread(name = "user-terminal-stderr", isDaemon = true) {
                process.errorStream.use { input -> newSession.stderr.readFrom(input, STREAM_MAX_BYTES) }
            }
            val sessionId = "s${++nextSessionNumber}"
            newSession.waiterThread = thread(name = "user-terminal-waiter", isDaemon = true) {
                runCatching { process.waitFor() }
                newSession.closed = true
                processSupervisor.retireExitedProcess(process)
                onSessionExit(sessionId)
            }
            if (!processSupervisor.transferActiveProcess(process) { sessions[sessionId] = newSession }) {
                processSupervisor.terminateProcessTree(process)
                return OpenResult.Failed("TERMINAL_CLOSED", "Terminal controller is closed")
            }

            val setup = buildString {
                if (environment == TerminalEnvironment.ANDROID && safeCwd == TerminalRuntime.workspace(selectedIdentity)) {
                    append("mkdir -p ${shellQuote(safeCwd)} && ")
                }
                // User-tool installers often write PATH into profile; persistent sessions are not login shells, so complete it explicitly here.
                append("[ -f /etc/profile ] && . /etc/profile; ")
                append("[ -f \"${'$'}HOME/.profile\" ] && . \"${'$'}HOME/.profile\"; ")
                append("cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1")
            }
            val setupResult = execInternal(newSession, setup, SETUP_TIMEOUT_MS, null)
            if (setupResult.exitCode != 0 || setupResult.timedOut) {
                closeSessionLocked(sessionId)
                logger.warn("User terminal action=open outcome=failed environment=${environment.wireName}")
                return OpenResult.Failed("SESSION_OPEN_FAILED", "Terminal session failed to initialize")
            }
            newSession.cwd = setupResult.cwd ?: safeCwd
            newSession.stdout.clear()
            newSession.stderr.clear()
            logger.info("User terminal action=open outcome=succeeded environment=${environment.wireName}")
            return OpenResult.Ready(sessionId, environment, newSession.cwd)
        }
    }

    /**
     * Run a command in the given persistent session and stream output via callback. Blocks the current thread; callers must use an IO thread.
     * No timeout; returns sessionClosed=true when the user stops it or the shell dies (e.g. exit is executed).
     */
    fun exec(sessionId: String, command: String, onDelta: (text: String, isStderr: Boolean) -> Unit): ExecResult {
        val trimmed = command.trim()
        val current = synchronized(sessionLock) { sessions[sessionId] }
            ?: return ExecResult(
                exitCode = null,
                cwd = DEFAULT_CWD,
                sessionClosed = true,
                interrupted = false,
            )
        if (current.identity == "root" && !rootAvailable()) {
            stopSession(sessionId)
            return ExecResult(null, current.cwd, sessionClosed = true, interrupted = true)
        }
        if (trimmed.isBlank()) {
            return ExecResult(
                exitCode = null,
                cwd = current.cwd,
                sessionClosed = false,
                interrupted = false,
            )
        }
        val result = execInternal(current, trimmed, timeoutMs = null, onDelta = onDelta)
        val interrupted = current.stopRequested
        val outcome = when {
            result.sessionClosed -> if (interrupted) "interrupted" else "closed"
            result.exitCode == 0 -> "succeeded"
            else -> "failed"
        }
        val logMessage =
            "User terminal action=exec outcome=$outcome environment=${current.environment.wireName} " +
                "exitCode=${result.exitCode} commandChars=${trimmed.length}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        return ExecResult(
            exitCode = result.exitCode,
            cwd = result.cwd ?: current.cwd,
            sessionClosed = result.sessionClosed,
            interrupted = interrupted,
        )
    }

    /** Terminate the given session and its process group; the caller must openSession again before the next exec on that session. */
    fun stopSession(sessionId: String) {
        synchronized(sessionLock) {
            sessions[sessionId]?.stopRequested = true
            closeSessionLocked(sessionId)
        }
    }

    /**
     * Append input to stdin while a session is running (simulating real terminal typing); both the foreground process and later shell commands can read it.
     * Returns false when the session is no longer usable.
     */
    fun writeInput(sessionId: String, text: String): Boolean {
        val current = synchronized(sessionLock) { sessions[sessionId] } ?: return false
        if (current.closed || !current.process.isAlive) return false
        if (current.identity == "root" && !rootAvailable()) return false
        return runCatching {
            synchronized(current.stdinLock) {
                current.process.outputStream.write(text.toByteArray(Charsets.UTF_8))
                current.process.outputStream.flush()
            }
        }.isSuccess
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
        runCatching { current.stdoutThread.join(500) }
        runCatching { current.stderrThread.join(500) }
        runCatching { current.waiterThread.join(500) }
        processSupervisor.unregisterProcess(current.process)
    }

    /** Reclaim exited session slots so dead sessions do not consume the concurrency limit. */
    private fun pruneDeadSessionsLocked() {
        val deadIds = sessions.filterValues { it.closed || !it.process.isAlive }.keys.toList()
        deadIds.forEach { closeSessionLocked(it) }
    }

    private fun defaultCwd(environment: TerminalEnvironment): String =
        if (environment.isLinux) LINUX_DEFAULT_CWD else DEFAULT_CWD

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private data class InternalResult(
        val exitCode: Int?,
        val cwd: String?,
        val timedOut: Boolean,
        val sessionClosed: Boolean,
    )

    private fun execInternal(
        session: Session,
        command: String,
        timeoutMs: Long?,
        onDelta: ((text: String, isStderr: Boolean) -> Unit)?,
    ): InternalResult {
        synchronized(session.execLock) {
            if (session.closed || !session.process.isAlive) {
                return InternalResult(
                    exitCode = null,
                    cwd = session.cwd,
                    timedOut = false,
                    sessionClosed = true,
                )
            }
            val marker = SessionStatusProtocol.newMarker()
            // Single-logical-line protocol: the status printf never goes through stdin, so interactive commands reading stdin cannot swallow the marker.
            val commandBlock = SessionStatusProtocol.commandLine(marker, command) + "\n"
            runCatching {
                synchronized(session.stdinLock) {
                    session.process.outputStream.write(commandBlock.toByteArray(Charsets.UTF_8))
                    session.process.outputStream.flush()
                }
            }.getOrElse {
                session.closed = true
                return InternalResult(null, session.cwd, timedOut = false, sessionClosed = true)
            }

            val deadline = timeoutMs?.let { System.currentTimeMillis() + it }
            var stdoutOffset = 0
            var stderrOffset = 0
            while (true) {
                val stdoutNow = session.stdout.text()
                val stderrNow = session.stderr.text()
                // Check the status line before emitting deltas: once a status line has arrived complete (including its trailing newline) it is no longer a "trailing partial line",
                // emitting the delta first would push the whole marker line to the UI. Status lines must also end with a newline,
                // so a truncated cwd is never parsed from a half line.
                val markerStart = stdoutNow.indexOf("\n$marker:")
                val markerLineEnd = if (markerStart >= 0) stdoutNow.indexOf('\n', markerStart + 1) else -1
                val status = if (markerLineEnd >= 0) {
                    SessionStatusProtocol.parseStatusLine(
                        stdoutNow.substring(markerStart + 1, markerLineEnd),
                        marker,
                    )
                } else {
                    null
                }
                if (status != null) {
                    if (onDelta != null) {
                        flushFinalStdout(stdoutNow, stdoutOffset, marker, onDelta)
                        if (stderrNow.length > stderrOffset) {
                            onDelta(stderrNow.substring(stderrOffset), true)
                        }
                    }
                    session.stdout.clear()
                    session.stderr.clear()
                    val newCwd = status.cwd ?: session.cwd
                    session.cwd = newCwd
                    return InternalResult(status.exitCode, newCwd, timedOut = false, sessionClosed = false)
                }
                if (session.closed || !session.process.isAlive) {
                    // Session has ended: flush remaining output as-is without status-line filtering.
                    if (onDelta != null) {
                        if (stdoutNow.length > stdoutOffset) onDelta(stdoutNow.substring(stdoutOffset), false)
                        if (stderrNow.length > stderrOffset) onDelta(stderrNow.substring(stderrOffset), true)
                    }
                    return InternalResult(null, session.cwd, timedOut = false, sessionClosed = true)
                }
                if (deadline != null && System.currentTimeMillis() >= deadline) {
                    return InternalResult(null, session.cwd, timedOut = true, sessionClosed = false)
                }
                if (onDelta != null) {
                    stdoutOffset = emitStdoutDelta(stdoutNow, stdoutOffset, marker, onDelta)
                    if (stderrNow.length > stderrOffset) {
                        onDelta(stderrNow.substring(stderrOffset), true)
                        stderrOffset = stderrNow.length
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * A trailing partial line may be a status-line fragment (the marker arrives truncated at random 50ms poll points),
     * so hold it back until complete instead of flashing the marker to the user.
     */
    private fun emitStdoutDelta(
        text: String,
        offset: Int,
        marker: String,
        onDelta: (String, Boolean) -> Unit,
    ): Int {
        if (text.length <= offset) return offset
        var end = text.length
        val tailStart = text.lastIndexOf('\n') + 1
        if (tailStart < end) {
            val tail = text.substring(tailStart)
            if ("$marker:".startsWith(tail) || tail.startsWith("$marker:")) {
                end = tailStart
            }
        }
        if (end > offset) {
            onDelta(text.substring(offset, end), false)
            return end
        }
        return offset
    }

    /** Status line arrived: flush only the text before the marker line, trimming trailing whitespace. */
    private fun flushFinalStdout(
        text: String,
        offset: Int,
        marker: String,
        onDelta: (String, Boolean) -> Unit,
    ) {
        val markerIndex = text.indexOf("\n$marker:")
        val content = if (markerIndex >= 0) text.substring(0, markerIndex) else text
        val trimmed = content.trimEnd()
        if (trimmed.length > offset) {
            onDelta(trimmed.substring(offset), false)
        }
    }

    private class Session(
        val identity: String,
        val environment: TerminalEnvironment,
        var cwd: String,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector,
    ) {
        val execLock = Any()

        /** Command-line writes for exec share one lock with runtime user-input writes to avoid interleaved bytes. */
        val stdinLock = Any()

        @Volatile
        var closed: Boolean = false

        @Volatile
        var stopRequested: Boolean = false

        lateinit var stdoutThread: Thread
        lateinit var stderrThread: Thread
        lateinit var waiterThread: Thread
    }
}
