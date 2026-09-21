package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * The thinking level determined by trying the compression model. Keep an in-process copy and write it to local config, so each compression doesn't have to retry from off/minimal every time.
 */
internal object CompressionReasoningStore {
    const val PREFS_KEY = "agent_compress_reasoning_effort_json"

    private val memory = ConcurrentHashMap<String, ReasoningEffort>()

    fun effortFor(config: AgentModelClient.ModelConfig): ReasoningEffort? {
        val key = key(config)
        memory[key]?.let { return it }
        val stored = JSONObject(Prefs.getString(PREFS_KEY, "{}")).optString(key)
        val effort = ReasoningEffort.fromWireValue(stored)
        if (effort != null) memory[key] = effort
        return effort
    }

    fun remember(config: AgentModelClient.ModelConfig, effort: ReasoningEffort) {
        val key = key(config)
        memory[key] = effort
        val json = JSONObject(Prefs.getString(PREFS_KEY, "{}"))
        json.put(key, effort.wireValue)
        Prefs.putString(PREFS_KEY, json.toString())
    }

    fun clearForTests() {
        memory.clear()
    }

    internal fun key(config: AgentModelClient.ModelConfig): String {
        val identity = config.providerId.ifBlank { config.baseUrl.trim() }
        return "$identity|${config.model.trim()}"
    }
}
