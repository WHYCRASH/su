package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Visible entries acquire foreground-execution eligibility before preparing or installing; the stop notification cancels the same task. */
internal fun CoroutineScope.launchForegroundExecution(
    context: Context,
    onUnavailable: () -> Unit,
    block: suspend () -> Unit,
): Job? {
    val id = "foreground-ui:${UUID.randomUUID()}"
    val job = launch(start = CoroutineStart.LAZY) { block() }
    if (!AgentExecutionService.acquire(context, id, onStop = { job.cancel() })) {
        job.cancel()
        onUnavailable()
        return null
    }
    job.invokeOnCompletion { AgentExecutionService.release(id) }
    job.start()
    return job
}
