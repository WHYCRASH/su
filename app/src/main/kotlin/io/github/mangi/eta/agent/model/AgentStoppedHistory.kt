package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Seal interrupted tool batches without claiming success or replaying side effects. */
internal object AgentStoppedHistory {
    fun closePendingTools(messages: JSONArray, turnId: String) {
        val pending = linkedMapOf<String, AgentModelClient.ToolCall>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString(AgentTurnIdentity.JSON_KEY) != turnId) continue
            when (message.optString("role")) {
                "assistant" -> AgentConversationCodec.parseToolCalls(message).forEach { pending[it.id] = it }
                "tool" -> pending.remove(message.optString("tool_call_id"))
            }
        }
        pending.values.forEach { call ->
            messages.put(AgentConversationCodec.toolResultMessage(call, AgentModelClient.ToolResult(
                content = JSONObject().put("ok", false).put("code", "STOPPED_OUTCOME_UNKNOWN")
                    .put("message", "The user has stopped this round; this call produced no confirmable result and may not have executed or may have partially executed. Do not automatically replay it; verify the actual state first.")
                    .toString(),
            )).put(AgentTurnIdentity.JSON_KEY, turnId))
        }
    }
}
