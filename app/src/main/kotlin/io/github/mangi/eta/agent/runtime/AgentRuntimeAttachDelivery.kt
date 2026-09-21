package io.github.mangi.eta.agent.runtime

/** Called by the client Main Handler in receive order; isolates history replay from live-event delivery. */
internal class AgentRuntimeAttachDelivery(
    private val onReplay: ((List<AgentEvent>) -> Unit)? = null,
    private val onEvent: (AgentEvent) -> Unit,
    private val onAttachResponse: (Boolean) -> Unit,
    private val onResult: (AgentRuntimeWire.RunResult) -> Unit,
) {
    private enum class State {
        REPLAYING,
        LIVE,
        CLOSED,
    }

    private var state = State.REPLAYING
    private val replayEvents = mutableListOf<AgentEvent>()

    fun event(event: AgentEvent) {
        when (state) {
            State.REPLAYING -> replayEvents += event
            State.LIVE -> onEvent(event)
            State.CLOSED -> Unit
        }
    }

    fun attachResponse(attached: Boolean) {
        if (state != State.REPLAYING) return
        if (attached) {
            state = State.LIVE
            deliverReplay()
        } else {
            state = State.CLOSED
            replayEvents.clear()
        }
        onAttachResponse(attached)
    }

    fun result(result: AgentRuntimeWire.RunResult) {
        if (state == State.CLOSED) return
        val needsReplay = state == State.REPLAYING
        state = State.CLOSED
        // The old service sends its success response after releasing the Session lock, so the terminal state may arrive first; replay history before delivering it.
        if (needsReplay) deliverReplay()
        onResult(result)
    }

    private fun deliverReplay() {
        val events = replayEvents.toList()
        replayEvents.clear()
        if (onReplay != null) {
            onReplay(events)
        } else {
            events.forEach(onEvent)
        }
    }
}
