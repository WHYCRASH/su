package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentQueuedCompactionTest {
    @get:org.junit.Rule val timeout = org.junit.rules.Timeout.seconds(45)
    @Test fun pressureIsReevaluatedAfterShrinkWithAtMostOneExtraPass() {
        val controller = AgentRunController()
        var compactions = 0
        var requests = 0
        val model = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1", apiKey = "test",
            model = "test", systemPrompt = "", contextWindow = 100_000)
        val history = JSONArray().put(AgentConversationCodec.userTextMessage("x".repeat(364_000)))
            .put(JSONObject().put("role", "assistant").put("content", "old"))
            .put(AgentConversationCodec.userTextMessage("protected"))
        val provider = object : AgentProviderClient {
            override val id = "pressure"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                requests++
                assertEquals(2, compactions)
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
            }
        }
        AgentLoop(model, history, JSONArray(), provider, AgentModelClient.ToolExecutor { error("no tools") },
            controller, AgentTraceFormatter(), onEvent = {},
            compactPolicy = AgentLoop.CompactPolicy(true, 100_000, 1, model),
            compactHistory = { source, policy ->
                compactions++
                check(compactions <= 2)
                val tail = source.drop(requireNotNull(policy.keepStartOverride))
                listOf(AgentModelClient.ConversationMessage("user", "[Conversation summary]\n" +
                    "x".repeat(if (compactions == 1) 360_000 else 4000))) + tail
            }).run()
        assertEquals(2, compactions)
        assertEquals(1, requests)
    }

    @Test fun queuedDuringStreamFinishesOnceAndCompactsWithoutContinuationOrToolReplay() {
        for (failCompaction in listOf(false, true)) {
            val controller = AgentRunController()
            val events = mutableListOf<AgentEvent>()
            val history = JSONArray().put(AgentConversationCodec.userTextMessage("old".repeat(4000)))
                .put(JSONObject().put("role", "assistant").put("content", "old result"))
                .put(AgentConversationCodec.userTextMessage("now"))
            var interrupted = false
            var responseComplete = false
            var requests = 0
            var compactions = 0
            val model = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1", apiKey = "test",
                model = "test", systemPrompt = "", contextWindow = 128_000,
                thinkingEnabled = true, reasoningEffort = ReasoningEffort.HIGH)
            val provider = object : AgentProviderClient {
                override val id = "queued-summary"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    assertEquals(1, ++requests)
                    assertTrue(request.config.thinkingEnabled)
                    val binding = runController.register(interruptible = true) { interrupted = true }
                    try {
                        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, 0))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "Full"))
                        repeat(2) { assertTrue(controller.requestCompact(keepRecentMessages = 1)) }
                        assertFalse(interrupted)
                        assertEquals(0, compactions)
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, " text"))
                        onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.TEXT, 0, content = "Full text", replaceContent = true))
                        responseComplete = true
                        return ProviderResponse(JSONObject().put("role", "assistant").put("content", "Full text").put("finish_reason", "stop"))
                    } finally {
                        binding.close()
                    }
                }
            }
            val result = AgentLoop(model, history, JSONArray(), provider,
                AgentModelClient.ToolExecutor { error("no tool replay") }, controller, AgentTraceFormatter(),
                onEvent = events::add, turnId = "original-turn",
                compactPolicy = AgentLoop.CompactPolicy(false, 128_000, 1, model),
                compactHistory = { source, policy ->
                    assertTrue(responseComplete)
                    compactions++
                    if (failCompaction) error("summary rejected")
                    listOf(AgentModelClient.ConversationMessage("user", "[Conversation summary]\nold facts")) + source.drop(requireNotNull(policy.keepStartOverride))
                }).run()
            assertEquals(1, requests)
            assertEquals(1, compactions)
            assertEquals("Full text", result.content)
            assertEquals("Full text", events.filterIsInstance<AgentEvent.AssistantBlockDelta>()
                .filter { it.kind == AgentEvent.AssistantBlockKind.TEXT }.joinToString("") { it.delta })
            assertEquals(listOf(1), events.filterIsInstance<AgentEvent.RoundStarted>().map { it.round })
            assertEquals(!failCompaction, events.filterIsInstance<AgentEvent.ContextCompacted>().single().applied)
            val last = history.getJSONObject(history.length() - 1)
            assertEquals("original-turn", last.getString(AgentTurnIdentity.JSON_KEY))
            assertEquals("Full text", last.getString("content"))
            assertFalse((0 until history.length()).any { history.getJSONObject(it).optString("content") == AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT })
            if (failCompaction) assertEquals("old".repeat(4000), history.getJSONObject(0).getString("content"))
            assertFalse(controller.requestCompact())
        }
    }
}
