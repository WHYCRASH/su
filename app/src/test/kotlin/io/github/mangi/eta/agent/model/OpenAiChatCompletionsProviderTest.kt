package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatCompletionsProviderTest {

    @Test
    fun steeringDiscardsUnfinishedToolBatchEvenWithParseableArguments() {
        for (arguments in listOf("{\"command\":", "{}")) {
            val controller = AgentRunController()
            val body = sseChunk(JSONObject().put("content", "before")) +
                sseChunk(JSONObject().put("tool_calls", JSONArray().put(
                    JSONObject().put("index", 0).put("id", "draft").put("type", "function")
                        .put("function", JSONObject().put("name", "terminal").put("arguments", arguments)))))
            withSseServer(body) { baseUrl ->
                val response = OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), controller) { event ->
                    if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TOOL_CALL) {
                        controller.steer("new instruction")
                    }
                }
                assertEquals(AssistantStopReason.INTERRUPTED, response.stopReason)
                assertEquals("before", response.assistantMessage.getString("content"))
                assertTrue(!response.assistantMessage.has("tool_calls"))
                assertTrue(controller.hasPendingSteering)
            }
        }
    }

    @Test
    fun pauseThenSteerAlsoDiscardsUnfinishedArguments() {
        val controller = AgentRunController()
        val body = sseChunk(JSONObject().put("tool_calls", JSONArray().put(
            JSONObject().put("index", 0).put("id", "draft").put("type", "function")
                .put("function", JSONObject().put("name", "terminal").put("arguments", "{}")))))
        withSseServer(body) { baseUrl ->
            val response = OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), controller) { event ->
                if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TOOL_CALL) {
                    controller.pause()
                    controller.steer("new instruction")
                }
            }
            assertEquals(AssistantStopReason.INTERRUPTED, response.stopReason)
            assertTrue(!response.assistantMessage.has("tool_calls"))
            assertTrue(controller.hasPausedInterrupt)
            assertTrue(controller.hasPendingSteering)
        }
    }

    @Test
    fun explicitToolFinishRemainsAuthoritativeEvenWhenSteeringArrives() {
        // Malformed arguments in a genuinely completed response must still reach normal validation.
        val controller = AgentRunController()
        val body = sseChunk(JSONObject().put("tool_calls", JSONArray().put(
            JSONObject().put("index", 0).put("id", "complete").put("type", "function")
                .put("function", JSONObject().put("name", "terminal").put("arguments", "{")))),
            finishReason = "tool_calls")
        withSseServer(body) { baseUrl ->
            val response = OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), controller) { event ->
                if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TOOL_CALL) {
                    controller.steer("new instruction")
                }
            }
            assertEquals(AssistantStopReason.TOOL_USE, response.stopReason)
            assertEquals("{", response.assistantMessage.getJSONArray("tool_calls")
                .getJSONObject(0).getJSONObject("function").getString("arguments"))
        }
    }

    @Test
    fun eofWithParseableToolArgumentsButNoFinishIsNotAnExecutableBatch() {
        val body = sseChunk(JSONObject().put("tool_calls", JSONArray().put(
            JSONObject().put("index", 0).put("id", "draft").put("type", "function")
                .put("function", JSONObject().put("name", "terminal").put("arguments", "{}")))))
        withSseServer(body) { baseUrl ->
            org.junit.Assert.assertThrows(AgentModelFailure::class.java) {
                OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), AgentRunController())
            }
        }
    }

    @Test
    fun completeParsesTextDeltasWithDoneSentinel() {
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "Hel")))
            append(sseChunk(JSONObject().put("content", "lo"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("Hello", response.assistantMessage.getString("content"))
            assertEquals(
                "Hello",
                events.filterIsInstance<ProviderEvent.BlockDelta>()
                    .filter { it.kind == AssistantBlockKind.TEXT }
                    .joinToString("") { it.delta }
            )
        }
    }

    @Test
    fun completeSplitsVisibleBlocksWhenDeltaTypeChanges() {
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "Analyze first")))
            append(sseChunk(JSONObject().put("content", "Explain first")))
            append(sseChunk(JSONObject().put("reasoning_content", "Then confirm")))
            append(sseChunk(JSONObject().put("content", "Final answer"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add,
            )

            assertEquals(
                listOf(
                    "start:THINKING:0",
                    "delta:THINKING:0:Analyze first",
                    "end:THINKING:0",
                    "start:TEXT:1",
                    "delta:TEXT:1:Explain first",
                    "end:TEXT:1",
                    "start:THINKING:2",
                    "delta:THINKING:2:Then confirm",
                    "end:THINKING:2",
                    "start:TEXT:3",
                    "delta:TEXT:3:Final answer",
                    "end:TEXT:3",
                ),
                events.mapNotNull { event ->
                    when (event) {
                        is ProviderEvent.BlockStart -> "start:${event.kind}:${event.index}"
                        is ProviderEvent.BlockDelta -> "delta:${event.kind}:${event.index}:${event.delta}"
                        is ProviderEvent.BlockEnd -> "end:${event.kind}:${event.index}"
                        else -> null
                    }
                },
            )
        }
    }

    @Test
    fun completeAcceptsFinishReasonWhenServerClosesWithoutDone() {
        val usage = JSONObject()
            .put("prompt_tokens", 10)
            .put("completion_tokens", 2)
            .put("total_tokens", 12)
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "Done")))
            append(sseChunk(null, finishReason = "stop"))
            append(usageChunk(usage))
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("Done", response.assistantMessage.getString("content"))
            assertEquals(
                12,
                events.filterIsInstance<ProviderEvent.Usage>().single().usage.contextTokens
            )
        }
    }

    @Test
    fun completeRejectsOpenRouterMidStreamError() {
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "Partial content")))
            append(
                sseErrorChunk(
                    code = 502,
                    message = "Provider disconnected unexpectedly",
                    errorType = "provider_unavailable",
                )
            )
        }

        withSseServer(body) { baseUrl ->
            val thrown = runCatching {
                OpenAiChatCompletionsProvider.complete(
                    request = providerRequest(baseUrl) {
                        it.copy(providerSourceType = "openrouter")
                    },
                    runController = AgentRunController()
                )
            }.exceptionOrNull()

            assertNotNull(thrown)
            assertTrue(thrown is IllegalStateException)
            assertTrue(thrown?.message.orEmpty().contains("Provider disconnected unexpectedly"))
            assertTrue(thrown?.message.orEmpty().contains("provider_unavailable"))
        }
    }

    @Test
    fun completeTreatsTruncatedStreamWithTextAsNaturalStop() {
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "The project introduction has been written.")))
        }

        withSseServer(body) { baseUrl ->
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
            )

            assertEquals("The project introduction has been written.", response.assistantMessage.getString("content"))
            assertEquals("stop", response.assistantMessage.getString("finish_reason"))
        }
    }

    @Test
    fun completeDoesNotRequestDeprecatedOpenRouterUsageOption() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(": OPENROUTER PROCESSING\n\n")
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl) {
                    it.copy(providerSourceType = "openrouter")
                },
                runController = AgentRunController(),
            )

            assertTrue(!JSONObject(requestBody.get()).has("stream_options"))
        }
    }

    @Test
    fun completeMapsHtmlPageToClassifiedFailure() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/chat/completions") { exchange ->
            val bytes = "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val thrown = runCatching {
                OpenAiChatCompletionsProvider.complete(
                    request = providerRequest("http://127.0.0.1:${server.address.port}"),
                    runController = AgentRunController(),
                )
            }.exceptionOrNull()
            assertTrue(thrown is AgentModelFailure)
            assertTrue(thrown?.message.orEmpty().contains("web page"))
            assertTrue(!thrown?.message.orEmpty().startsWith("Invalid content-type"))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun requestMergesSystemMessagesAtTheBeginningForStrictChatTemplates() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = requestBody::set) { baseUrl ->
            val request = providerRequest(baseUrl).copy(
                messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "Basic constraints"))
                    .put(JSONObject().put("role", "user").put("content", "Old question"))
                    .put(JSONObject().put("role", "system").put("content", "Dynamic context"))
                    .put(JSONObject().put("role", "assistant").put("content", "Old answer"))
                    .put(JSONObject().put("role", "user").put("content", "Current question")),
            )

            OpenAiChatCompletionsProvider.complete(request, AgentRunController())
        }

        val sent = JSONObject(requestBody.get()).getJSONArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), sent.roles())
        assertEquals("Basic constraints\n\nDynamic context", sent.getJSONObject(0).getString("content"))
    }

    @Test
    fun completeParsesReasoningAliasAndFinalMessageSnapshot() {
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning", "Confirm the directory first.")))
            append(
                sseChunk(
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("id", "call_1")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "list_directory")
                                        .put("arguments", "{}")
                                )
                        )
                    ),
                    finishReason = "tool_calls",
                    message = JSONObject()
                        .put("role", "assistant")
                        .put("content", "")
                        .put("reasoning_content", "Confirm the directory first.")
                )
            )
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
            )
            assertEquals("Confirm the directory first.", response.assistantMessage.getString("reasoning_content"))
            assertEquals(
                "call_1",
                response.assistantMessage.getJSONArray("tool_calls").getJSONObject(0).getString("id"),
            )
        }
    }

    @Test
    fun requestReplaysAssistantReasoningWhenSendingToolResults() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = requestBody::set) { baseUrl ->
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl).copy(
                    messages = JSONArray()
                        .put(JSONObject().put("role", "user").put("content", "List the directory"))
                        .put(
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", "I will read it.")
                                .put("reasoning_content", "Need to list the directory first")
                                .put(
                                    "tool_calls",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("id", "call_00_test")
                                            .put("type", "function")
                                            .put(
                                                "function",
                                                JSONObject()
                                                    .put("name", "list_directory")
                                                    .put("arguments", "{\"path\":\"/tmp\"}"),
                                            ),
                                    ),
                                ),
                        )
                        .put(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", "call_00_test")
                                .put("content", "{\"ok\":true}"),
                        ),
                    tools = JSONArray().put(JSONObject().put("type", "function")),
                ),
                runController = AgentRunController(),
            )
        }

        val sent = JSONObject(requestBody.get()).getJSONArray("messages")
        val assistant = (0 until sent.length())
            .map { sent.getJSONObject(it) }
            .first { it.optString("role") == "assistant" }
        assertEquals("Need to list the directory first", assistant.getString("reasoning_content"))
        assertTrue(assistant.has("tool_calls"))
    }

    @Test
    fun completeAccumulatesChunkedToolCalls() {
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "Need to call the tool.")))
            append(
                sseChunk(
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("id", "call_1")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "term")
                                        .put("arguments", "{\"a\"")
                                )
                        )
                    )
                )
            )
            append(
                sseChunk(
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "inal")
                                        .put("arguments", ":1}")
                                )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            )
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add
            )

            val toolCall = response.assistantMessage
                .getJSONArray("tool_calls")
                .getJSONObject(0)
            assertEquals("call_1", toolCall.getString("id"))
            assertEquals("terminal", toolCall.getJSONObject("function").getString("name"))
            assertEquals("{\"a\":1}", toolCall.getJSONObject("function").getString("arguments"))
            assertEquals("Need to call the tool.", response.assistantMessage.getString("reasoning_content"))
            assertEquals(
                "Need to call the tool.",
                events.filterIsInstance<ProviderEvent.BlockDelta>()
                    .filter { it.kind == AssistantBlockKind.THINKING }
                    .joinToString("") { it.delta }
            )
            assertEquals(2, events.filterIsInstance<ProviderEvent.BlockDelta>().count { it.kind == AssistantBlockKind.TOOL_CALL })
        }
    }

    @Test
    fun completeParsesReasoningUsageAndMergesExtraBody() {
        val usage = JSONObject()
            .put("prompt_tokens", 10)
            .put("completion_tokens", 8)
            .put("total_tokens", 18)
            .put(
                "completion_tokens_details",
                JSONObject().put("reasoning_tokens", 5)
            )
            .put(
                "prompt_tokens_details",
                JSONObject().put("cached_tokens", 3)
            )
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "Analyze first")))
            append(sseChunk(JSONObject().put("content", "Result"), finishReason = "stop"))
            append(usageChunk(usage))
            append("data: [DONE]\n\n")
        }

        val requestBody = AtomicReference<String>()
        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(
                    baseUrl = baseUrl,
                    configTransform = {
                        it.copy(
                            thinkingEnabled = true,
                            extraBodyJson = """{"enable_thinking":false,"thinking_budget":50}"""
                        )
                    }
                ),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("Result", response.assistantMessage.getString("content"))
            assertEquals("Analyze first", response.assistantMessage.getString("reasoning_content"))
            val parsedUsage = events.filterIsInstance<ProviderEvent.Usage>().single().usage
            assertEquals(18, parsedUsage.contextTokens)
            assertEquals(10, parsedUsage.inputTokens)
            assertEquals(8, parsedUsage.outputTokens)
            assertEquals(5, parsedUsage.reasoningTokens)
            assertEquals(3, parsedUsage.cachedTokens)

            val request = JSONObject(requestBody.get())
            assertEquals(false, request.getBoolean("enable_thinking"))
            assertEquals(50, request.getInt("thinking_budget"))
            assertTrue(
                request.getJSONObject("stream_options").getBoolean("include_usage")
            )
        }
    }

    @Test
    fun completeRejectsStreamThatEndsBeforeDone() {
        val body = sseChunk(JSONObject().put("content", "partial"))

        withSseServer(body) { baseUrl ->
            val result = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController()
            )
            assertEquals("partial", result.assistantMessage.optString("content"))
            assertEquals("stop", result.assistantMessage.optString("finish_reason"))
        }
    }

    @Test
    fun completeBuildsDeepSeekThinkingRequest() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl) {
                    it.copy(
                        providerSourceType = "deepseek",
                        model = "deepseek-v4-pro",
                        thinkingEnabled = true,
                        reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.HIGH,
                    )
                },
                runController = AgentRunController(),
            )

            val request = JSONObject(requestBody.get())
            assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
            assertEquals("high", request.getString("reasoning_effort"))
        }
    }

    @Test
    fun completeBuildsKimiPreservedThinkingRequest() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl) {
                    it.copy(
                        providerSourceType = "moonshot",
                        model = "kimi-k2.6",
                        thinkingEnabled = true,
                    )
                },
                runController = AgentRunController(),
            )

            val thinking = JSONObject(requestBody.get()).getJSONObject("thinking")
            assertEquals("enabled", thinking.getString("type"))
            assertEquals("all", thinking.getString("keep"))
        }
    }

    private fun providerRequest(
        baseUrl: String,
        configTransform: (AgentModelClient.ModelConfig) -> AgentModelClient.ModelConfig = { it }
    ): ProviderRequest =
        ProviderRequest(
            config = configTransform(
                AgentModelClient.ModelConfig(
                    providerSourceType = "custom",
                    baseUrl = baseUrl,
                    apiKey = "test-key",
                    model = "test-model",
                    systemPrompt = "",
                    terminalTools = true
                )
            ),
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
            tools = JSONArray()
        )

    private fun sseChunk(
        delta: JSONObject?,
        finishReason: String? = null,
        message: JSONObject? = null,
    ): String {
        val choice = JSONObject()
            .put("delta", delta ?: JSONObject.NULL)
            .put("finish_reason", finishReason ?: JSONObject.NULL)
        if (message != null) {
            choice.put("message", message)
        }
        return "data: ${JSONObject().put("choices", JSONArray().put(choice))}\n\n"
    }

    private fun usageChunk(usage: JSONObject): String =
        "data: ${JSONObject().put("choices", JSONArray()).put("usage", usage)}\n\n"

    private fun sseErrorChunk(
        code: Int,
        message: String,
        errorType: String,
    ): String =
        "data: ${JSONObject()
            .put("error", JSONObject()
                .put("code", code)
                .put("message", message)
                .put("metadata", JSONObject().put("error_type", errorType)))
            .put("choices", JSONArray().put(
                JSONObject()
                    .put("delta", JSONObject().put("content", ""))
                    .put("finish_reason", "error")
            ))}\n\n"

    private fun withSseServer(
        body: String,
        onRequest: (String) -> Unit = {},
        block: (String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/chat/completions") { exchange ->
            onRequest(exchange.requestBody.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            })
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { output -> output.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun JSONArray.roles(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("role") }
}
