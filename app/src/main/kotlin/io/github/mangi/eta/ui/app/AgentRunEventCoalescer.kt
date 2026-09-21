package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent

/**
 * Coalesce adjacent text deltas so every small model shard does not trigger its own Compose state update.
 *
 * Only events from the same run, round, content block, and kind are merged; block boundaries are still flushed immediately by the caller,
 * so the ordering semantics of Runtime events do not change.
 */
internal class AgentRunEventCoalescer {
    private val pendingByRun = mutableMapOf<String, PendingDelta>()

    fun append(
        runId: String,
        event: AgentEvent.AssistantBlockDelta,
    ): AgentEvent.AssistantBlockDelta? {
        val pending = pendingByRun[runId]
        if (pending?.matches(event) == true) {
            pending.delta.append(event.delta)
            pending.deltaChars += event.deltaChars
            return null
        }

        val ready = pending?.toEvent()
        pendingByRun[runId] = PendingDelta(event)
        return ready
    }

    fun flush(runId: String): AgentEvent.AssistantBlockDelta? =
        pendingByRun.remove(runId)?.toEvent()

    private class PendingDelta(event: AgentEvent.AssistantBlockDelta) {
        val round = event.round
        val kind = event.kind
        val index = event.index
        var deltaChars = event.deltaChars
        val delta = StringBuilder(event.delta)

        fun matches(event: AgentEvent.AssistantBlockDelta): Boolean =
            round == event.round && kind == event.kind && index == event.index

        fun toEvent(): AgentEvent.AssistantBlockDelta =
            AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind,
                index = index,
                deltaChars = deltaChars,
                delta = delta.toString(),
            )
    }
}
