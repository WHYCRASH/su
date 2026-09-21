package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentOversizedSummaryTest {
    private fun summary(extra: String = "") = "[Conversation summary]\n" +
        AgentContextCompactor.SUMMARY_SECTIONS.joinToString("\n") { "## $it\n- (none)" } + extra
    private fun model(window: Int = 128_000) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test", systemPrompt = "", contextWindow = window,
    )
    private fun response(text: String = summary(), finish: String = "stop") = JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", finish)
    private fun provider(block: (ProviderRequest) -> JSONObject) = object : AgentProviderClient {
        override val id = "oversized-summary-test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            assertFits(request)
            return ProviderResponse(block(request))
        }
    }
    private fun assertFits(request: ProviderRequest) {
        val used = AgentContextBudget.estimate(request.messages).toLong() + AgentContextBudget.countTokens(request.tools.toString())
        assertTrue("input=$used", used <= AgentCompressionBoundary.inputLimit(
            requireNotNull(request.config.contextWindow), requireNotNull(request.config.summaryOutputLimit)))
    }
    private fun body(request: ProviderRequest) = request.messages.getJSONObject(request.messages.length() - 1).getString("content")
    private fun fragment(request: ProviderRequest): String? {
        val text = body(request)
        val metadata = Regex("UTF-16 range=(\\d+)\\.\\.(\\d+); total=\\d+]").find(text) ?: return null
        val length = metadata.groupValues[2].toInt() - metadata.groupValues[1].toInt()
        val start = text.indexOf("<history-fragment>\n") + "<history-fragment>\n".length
        assertTrue(start >= "<history-fragment>\n".length)
        assertEquals(0, request.tools.length())
        assertEquals(2, request.messages.length())
        assertTrue(text.contains("reference-only"))
        assertTrue(text.contains("not calls to execute"))
        return text.substring(start, start + length)
    }
    private fun tail() = AgentModelClient.ConversationMessage("user", "Current task, must be preserved in full 😀", turnId = "live-turn")

    @Test fun hugeMentionAbove128kIsReadInFullWithoutChangingSourceOrTail() {
        val referenced = "Leading evidence" + "Middle".repeat(55_000) + "middle" + "a".repeat(195_000) + "Trailing evidence😀"
        val content = "# Conversations mentioned by the user:\n" +
            JSONArray().put(JSONObject().put("id", "history").put("title", "Old conversation").put("transcript", referenced)) +
            "\n\nCurrent request: fix TTS."
        val first = AgentModelClient.ConversationMessage("user", content)
        assertTrue(AgentContextBudget.countMessage(first) > 128_000)
        val source = listOf(first, tail())
        val before = source.toList()
        val pieces = mutableListOf<String>()
        var calls = 0
        val result = AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(500_000), provider {
            calls++
            fragment(it)?.let(pieces::add)
            response()
        }), keepStartOverride = 1)
        assertTrue(pieces.size >= 2)
        assertEquals(AgentContextCompactor.messageToSummaryText(first), pieces.joinToString(""))
        assertEquals(pieces.size + 1, calls)
        assertEquals(before, source)
        assertSame(source.last(), result.last())
    }

    @Test fun replayOffsetsStayCorrectAfterAnOversizedMessage() {
        val source = listOf(AgentModelClient.ConversationMessage("user", "Middle".repeat(10_000)),
            AgentModelClient.ConversationMessage("assistant", "FOLLOWING ORIGINAL MESSAGE"), tail())
        val system = JSONObject().put("role", "system").put("content", "ORIGINAL SYSTEM")
        val raw = JSONArray().put(AgentConversationCodec.toJsonObject(source[0])).put(AgentConversationCodec.toJsonObject(source[1]))
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "do_not_run")))
        val replay = AgentContextCompactor.ReplayContext(JSONArray().put(system), raw, tools, "original-session")
        val originalReplay = raw.toString()
        var sawOriginalFollowing = false
        val pieces = mutableListOf<String>()
        AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider {
            fragment(it)?.let(pieces::add)
            if (it.sessionId == "original-session") {
                sawOriginalFollowing = true
                assertEquals(system.toString(), it.messages.getJSONObject(0).toString())
                assertEquals(source[1].content, it.messages.getJSONObject(1).getString("content"))
                assertEquals(tools.toString(), it.tools.toString())
                assertEquals(3, it.messages.length())
            }
            response()
        }), keepStartOverride = 2, replay = replay)
        assertTrue(sawOriginalFollowing)
        assertEquals(AgentContextCompactor.messageToSummaryText(source[0]), pieces.joinToString(""))
        assertEquals(originalReplay, raw.toString())
    }

    @Test fun hugeToolBatchBecomesInertEvidenceNotOrphanProtocolCalls() {
        val calls = JSONArray().put(JSONObject().put("id", "call-A").put("type", "function").put("function",
            JSONObject().put("name", "terminal").put("arguments", JSONObject().put("command", "Middle".repeat(7000)).toString())))
        val source = listOf(AgentModelClient.ConversationMessage("assistant", toolCallsJson = calls.toString()),
            AgentModelClient.ConversationMessage("tool", "RESULT-A " + "Result".repeat(3000), toolCallId = "call-A"), tail())
        val pieces = mutableListOf<String>()
        val result = AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider {
            fragment(it)?.let(pieces::add)
            for (i in 0 until it.messages.length()) {
                assertFalse(it.messages.getJSONObject(i).has("tool_calls"))
                assertFalse(it.messages.getJSONObject(i).has("tool_call_id"))
            }
            response()
        }), keepStartOverride = 2, toolExecutor = AgentModelClient.ToolExecutor { error("Must not execute") })
        assertEquals(source.take(2).joinToString("\n\n") { AgentContextCompactor.messageToSummaryText(it) }, pieces.joinToString(""))
        assertSame(source.last(), result.last())
    }

    @Test fun structuredTextFragmentsDoNotCarryImageBase64() {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "Text".repeat(8000)))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + "A".repeat(40_000))))
        val first = AgentModelClient.ConversationMessage("user", contentJson = content.toString())
        val source = listOf(first, tail())
        val pieces = mutableListOf<String>()
        AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider {
            assertFalse(it.messages.toString().contains("AAAA"))
            fragment(it)?.let(pieces::add)
            response()
        }), keepStartOverride = 1)
        assertEquals(AgentContextCompactor.messageToSummaryText(first), pieces.joinToString(""))
        assertTrue(source.first().contentJson.contains("AAAA"))
    }

    @Test fun excessiveFragmentsAreRejectedBeforeAnyProviderRequest() {
        val source = listOf(AgentModelClient.ConversationMessage("user", "Middle".repeat(160_000)), tail())
        var calls = 0
        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider { calls++; response() }), keepStartOverride = 1)
        }
        assertTrue(error.message!!.contains("Too many summary fragments"))
        assertEquals(0, calls)
        assertEquals(960_000, source.first().content.length)
    }

    @Test fun providerFailureInLaterFragmentNeverReplacesOriginalHistory() {
        val source = listOf(AgentModelClient.ConversationMessage("user", "Middle".repeat(10_000)), tail())
        val before = source.toList()
        var calls = 0
        assertThrows(java.io.IOException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider {
                if (++calls == 2) throw java.io.IOException("network failed")
                response()
            }), keepStartOverride = 1)
        }
        assertEquals(2, calls)
        assertEquals(before, source)
    }

    @Test fun cancellationAfterFirstFragmentDoesNotSendNextRequest() {
        val parent = AgentRunController()
        val source = listOf(AgentModelClient.ConversationMessage("user", "Middle".repeat(10_000)), tail())
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider {
                calls++; parent.cancel(); response()
            }), keepStartOverride = 1, controller = parent)
        }
        assertEquals(1, calls)
        assertEquals(60_000, source.first().content.length)
    }

    @Test fun protectedOversizedTailIsNotProjectedOrSplit() {
        val protected = tail().copy(content = "TAIL_MUST_NOT_SEND" + "Middle".repeat(100_000))
        val source = listOf(AgentModelClient.ConversationMessage("user", "old".repeat(5000)), protected)
        val result = AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider {
            assertFalse(it.messages.toString().contains("TAIL_MUST_NOT_SEND"))
            response()
        }), keepStartOverride = 1)
        assertSame(protected, result.last())
    }

    @Test fun malformedOrIncompleteToolBatchesStillFailBeforeSending() {
        val orphan = AgentModelClient.ConversationMessage("tool", "Middle".repeat(10_000), toolCallId = "missing")
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(listOf(orphan, tail()), AgentContextCompactor.Config(1, model(8192), provider {
                calls++; response()
            }), keepStartOverride = 1)
        }
        assertEquals(0, calls)
    }

    @Test fun tinyWindowCannotMakeEmptyFragmentsOrSendOversizedPrompt() {
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(listOf(AgentModelClient.ConversationMessage("user", "Middle".repeat(1000)), tail()),
                AgentContextCompactor.Config(1, model(2048), provider { calls++; response() }), keepStartOverride = 1)
        }
        assertEquals(0, calls)
    }

    @Test fun staleReplayIsStillRejectedBeforePlanning() {
        val source = listOf(AgentModelClient.ConversationMessage("user", "Middle".repeat(10_000)), tail())
        val replay = AgentContextCompactor.ReplayContext(JSONArray(), JSONArray().put(JSONObject().put("role", "user").put("content", "stale")), JSONArray(), "s")
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider { calls++; response() }), keepStartOverride = 1, replay = replay)
        }
        assertEquals(0, calls)
    }
    @Test fun mergeIsHierarchicalWhenIntermediateSummariesCannotFitOneRequest() {
        val prefix = (1..4).map { AgentModelClient.ConversationMessage("user", "SOURCE-$it " + "Middle".repeat(2800)) }
        val source = prefix + tail()
        var calls = 0
        val result = AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider {
            calls++
            response(if (calls <= 4) summary("\n- " + "Middle".repeat(1800)) else summary())
        }), keepStartOverride = 4)
        assertEquals(9, calls) // 4 source requests, 4 bounded intermediate merges, 1 final merge.
        assertEquals(AgentContextCompactor.coerceSummary(summary()), result.first().content)
        assertSame(source.last(), result.last())
    }

    @Test fun nonReducingMergeFailsWithoutUnboundedRequestsOrPartialCommit() {
        val prefix = (1..4).map { AgentModelClient.ConversationMessage("user", "SOURCE-$it " + "Middle".repeat(2800)) }
        val source = prefix + tail()
        val before = source.toList()
        var calls = 0
        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider {
                calls++; response(summary("\n- " + "Middle".repeat(1800)))
            }), keepStartOverride = 4)
        }
        assertTrue(error.message!!.contains("merge did not shrink the input"))
        assertEquals(8, calls)
        assertEquals(before, source)
    }

    @Test fun overlargeReplayOverheadFallsBackToTextWithoutSendingOriginalTools() {
        val first = AgentModelClient.ConversationMessage("user", "History".repeat(1000))
        val source = listOf(first, tail())
        val raw = JSONArray().put(AgentConversationCodec.toJsonObject(first))
        val system = JSONArray().put(JSONObject().put("role", "system").put("content", "Legacy system".repeat(5000)))
        val replay = AgentContextCompactor.ReplayContext(system, raw, JSONArray(), "overhead-session")
        var calls = 0
        AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider {
            calls++
            assertEquals(AgentContextCompactor.messageToSummaryText(first), fragment(it))
            assertNotEquals("overhead-session", it.sessionId)
            assertFalse(it.messages.toString().contains("Legacy system"))
            response()
        }), keepStartOverride = 1, replay = replay)
        assertEquals(1, calls)
    }

    @Test fun laterFragmentTruncationStillRejectsRatherThanAcceptingPartialSummary() {
        val source = listOf(AgentModelClient.ConversationMessage("user", "Middle".repeat(100_000)), tail())
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(), provider {
                calls++
                response(finish = if (calls == 1) "stop" else "length")
            }), keepStartOverride = 1)
        }
        assertEquals(2, calls) // 128K summaries already reserve 16K; no larger output retry fits.
        assertEquals(600_000, source.first().content.length)
    }

    @Test fun shrinkingButStillUnmergeableOutputsStopAtDepthLimit() {
        val prefix = (1..4).map { AgentModelClient.ConversationMessage("user", "SOURCE-$it " + "Middle".repeat(2800)) }
        val source = prefix + tail()
        var calls = 0
        val error = assertThrows(IllegalStateException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider {
                val stage = calls++ / 4
                response(summary("\n- " + "Middle".repeat(1800 - stage * 10)))
            }), keepStartOverride = 4)
        }
        assertTrue(error.message!!.contains("safety level limit"))
        assertEquals(20, calls) // 4 source + 4 levels * 4 intermediate requests, then stop.
        assertEquals(prefix.first(), source.first())
    }

    @Test fun finalMergeFailureCannotCommitSuccessfulFragmentSummaries() {
        val source = listOf(AgentModelClient.ConversationMessage("user", "Middle".repeat(10_000)), tail())
        val before = source.toList()
        var pieces = 0
        var merges = 0
        assertThrows(java.io.IOException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(8192), provider {
                if (fragment(it) != null) {
                    pieces++
                    response()
                } else {
                    merges++
                    throw java.io.IOException("merge unavailable")
                }
            }), keepStartOverride = 1)
        }
        assertTrue(pieces >= 2)
        assertEquals(1, merges)
        assertEquals(before, source)
    }

    @Test fun ordinaryFittingPrefixStillUsesOneRequestWithoutFragments() {
        val source = listOf(AgentModelClient.ConversationMessage("user", "Ordinary body text".repeat(1000)), tail())
        var calls = 0
        val result = AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(), provider {
            calls++
            assertNull(fragment(it))
            assertTrue(body(it).contains(source.first().content))
            response()
        }), keepStartOverride = 1)
        assertEquals(1, calls)
        assertSame(source.last(), result.last())
    }

}
