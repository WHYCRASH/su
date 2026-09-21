package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.oauth.OpenAiCodexOAuth
import io.github.mangi.eta.data.model.OpenAiEndpointMode

/** Compression-only Chat Completions / Responses override. Independent of the session provider. */
internal object AgentCompressionEndpoint {
    fun parse(value: String?): String =
        if (value == OpenAiEndpointMode.RESPONSES) OpenAiEndpointMode.RESPONSES
        else OpenAiEndpointMode.CHAT_COMPLETIONS

    fun canOverride(config: AgentModelClient.ModelConfig): Boolean {
        if (config.providerType != io.github.mangi.eta.data.model.ProviderTypes.OPENAI_COMPATIBLE) return false
        if (OpenAiCodexOAuth.isCodexEndpoint(config.baseUrl)) return false
        if (io.github.mangi.eta.data.model.RemovedProviderPolicy.isRemoved(config.baseUrl, config.openAiEndpointMode)) return false
        return config.openAiEndpointMode == OpenAiEndpointMode.CHAT_COMPLETIONS ||
            config.openAiEndpointMode == OpenAiEndpointMode.RESPONSES
    }

    fun apply(config: AgentModelClient.ModelConfig, endpointMode: String?): AgentModelClient.ModelConfig {
        if (!canOverride(config)) return config
        val resolved = parse(endpointMode)
        return if (config.openAiEndpointMode == resolved) config
        else config.copy(openAiEndpointMode = resolved)
    }
}

internal object AgentCompressionBoundary {
    /** Every cut is between complete tool batches; malformed/orphaned results are not compactable. */
    fun balancedCuts(history: List<AgentModelClient.ConversationMessage>): List<Int> {
        val cuts = collectCuts(history, strict = true)
        require(cuts.lastOrNull() == history.size) { "Tool batch has not completed yet" }
        return cuts
    }

    /** Complete-batch cut points even if the newest tool batch is still running. */
    fun availableCuts(history: List<AgentModelClient.ConversationMessage>): List<Int> =
        collectCuts(history, strict = false)

    private fun collectCuts(
        history: List<AgentModelClient.ConversationMessage>,
        strict: Boolean,
    ): List<Int> {
        val pending = mutableSetOf<String>()
        val cuts = mutableListOf(0)
        history.forEachIndexed { index, message ->
            if (message.toolCallsJson.isNotBlank()) {
                val calls = runCatching { org.json.JSONArray(message.toolCallsJson) }.getOrNull()
                if (calls == null) {
                    if (strict) require(false) { "Tool call ID missing or duplicated" }
                } else {
                    for (i in 0 until calls.length()) {
                        val id = calls.optJSONObject(i)?.optString("id").orEmpty()
                        if (strict) {
                            require(id.isNotBlank() && pending.add(id)) { "Tool call ID missing or duplicated" }
                        } else if (id.isNotBlank()) {
                            pending.add(id)
                        }
                    }
                }
            }
            if (message.role == "tool") {
                val id = message.toolCallId
                if (strict) {
                    require(id.isNotBlank() && pending.remove(id)) { "Tool result is missing a corresponding call" }
                } else if (id.isNotBlank()) {
                    pending.remove(id)
                } else {
                    pending.lastOrNull()?.let(pending::remove)
                }
            }
            if (pending.isEmpty()) cuts += index + 1
        }
        return cuts
    }

    /** Shared token-tail selection. Every cut remains at a complete tool-batch boundary. */
    fun selectStart(
        history: List<AgentModelClient.ConversationMessage>,
        contextWindow: Int,
        overflow: Boolean = false,
    ): Int {
        if (contextWindow <= 0) return 0
        val cut = continuationStart(history, continuationRetentionBudget(contextWindow, overflow))
        // Billed pressure includes request overhead. A short local history can still
        // need compaction; retain the newest complete unit if the full budget cannot cut.
        return if (cut > 0) cut else continuationStart(history, 1)
    }

    /**
     * Keep a priced recent tail for continued execution, matching DeepSeek harness
     * retainRatio=0.16. Overflow recovery may shrink this to a single token so
     * the newest complete tool batch can still be selected.
     */
    internal fun continuationRetentionBudget(contextWindow: Int, overflow: Boolean = false): Int {
        if (overflow) return 1
        if (contextWindow <= 0) return 1
        return maxOf(1, (contextWindow.toLong() * 16 / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** Never summarize the newest complete unit; walk backward to the next balanced cut. */
    fun continuationStart(history: List<AgentModelClient.ConversationMessage>, retainTokens: Int): Int {
        if (history.size < 2) return 0
        var tokens = 0L
        var start = history.lastIndex
        for (i in history.indices.reversed()) {
            tokens += AgentContextBudget.countMessage(history[i])
            start = i
            if (tokens >= retainTokens.coerceAtLeast(1)) break
        }
        return availableCuts(history).lastOrNull { it <= start && it < history.size } ?: 0
    }

    fun outputReserve(config: AgentModelClient.ModelConfig): Int {
        val body = org.json.JSONObject(config.extraBodyJson.ifBlank { "{}" })
        RequestBodyMerge.mergeCustomBody(body, config.customBody)
        return listOf("max_tokens", "max_completion_tokens", "max_output_tokens")
            .mapNotNull { key -> body.optLong(key, -1).takeIf { it > 0 } }
            .maxOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 4096
    }

    fun inputLimit(window: Int, outputReserve: Int = 4096): Int =
        (window.toLong() - outputReserve - maxOf(512, window / 20)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}
