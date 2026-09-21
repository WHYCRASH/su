package io.github.mangi.eta.agent.runtime

/** The recovery log keeps UI-visible events only; tool-argument deltas still live only in the current model turn. */
internal fun AgentEvent.recoveryProjection(): AgentEvent? = when (this) {
    is AgentEvent.AssistantBlockDelta ->
        takeUnless { kind == AgentEvent.AssistantBlockKind.TOOL_CALL }
    else -> this
}
