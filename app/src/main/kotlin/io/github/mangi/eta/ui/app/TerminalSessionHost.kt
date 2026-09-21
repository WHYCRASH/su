package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A terminal session belongs to the running task; recreating or leaving the Activity only detaches the UI and never kills processes the user started. */
internal class TerminalSessionHost private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val terminal = UserTerminalStore(context.applicationContext, scope)
    val console = ConsoleStore(context.applicationContext, scope)

    companion object {
        @Volatile private var instance: TerminalSessionHost? = null
        fun get(context: Context): TerminalSessionHost = instance ?: synchronized(this) {
            instance ?: TerminalSessionHost(context).also { instance = it }
        }
    }
}

/** Acquire foreground-execution rights first, then bind the session once the process exists, covering the race where the user hits stop in between. */
internal class TerminalSessionLease private constructor(
    private val id: String,
    private val onStop: (String) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private val session = AtomicReference<String?>(null)

    fun attach(sessionId: String): Boolean {
        session.set(sessionId)
        return !stopped.get()
    }

    fun release() { AgentExecutionService.release(id) }

    companion object {
        fun acquire(context: Context, onStop: (String) -> Unit): TerminalSessionLease? {
            val lease = TerminalSessionLease("terminal-ui:${UUID.randomUUID()}", onStop)
            return lease.takeIf {
                AgentExecutionService.acquire(context, lease.id) {
                    lease.stopped.set(true)
                    lease.session.get()?.let(lease.onStop)
                }
            }
        }
    }
}
