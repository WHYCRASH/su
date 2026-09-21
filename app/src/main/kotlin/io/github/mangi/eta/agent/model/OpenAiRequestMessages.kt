package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Projects su session messages into the system-instruction shape OpenAI-compatible requests need. */
internal object OpenAiRequestMessages {
    fun forChatCompletions(source: JSONArray): JSONArray {
        val system = collectInstructions(source, SYSTEM_ROLES)
        return JSONArray().also { messages ->
            if (system.isNotBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", system))
            }
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in SYSTEM_ROLES) {
                    messages.put(JSONObject(message.toString()).also { it.remove(AgentTurnIdentity.JSON_KEY); it.remove(ResponsesReasoningState.KEY); it.remove("_eta_responses_output_items") })
                }
            }
        }
    }

    fun responsesInstructions(source: JSONArray): String =
        collectInstructions(source, RESPONSES_INSTRUCTION_ROLES)

    private fun collectInstructions(source: JSONArray, roles: Set<String>): String =
        buildList {
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in roles) continue
                providerMessageText(message.opt("content"))
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }.joinToString("\n\n")

    private val SYSTEM_ROLES = setOf("system")
    private val RESPONSES_INSTRUCTION_ROLES = setOf("system", "developer")
}
