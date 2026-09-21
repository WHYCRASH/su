package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null

    /**
     * The window occupied by the current request: aligned with ST "Input".
     * cache is already included in the prompt; completion is this round's output and will only enter the prompt in the next round.
     */
    fun occupancyTokens(): Int? {
        inputTokens?.takeIf { it > 0 }?.let { return it }
        return contextTokens?.takeIf { it > 0 }
    }
}
