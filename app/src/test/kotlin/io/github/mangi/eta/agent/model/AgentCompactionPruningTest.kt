package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentCompactionPruningTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun model() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test",
        systemPrompt = "", contextWindow = 20_000,
    )
    private fun batch(id: String, body: String) = listOf(
        AgentModelClient.ConversationMessage("assistant", "", toolCallsJson =
            """[{"id":"$id","type":"function","function":{"name":"test","arguments":"{}"}}]"""),
        AgentModelClient.ConversationMessage("tool", body, toolCallId = id),
    )
    private fun history() = listOf(AgentModelClient.ConversationMessage("user", "old task")) +
        batch("old", "OLD ".repeat(20_000)) +
        AgentModelClient.ConversationMessage("user", "protected task") + batch("live", "LIVE ".repeat(4000))
    private fun summary() = "[Conversation summary]\n" + AgentContextCompactor.SUMMARY_SECTIONS.joinToString("\n") { "## $it\n- (none)" }
    private fun provider(block: (ProviderRequest) -> Unit) = object : AgentProviderClient {
        override val id = "pruning-test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            block(request)
            return ProviderResponse(JSONObject().put("role", "assistant").put("content", summary()).put("finish_reason", "stop"))
        }
    }

    @Test fun prefixPruningNeverChangesProtectedLongToolResult() {
        val source = history()
        val archive = AgentCompactionArchive(temporary.root, "strict")
        val cut = AgentCompressionBoundary.selectStart(source, 20_000)
        assertEquals(4, cut)
        val working = AgentContextCompactor.pruneOversizedToolResults(source, archive, cut)
        assertTrue(working[2].content.contains(AgentContextCompactor.TOOL_PRUNED_PREFIX))
        assertEquals(source.drop(cut), working.drop(cut))
        val result = AgentContextCompactor.compress(working,
            AgentContextCompactor.Config(1, model(), provider {}, archive), keepStartOverride = cut)
        assertEquals(source.drop(cut), result.takeLast(source.size - cut))
        assertEquals("LIVE ".repeat(4000), result.last().content)
    }

    @Test fun summarizationDoesNotPruneOrCreateArchivesEvenWhenArchiveIsProvided() {
        val source = listOf(AgentModelClient.ConversationMessage("user", "x".repeat(10_000))) +
            AgentModelClient.ConversationMessage("user", "protected") + batch("live", "L".repeat(20_000))
        val result = AgentContextCompactor.compress(source,
            AgentContextCompactor.Config(1, model(), provider {}, AgentCompactionArchive(temporary.root, "pure")))
        assertEquals(source.drop(1), result.drop(1))
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun replaySendsThePrunedSnapshotAndPreservesProviderFields() {
        val source = history()
        val cut = 3
        val working = AgentContextCompactor.pruneOversizedToolResults(source,
            AgentCompactionArchive(temporary.root, "replay"), cut)
        val raw = JSONArray().also { array -> working.take(cut).forEach { array.put(AgentConversationCodec.toJsonObject(it)) } }
        raw.getJSONObject(1).put("provider_private", "opaque-kept")
        val replay = AgentContextCompactor.ReplayContext(JSONArray(), raw, JSONArray(), "session")
        var calls = 0
        AgentContextCompactor.compress(working, AgentContextCompactor.Config(1, model(), provider { request ->
            calls++
            assertEquals(working[2].content, request.messages.getJSONObject(2).getString("content"))
            assertEquals("opaque-kept", request.messages.getJSONObject(1).getString("provider_private"))
            assertFalse(request.messages.toString().contains(source[2].content))
        }), keepStartOverride = cut, replay = replay)
        assertEquals(1, calls)
        assertEquals("opaque-kept", raw.getJSONObject(1).getString("provider_private"))
    }

    @Test fun staleUnprunedReplayIsRejectedBeforeProviderCall() {
        val source = history()
        val working = AgentContextCompactor.pruneOversizedToolResults(source,
            AgentCompactionArchive(temporary.root, "stale"), 3)
        val replay = AgentContextCompactor.ReplayContext(JSONArray(), JSONArray().also { array ->
            source.take(3).forEach { array.put(AgentConversationCodec.toJsonObject(it)) }
        }, JSONArray(), "session")
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(working, AgentContextCompactor.Config(1, model(), provider {
                fail("Stale replay must never be sent")
            }), keepStartOverride = 3, replay = replay)
        }
        assertTrue(failure.message.orEmpty().contains("Summary replay is inconsistent with the selected history"))
    }

    @Test fun noEligiblePrefixMeansNoPruningAndNoArchiveWrites() {
        val source = history()
        assertEquals(source, AgentContextCompactor.pruneOversizedToolResults(source,
            AgentCompactionArchive(temporary.root, "none"), 0))
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }
}
