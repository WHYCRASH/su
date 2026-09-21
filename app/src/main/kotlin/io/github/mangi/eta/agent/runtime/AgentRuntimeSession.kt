package io.github.mangi.eta.agent.runtime


import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Ownership and the single terminal state of one runtime run.
 *
 * Service replacement, user cancellation, and normal completion must all go through this object, so an old run never sends to a new reply channel
 * and the same run never sends two final results.
 */
internal class AgentRuntimeSession(
    val runId: String,
    val controller: AgentRunController = AgentRunController(),
    eventSink: ((AgentEvent) -> Unit)? = null,
    resultSink: ((AgentRuntimeWire.RunResult) -> Unit)? = null,
) {
    private enum class State {
        RUNNING,
        STOPPING,
        COMMITTING,
        TERMINAL,
    }

    private val lock = ReentrantLock()
    private var state = State.RUNNING
    private val replayEvents = mutableListOf<AgentEvent>()
    private val subscribers = mutableListOf<Subscriber>()

    private data class Subscriber(
        val eventSink: (AgentEvent) -> Unit,
        val resultSink: (AgentRuntimeWire.RunResult) -> Unit,
    )

    init {
        if (eventSink != null || resultSink != null) {
            subscribers += Subscriber(
                eventSink = eventSink ?: {},
                resultSink = resultSink ?: {},
            )
        }
    }

    /** User stop cancels resources immediately; the worker must still seal its history. */
    fun requestStop(): Boolean {
        lock.withLock {
            if (state != State.RUNNING) return false
            state = State.STOPPING
        }
        controller.cancel()
        return true
    }

    var terminalResult: AgentRuntimeWire.RunResult? = null
        private set

    val isTerminal: Boolean
        get() = lock.withLock { state == State.TERMINAL }

    fun emit(event: AgentEvent): Boolean =
        lock.withLock {
            if (state != State.RUNNING) return false
            recordForReplay(event)
            subscribers.forEach { it.eventSink(event) }
            true
        }

    /**
     * The runtime may keep running after the activity leaves the task stack. Safe history replay, completion acknowledgement, and live subscription
     * share one lock, so every event before the client acknowledgement is history and new events never cross the terminal boundary.
     */
    fun attach(
        eventSink: (AgentEvent) -> Unit,
        resultSink: (AgentRuntimeWire.RunResult) -> Unit,
        onReplayComplete: () -> Unit = {},
    ): Boolean = lock.withLock {
        if (state == State.TERMINAL) return false
        replayEvents.forEach(eventSink)
        onReplayComplete()
        subscribers += Subscriber(eventSink, resultSink)
        true
    }

    fun steer(text: String): Boolean {
        lock.withLock {
            if (state != State.RUNNING) return false
        }
        // Never hold the session lock to interrupt SSE: the reader thread needs the same lock in emit,
        // and EventSource.cancel() waits for the reader thread, which would drain the current reply before returning.
        return controller.steer(text)
    }

    fun requestCompact(
        keepRecentMessages: Int? = null,
        compressModelConfig: io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig? = null,
    ): Boolean {
        lock.withLock {
            if (state != State.RUNNING) return false
        }
        return controller.requestCompact(keepRecentMessages, compressModelConfig)
    }

    fun <T : AgentEvent> steer(
        text: String,
        imagesJson: String = "[]",
        eventFactory: () -> T,
    ): T? {
        val accepted = lock.withLock {
            if (state != State.RUNNING) return null
            val interrupt = controller.enqueueSteering(AgentRunController.SteeringInput(text, imagesJson)) ?: return null
            val event = eventFactory()
            recordForReplay(event)
            subscribers.forEach { subscriber -> runCatching { subscriber.eventSink(event) } }
            event to interrupt
        }
        // Never cancel network resources while holding the session lock.
        controller.interruptSteering(accepted.second)
        if (!accepted.second) controller.resume()
        return accepted.first
    }

    private fun recordForReplay(event: AgentEvent) {
        val projected = event.recoveryProjection() ?: return
        if (projected !is AgentEvent.AssistantBlockDelta) {
            replayEvents += projected
            return
        }
        val previous = replayEvents.lastOrNull() as? AgentEvent.AssistantBlockDelta
        if (
            previous != null &&
            previous.round == projected.round &&
            previous.kind == projected.kind &&
            previous.index == projected.index
        ) {
            replayEvents[replayEvents.lastIndex] = previous.copy(
                deltaChars = previous.deltaChars + projected.deltaChars,
                delta = previous.delta + projected.delta,
            )
        } else {
            replayEvents += projected
        }
    }

    /**
     * First race atomically for COMMITTING, then run pre-commit side effects and result publishing. Cancellation and replacement cannot overtake the commit winner,
     * so the split state of "client saw cancellation but the outbox kept a success" never happens; slow I/O never holds the lock.
     * [beforePublish] must absorb non-fatal persistence exceptions itself.
     */
    fun complete(
        result: AgentRuntimeWire.RunResult,
        beforePublish: (AgentRuntimeWire.RunResult) -> Unit = {},
    ): Boolean {
        val terminal = lock.withLock {
            if (state != State.RUNNING && state != State.STOPPING) return false
            require(result.runId == runId) { "Result runId does not match the active session" }
            val stopped = state == State.STOPPING
            state = State.COMMITTING
            if (stopped) result.copy(ok = false, error = "Stopped") else result
        }
        val commitFailure = runCatching { beforePublish(terminal) }.exceptionOrNull()
        lock.withLock {
            state = State.TERMINAL
            terminalResult = terminal
            subscribers.forEach { it.resultSink(terminal) }
            subscribers.clear()
            replayEvents.clear()
        }
        commitFailure?.let { throw it }
        return true
    }

    fun cancel(reason: String): Boolean {
        val result = lock.withLock {
            if (state != State.RUNNING) return false
            state = State.TERMINAL
            AgentRuntimeWire.RunResult(
                runId = runId,
                ok = false,
                content = "",
                error = reason,
            )
        }
        controller.cancel()
        lock.withLock {
            subscribers.forEach { it.resultSink(result) }
            subscribers.clear()
            replayEvents.clear()
        }
        return true
    }
}
