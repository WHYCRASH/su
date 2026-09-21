package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesReasoningStateTest {
    private val config = AgentModelClient.ModelConfig(
        providerSourceType = "custom", baseUrl = "https://example.com/v1", apiKey = "not-persisted",
        model = "deepseek-v4.1-flash", systemPrompt = "test",
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES, reasoningEffort = ReasoningEffort.HIGH,
    )
    private fun assistant() = JSONObject().put("role", "assistant").put("content", "let me look into this").put(
        "tool_calls", JSONArray().put(JSONObject().put("id", "call_1").put("type", "function").put(
            "function", JSONObject().put("name", "test").put("arguments", "{\"secret\":\"private-argument\"}"),
        )),
    )
    private fun reasoning() = JSONObject().put("type", "reasoning").put("id", "rs_1").put(
        "content", JSONArray().put(JSONObject().put("type", "reasoning_text").put("text", "genuine reasoning")),
    )
    private fun saved(message: JSONObject): JSONObject {
        val dto = AgentConversationCodec.durableMessage(message)
        val raw = AgentConversationCodec.encodeConversationCheckpoint(listOf(dto))
        return AgentConversationCodec.toJsonObject(AgentConversationCodec.decodeTranscript(raw).single())
    }
    private fun input(messages: JSONArray, model: AgentModelClient.ModelConfig = config) =
        ResponsesRequestBuilder.build(model, messages, JSONArray()).getJSONArray("input")

    @Test fun requestKeepsReasoningStatusAfterCompatibilitySwitchRemoved() {
        val reason = reasoning().put("status", "completed").put("encrypted_content", "opaque")
        val outputMessage = JSONObject().put("type", "message").put("role", "assistant")
            .put("status", "completed").put("content", JSONArray().put(
                JSONObject().put("type", "output_text").put("text", "answer"),
            ))
        val live = assistant()
        ResponsesEphemeralState.attachOutputItems(live, JSONArray().put(reason).put(outputMessage))
        ResponsesReasoningState.capture(live, config)
        for (message in listOf(live, saved(live))) {
            val sourceBefore = message.toString()
            val projected = input(JSONArray().put(message)).getJSONObject(0)
            assertEquals("completed", projected.getString("status"))
            assertEquals("rs_1", projected.getString("id"))
            assertEquals("opaque", projected.getString("encrypted_content"))
            assertEquals(sourceBefore, message.toString())
        }
        assertEquals("completed", outputMessage.getString("status"))
    }

    @Test fun checkpointAndIpcReplayOriginalReasoningBeforeCalls() {
        val message = assistant()
        ResponsesEphemeralState.attachOutputItems(message, JSONArray().put(reasoning()))
        ResponsesReasoningState.capture(message, config)
        val history = AgentConversationCodec.assistantHistoryMessage(message, AgentConversationCodec.parseToolCalls(message))
        val durable = saved(history)
        val ipc = AgentConversationCodec.encodeTranscriptForIpc(listOf(AgentConversationCodec.durableMessage(durable)))
        val replayed = AgentConversationCodec.toJsonObject(AgentConversationCodec.decodeTranscript(ipc).single())
        assertNull(ResponsesEphemeralState.outputItems(replayed))
        val result = input(JSONArray().put(replayed))
        assertEquals(reasoning().toString(), result.getJSONObject(0).toString())
        assertEquals("function_call", result.getJSONObject(2).getString("type"))
        assertFalse(replayed.getJSONObject(ResponsesReasoningState.KEY).toString().contains("private-argument"))
        assertFalse(ipc.contains("not-persisted"))
    }

    @Test fun scopesPreventReasoningReplayToOtherProviderOrModel() {
        val message = assistant()
        ResponsesEphemeralState.attachOutputItems(message, JSONArray().put(reasoning()))
        ResponsesReasoningState.capture(message, config)
        val restored = saved(message)
        assertNull(ResponsesReasoningState.items(restored, config.copy(model = "other")))
        assertNull(ResponsesReasoningState.items(restored, config.copy(baseUrl = "https://other.example/v1")))
        assertNull(ResponsesReasoningState.items(restored, config.copy(providerId = "other")))
        assertNotNull(ResponsesReasoningState.items(restored, config.copy(apiKey = "rotated")))
    }

    @Test fun oldToolHistoryBecomesEvidenceNotFakeThinkingOrExecutableCalls() {
        val message = assistant().put("reasoning_content", "probably just an old summary")
        val result = input(JSONArray().put(message).put(
            JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "STOPPED_OUTCOME_UNKNOWN"),
        ))
        assertEquals(2, result.length())
        for (i in 0 until result.length()) assertEquals("message", result.getJSONObject(i).getString("type"))
        assertTrue(result.getJSONObject(0).getString("content").contains("do not replay automatically"))
        assertTrue(result.getJSONObject(1).getString("content").contains("STOPPED_OUTCOME_UNKNOWN"))
        assertFalse(result.toString().contains("reasoning_text"))
        assertEquals("function_call", input(JSONArray().put(message), config.copy(model = "other"))
            .getJSONObject(1).getString("type"))
    }

    @Test fun otherProtocolsCannotLeakInternalReplayMetadata() {
        val message = assistant()
        ResponsesEphemeralState.attachOutputItems(message, JSONArray().put(reasoning()))
        ResponsesReasoningState.capture(message, config)
        val result = OpenAiRequestMessages.forChatCompletions(JSONArray().put(saved(message))).toString()
        assertFalse(result.contains(ResponsesReasoningState.KEY))
        assertFalse(result.contains("_eta_responses_output_items"))
    }

    @Test fun sensitiveToolRedactionIsNotBypassedByDurableReplay() {
        val message = assistant()
        ResponsesEphemeralState.attachOutputItems(message, JSONArray().put(reasoning()))
        ResponsesReasoningState.capture(message, config)
        val redacted = AgentConversationCodec.redactSensitiveMessages(
            listOf(AgentConversationCodec.durableMessage(message)), setOf("call_1"),
        ).single()
        val result = input(JSONArray().put(AgentConversationCodec.toJsonObject(redacted)))
        assertFalse(result.toString().contains("private-argument"))
        assertTrue(result.toString().contains("genuine reasoning"))
        assertTrue(result.getJSONObject(2).getString("arguments").contains("redacted"))
    }

    @Test fun malformedDataIsRejectedAndLegacyDtoRemainsReadable() {
        assertEquals("", ResponsesReasoningState.sanitize("not json"))
        val legacy = AgentConversationCodec.decodeTranscript("[{\"role\":\"assistant\",\"content\":\"old\"}]").single()
        assertEquals("", legacy.responsesReasoningJson)
    }
}
