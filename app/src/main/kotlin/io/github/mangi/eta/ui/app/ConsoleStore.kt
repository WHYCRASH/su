package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.ConsoleSessionController
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.TerminalScreenBuffer
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.agent.terminal.ptySupported
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One console frame: line object identity is stable; the render layer reuses by Line.id and reorders by Line.version. */
@Immutable
internal data class ConsoleFrame(
    val lines: List<TerminalScreenBuffer.Line> = emptyList(),
    val screenRows: Int = 0,
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
    val cursorVisible: Boolean = true,
)

@Immutable
internal data class ConsoleSessionUi(
    val id: String,
    val environment: TerminalEnvironment,
    val exited: Boolean = false,
)

@Immutable
internal data class ConsoleUiState(
    val sessions: List<ConsoleSessionUi> = emptyList(),
    val activeSessionId: String? = null,
    /** Environment tab selection; matches the current session environment, or the target for a new session when empty. */
    val environment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val linuxEnvironment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val connected: Boolean = false,
    val exited: Boolean = false,
    /** null = probing; when false the entry falls back to block-style terminal. */
    val ptySupported: Boolean? = null,
    val failMessage: String? = null,
    /** Current session view; each session keeps its own screen buffer. */
    val frame: ConsoleFrame = ConsoleFrame(),
)

/**
 * App-level owner of the console page: holds multiple PTY sessions with their own screen buffers.
 * Byte streams feed [TerminalScreenBuffer] on IO threads and publish the current session's frame to UI on a throttle.
 * Sessions survive leaving the page; plain sessions are held by the foreground execution service and reclaimed after the user stops them.
 */
internal class ConsoleStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val FLUSH_INTERVAL_MS = 50L
        const val SCROLLBACK_LINES = 500
    }

    private val appContext = context.applicationContext
    private val sessionLeases = ConcurrentHashMap<String, TerminalSessionLease>()
    private val controller = ConsoleSessionController(
        logger = AndroidAgentLogger,
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val bufferLock = Any()
    private val initialLinuxEnvironment =
        LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment

    /** Per-session screen buffers; writes happen on IO threads under lock, UI threads read snapshots under lock. */
    private val buffers = mutableMapOf<String, TerminalScreenBuffer>()
    private val sessionSizes = mutableMapOf<String, Pair<Int, Int>>()

    private val _uiState = MutableStateFlow(
        ConsoleUiState(
            environment = if (isReady(initialLinuxEnvironment)) {
                initialLinuxEnvironment
            } else {
                TerminalEnvironment.ANDROID
            },
            linuxEnvironment = initialLinuxEnvironment,
        )
    )
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    private var lastFlushMs = 0L
    private var flushScheduled = false
    private var lastCols = 0
    private var lastRows = 0

    /** PTY prerequisites probe; the result is cached in state, and the UI offers a fallback entry when unsupported. */
    fun probePtySupport() {
        scope.launch {
            val supported = withContext(Dispatchers.IO) { ptySupported(ShellProcessSupervisor()) }
            _uiState.update { it.copy(ptySupported = supported) }
        }
    }

    fun refreshLinuxEnvironment() {
        val selected = LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment
        _uiState.update { state ->
            state.copy(
                linuxEnvironment = selected,
                environment = if (state.environment.isLinux) selected else state.environment,
            )
        }
    }

    /**
     * Make sure a live session exists; re-entering on grid-size changes does not rebuild a live current session.
     * [environment] is only used to create a new session when nothing is reusable.
     */
    fun open(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        lastCols = cols
        lastRows = rows
        val state = _uiState.value
        val activeId = state.activeSessionId
        if (activeId != null && controller.sessionAlive(activeId)) return
        if (activeId == null) {
            val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
            if (reusable != null && controller.sessionAlive(reusable.id)) {
                switchSession(reusable.id)
                return
            }
        }
        createSession(environment, cols, rows)
    }

    /** Create a session with the current environment and the most recent grid size. */
    fun newSession() {
        if (lastCols <= 0 || lastRows <= 0) return
        createSession(_uiState.value.environment, lastCols, lastRows)
    }

    fun switchSession(sessionId: String) {
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        _uiState.update { state ->
            state.copy(
                activeSessionId = sessionId,
                environment = session.environment,
                connected = !session.exited,
                exited = session.exited,
                failMessage = null,
            )
        }
        flushFrame(sessionId)
    }

    /** Close a session; closing the current one switches to the most recent remaining session, or back to empty state. */
    fun closeSession(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
        }
        synchronized(bufferLock) { buffers.remove(sessionId) }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            val sessions = state.sessions.filterNot { it.id == sessionId }
            if (state.activeSessionId != sessionId) {
                state.copy(sessions = sessions)
            } else {
                val next = sessions.lastOrNull()
                state.copy(
                    sessions = sessions,
                    activeSessionId = next?.id,
                    environment = next?.environment ?: state.environment,
                    connected = next != null && !next.exited,
                    exited = next?.exited == true,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
        }
        _uiState.value.activeSessionId?.let { flushFrame(it) }
    }

    /** Restart a session: terminate it, then reopen with its original environment and grid size. */
    fun restartSession(sessionId: String) {
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        val size = sessionSizes[sessionId] ?: (lastCols to lastRows)
        if (size.first <= 0 || size.second <= 0) return
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
        }
        synchronized(bufferLock) { buffers.remove(sessionId) }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            state.copy(sessions = state.sessions.filterNot { it.id == sessionId })
        }
        createSession(session.environment, size.first, size.second)
    }

    /** Switch environment tabs: only change the target environment; jump to a live session of that environment if one exists, otherwise the grid callback creates one. */
    fun switchEnvironment(environment: TerminalEnvironment) {
        val state = _uiState.value
        if (state.environment == environment && state.activeSessionId != null) return
        val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
        if (reusable != null) {
            switchSession(reusable.id)
        } else {
            _uiState.update {
                it.copy(
                    environment = environment,
                    activeSessionId = null,
                    connected = false,
                    exited = false,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
        }
    }

    /** Reconnect the current session after a disconnect, with the same environment and grid size. */
    fun reconnect() {
        val activeId = _uiState.value.activeSessionId ?: return
        restartSession(activeId)
    }

    fun write(text: String) {
        val sessionId = _uiState.value.activeSessionId ?: return
        scope.launch(Dispatchers.IO) { controller.write(sessionId, text) }
    }

    fun close() {
        controller.close()
        sessionLeases.values.forEach { it.release() }
        sessionLeases.clear()
    }

    private fun createSession(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        lastCols = cols
        lastRows = rows
        _uiState.update { it.copy(connected = false, exited = false, failMessage = null) }
        scope.launch(Dispatchers.IO) {
            val rootfs = environment.linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it).absolutePath }
            val identity = TerminalRuntime.defaultIdentity(environment, rootfs)
            val lease = if (identity == "user") {
                TerminalSessionLease.acquire(appContext) { sessionId -> scope.launch { closeSession(sessionId) } } ?: run {
                    _uiState.update { it.copy(failMessage = appContext.getString(R.string.capability_background_failed)) }
                    return@launch
                }
            } else null
            val result = try {
                controller.open(
                    environment = environment,
                    identity = identity,
                    cols = cols,
                    rows = rows,
                    onOutput = ::onOutput,
                    onExit = { sessionId ->
                        lease?.release()
                        onExit(sessionId)
                    },
                )
            } catch (failure: Throwable) {
                lease?.release()
                throw failure
            }
            when (result) {
                is ConsoleSessionController.OpenResult.Ready -> {
                    if (lease != null) {
                        sessionLeases[result.sessionId] = lease
                        if (!lease.attach(result.sessionId) || !controller.sessionAlive(result.sessionId)) {
                            controller.closeSession(result.sessionId)
                            sessionLeases.remove(result.sessionId)?.release()
                            _uiState.update { it.copy(failMessage = appContext.getString(R.string.terminal_session_closed)) }
                            return@launch
                        }
                    }
                    synchronized(bufferLock) {
                        buffers[result.sessionId] = TerminalScreenBuffer(cols, rows, SCROLLBACK_LINES)
                    }
                    sessionSizes[result.sessionId] = cols to rows
                    _uiState.update { state ->
                        state.copy(
                            sessions = state.sessions + ConsoleSessionUi(result.sessionId, environment),
                            activeSessionId = result.sessionId,
                            environment = environment,
                            connected = true,
                            exited = false,
                            frame = ConsoleFrame(),
                        )
                    }
                }
                is ConsoleSessionController.OpenResult.Failed -> {
                    lease?.release()
                    _uiState.update { it.copy(connected = false, failMessage = result.message) }
                }
            }
        }
    }

    private fun onOutput(sessionId: String, chunk: ByteArray) {
        synchronized(bufferLock) {
            buffers[sessionId]?.process(String(chunk, Charsets.UTF_8))
        }
        if (sessionId != _uiState.value.activeSessionId) return
        val now = System.currentTimeMillis()
        if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            flushFrame(sessionId)
        } else {
            scheduleFlush(sessionId)
        }
    }

    private fun scheduleFlush(sessionId: String) {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            delay(FLUSH_INTERVAL_MS)
            flushScheduled = false
            flushFrame(sessionId)
        }
    }

    private fun flushFrame(sessionId: String) {
        val frame = synchronized(bufferLock) {
            val current = buffers[sessionId] ?: return
            ConsoleFrame(
                lines = current.lines(),
                screenRows = current.rows,
                cursorRow = current.cursorRow,
                cursorCol = current.cursorCol,
                cursorVisible = current.cursorVisible,
            )
        }
        if (sessionId != _uiState.value.activeSessionId) return
        lastFlushMs = System.currentTimeMillis()
        _uiState.update { it.copy(frame = frame) }
    }

    private fun onExit(sessionId: String) {
        sessionLeases.remove(sessionId)?.release()
        _uiState.update { state ->
            val sessions = state.sessions.map {
                if (it.id == sessionId) it.copy(exited = true) else it
            }
            if (state.activeSessionId == sessionId) {
                state.copy(sessions = sessions, connected = false, exited = true)
            } else {
                state.copy(sessions = sessions)
            }
        }
        flushFrame(sessionId)
    }

    private fun isReady(environment: TerminalEnvironment): Boolean =
        environment.linuxDistribution?.let { distribution ->
            LinuxEnvironmentPaths.rootfsReady(
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath,
            )
        } ?: false
}
