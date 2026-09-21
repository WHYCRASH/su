package io.github.mangi.eta.agent.runtime

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Service destruction only closes off new submissions; stop callbacks that have already been accepted must run to completion and must not be canceled along with the service coroutine. */
internal class ExecutionStopQueue(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-execution-stop").apply { isDaemon = true }
    },
    private val onFailure: (Exception) -> Unit,
) {
    fun submit(callbacks: List<() -> Unit>, afterStop: () -> Unit = {}) {
        executor.execute {
            callbacks.forEach { callback ->
                try {
                    callback()
                } catch (failure: Exception) {
                    onFailure(failure)
                }
            }
            afterStop()
        }
    }

    fun close(callbacks: List<() -> Unit>) {
        submit(callbacks)
        executor.shutdown()
    }
}
