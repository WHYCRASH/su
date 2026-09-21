package io.github.mangi.eta.agent.runtime

import android.graphics.Bitmap
import android.os.Parcel
import android.util.Base64
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeWireTest {
    private fun emptyHistoryDescriptor(): android.os.ParcelFileDescriptor {
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        java.io.FileOutputStream(pipe[1].fileDescriptor).use { output ->
            output.write("[]".toByteArray(Charsets.UTF_8))
            output.flush()
        }
        return pipe[0]
    }

    @Test fun childContextAndCompactingModelSurviveIpcAndReplay() {
        val stats = io.github.mangi.eta.agent.delegation.SubAgentContextStats(
            "child-task", 4, "implementation", "model-id", "Model name", "Provider", 128000,
            contextTokens = 64000, isCompacting = true)
        listOf(AgentEvent.ChildContextUpdated(stats), AgentEvent.ContextCompactionStarted(2, "Main model")).forEach { event ->
            assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
            assertEquals(event, AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(event)))
        }
    }

    @Test
    fun completionTimestampSurvivesWireAndDurableEventReplay() {
        val event = AgentEvent.RunFinished(3, 100, 1_800_000_000_000L)
        assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
        assertEquals(event, AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(event)))
        val legacy = AgentEvent.RunFinished(1, 20)
        assertEquals(legacy, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(legacy)))
    }

    @Test fun assistantIdentityTravelsWithConfigAndMissingIdentityDoesNotUseActiveAssistant() {
        val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
            model = "test", systemPrompt = "A", assistantId = "assistant-a")
        val request = AgentRuntimeWire.RunRequest(runId = "identity", prompt = "test", config = config, images = emptyList())
        val bundle = AgentRuntimeWire.toLegacyBundle(request, emptyHistoryDescriptor())
        bundle.remove(AgentRuntimeWire.KEY_HISTORY_FD)
        bundle.putParcelableArrayList(AgentRuntimeWire.KEY_HISTORY, java.util.ArrayList())
        val restored = AgentRuntimeWire.runRequestFromBundle(bundle)
        assertEquals("assistant-a", restored.assistantId)
        assertEquals("assistant-a", restored.config.assistantId)
        bundle.remove("assistant_id")
        assertEquals("", AgentRuntimeWire.runRequestFromBundle(bundle).assistantId)
    }

    @Test
    fun visionCapabilitySurvivesIpcAndMissingKeyStaysClosed() {
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid",
            apiKey = "test",
            model = "grok-4.6",
            systemPrompt = "",
            supportsVision = true,
            supportsVideo = true,
        )
        val request = AgentRuntimeWire.RunRequest(
            runId = "vision",
            prompt = "View image",
            config = config,
            images = emptyList(),
        )
        val bundle = AgentRuntimeWire.toLegacyBundle(request, emptyHistoryDescriptor())
        bundle.remove(AgentRuntimeWire.KEY_HISTORY_FD)
        bundle.putParcelableArrayList(AgentRuntimeWire.KEY_HISTORY, java.util.ArrayList())
        val restored = AgentRuntimeWire.runRequestFromBundle(bundle)
        assertTrue(restored.config.supportsVision)
        assertTrue(restored.config.supportsVideo)
        bundle.remove("supports_vision")
        bundle.remove("supports_video")
        val missing = AgentRuntimeWire.runRequestFromBundle(bundle)
        assertFalse(missing.config.supportsVision)
        assertFalse(missing.config.supportsVideo)
    }

    @Test
    fun modelSessionSurvivesIpcAndLegacyRequestsUseConversationIdentity() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-session", prompt = "Test",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1", apiKey = "test-key",
                model = "test-model", systemPrompt = "", reasoningEffort = ReasoningEffort.OFF,
            ),
            images = emptyList(),
            modelSessionId = "stable-session",
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "handoff", source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = AgentUiHandoffPayload("conversation-1").toJson(),
            ),
        )
        val bundle = AgentRuntimeWire.toLegacyBundle(request, emptyHistoryDescriptor())
        bundle.remove(AgentRuntimeWire.KEY_HISTORY_FD)
        bundle.putParcelableArrayList(AgentRuntimeWire.KEY_HISTORY, java.util.ArrayList())
        assertEquals(request, AgentRuntimeWire.runRequestFromBundle(bundle))
        bundle.remove("model_session_id")
        assertEquals("conversation-1", AgentRuntimeWire.runRequestFromBundle(bundle).effectiveModelSessionId)
        bundle.remove("handoff")
        assertEquals("run-session", AgentRuntimeWire.runRequestFromBundle(bundle).effectiveModelSessionId)
    }

    @Test
    fun retryEventSurvivesIpcAndArchiveJson() {
        val event = AgentEvent.ModelRetryScheduled(7, 2, 3, 4_000, "HTTP_429", "Server: Model busy; Retry-After: 45")
        assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
        assertEquals(event, AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(event)))
    }

    @Test
    fun oldRetryEventWithoutReasonDetailRemainsReadable() {
        val old = AgentEvent.ModelRetryScheduled(1, 1, 3, 2000, "HTTP_429")
        val bundle = AgentRuntimeWire.eventToBundle(old)
        bundle.remove("reason_detail")
        assertEquals(old, AgentRuntimeWire.eventFromBundle(bundle))
        assertTrue(!old.displayMessage.contains("Reason:"))
        assertTrue(old.copy(reasonDetail = "Model busy").displayMessage.contains("Reason: Model busy"))
    }

    @Test
    fun attachResponsePreservesRunIdentityAndDecision() {
        val accepted = AgentRuntimeWire.attachRunResponseBundle("run-1", attached = true)
        val rejected = AgentRuntimeWire.attachRunResponseBundle("run-2", attached = false)

        assertEquals("run-1", AgentRuntimeWire.runIdFromBundle(accepted))
        assertTrue(AgentRuntimeWire.attachRunSucceeded(accepted))
        assertEquals("run-2", AgentRuntimeWire.runIdFromBundle(rejected))
        assertFalse(AgentRuntimeWire.attachRunSucceeded(rejected))
    }

    @Test
    fun oversizedLegacyInlineImageRequestIsRejectedBeforeMessengerSend() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-large-image",
            prompt = "Analyze image",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
                reasoningEffort = ReasoningEffort.OFF,
            ),
            images = listOf(
                AgentModelClient.ModelImage(
                    reference = "data:image/png;base64,${"A".repeat(500_000)}",
                    mimeType = "image/png",
                    bytes = 375_000,
                )
            ),
        )

        assertThrows(AgentRuntimeWire.PayloadTooLargeException::class.java) {
            AgentRuntimeWire.toLegacyBundle(request, emptyHistoryDescriptor())
        }
    }

    @Test
    fun largeImageBodyUsesFileDescriptorAndStaysOutOfBinderBundle() {
        val imageBytes = ByteArray(600_000) { index -> (index % 251).toByte() }
        val dataUrl = "data:image/png;base64,${Base64.encodeToString(imageBytes, Base64.NO_WRAP)}"
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-large-image",
            prompt = "Analyze image",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
                reasoningEffort = ReasoningEffort.OFF,
            ),
            images = listOf(
                AgentModelClient.ModelImage(
                    reference = dataUrl,
                    mimeType = "image/png",
                    bytes = imageBytes.size,
                    source = "test",
                )
            ),
        )

        AgentRuntimeImageTransfer.prepare(
            RuntimeEnvironment.getApplication(),
            request.images,
        ).use { prepared ->
            val bundle = AgentRuntimeWire.toBundle(request, prepared.images, emptyHistoryDescriptor())
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(bundle)
                assertTrue(parcel.dataSize() < 64_000)
            } finally {
                parcel.recycle()
            }

            val materialized = AgentRuntimeImageTransfer.materialize(
                AgentRuntimeWire.incomingRunRequestFromBundle(bundle)
            )
            assertEquals(request.copy(images = emptyList()), materialized.copy(images = emptyList()))
            assertEquals(request.images.single().reference, materialized.images.single().reference)
            assertEquals(request.images.single().mimeType, materialized.images.single().mimeType)
            assertEquals(request.images.single().bytes, materialized.images.single().bytes)
            assertEquals(request.images.single().source, materialized.images.single().source)
        }
    }

    @Test
    fun localImageReferenceIsNotBase64EncodedUntilRuntimeIngestsIt() {
        val context = RuntimeEnvironment.getApplication()
        val sourceFile = File(context.cacheDir, "runtime-wire-source-${System.nanoTime()}.png")
        FileOutputStream(sourceFile).use { output ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(
                Bitmap.CompressFormat.PNG,
                100,
                output,
            )
        }
        try {
            val image = AgentImageCodec.fromTransferReference(
                context = context,
                value = sourceFile.absolutePath,
                source = "test_local",
            ) ?: error("Test image parsing failure")
            assertEquals(sourceFile.absolutePath, image.reference)
            val request = AgentRuntimeWire.RunRequest(
                runId = "run-local-image",
                prompt = "Analyze local image",
                config = AgentModelClient.ModelConfig(
                    baseUrl = "https://example.invalid/v1",
                    apiKey = "test-key",
                    model = "test-model",
                    systemPrompt = "",
                ),
                images = listOf(image),
            )

            AgentRuntimeImageTransfer.prepare(context, request.images).use { prepared ->
                assertNull(prepared.images.single().remoteUrl)
                assertTrue(prepared.images.single().fileDescriptor != null)
                val materialized = AgentRuntimeImageTransfer.materialize(
                    AgentRuntimeWire.incomingRunRequestFromBundle(
                        AgentRuntimeWire.toBundle(request, prepared.images, emptyHistoryDescriptor())
                    )
                )
                assertTrue(materialized.images.single().reference.startsWith("data:image/"))
                assertTrue(materialized.images.single().reference.contains(";base64,"))
                assertEquals(sourceFile.length().toInt(), materialized.images.single().bytes)
            }
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun completedRunDrainStaysUnderBinderTransactionBudget() {
        val runs = List(8) { index ->
            AgentRuntimeWire.CompletedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = "run-$index",
                    source = "agent_ui",
                    payload = "conversation-$index",
                ),
                result = AgentRuntimeWire.RunResult(
                    runId = "run-$index",
                    ok = true,
                    content = "c".repeat(80_000),
                    reasoningContent = "r".repeat(40_000),
                    transcript = listOf(
                        AgentModelClient.ConversationMessage(
                            role = "assistant",
                            content = "t".repeat(80_000),
                        )
                    ),
                ),
                createdAt = index.toLong(),
            )
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(AgentRuntimeWire.completedRunsToBundle(runs))
            assertTrue(parcel.dataSize() < 900_000)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun legacyModelConfigJsonDefaultsBrowserToolsToEnabled() {
        val config = Json.decodeFromString<AgentModelClient.ModelConfig>(
            """{"baseUrl":"https://api.openai.com/v1","apiKey":"test-key","model":"gpt-test","systemPrompt":"You are a mobile Agent"}"""
        )

        assertEquals(true, config.browserTools)
        assertEquals(true, config.deviceDirectTools)
        assertEquals(false, config.deviceSensitiveReadTools)
        assertEquals(false, config.deviceSensitiveActionTools)
    }

    @Test
    fun unknownReasoningEffortJsonFallsBackToDefault() {
        val config = Json.decodeFromString<AgentModelClient.ModelConfig>(
            """
            {
              "baseUrl":"https://api.openai.com/v1",
              "apiKey":"test-key",
              "model":"gpt-test",
              "systemPrompt":"system",
              "reasoningEffort":"future"
            }
            """.trimIndent()
        )

        assertEquals(ReasoningEffort.DEFAULT, config.reasoningEffort)
    }

    @Test
    fun runRequestBundleRoundTripPreservesConfigHistoryAndImages() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-1",
            turnId = "original-user-turn",
            prompt = "Continue analysis",
            config = AgentModelClient.ModelConfig(
                providerSourceType = "bailian",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKey = "test-key",
                model = "qwen3-max",
                contextWindow = 262_144,
                systemPrompt = "You are a mobile Agent",
                terminalTools = true,
                browserTools = true,
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
                thinkingEnabled = true,
                reasoningEffort = ReasoningEffort.HIGH,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.HIGH),
                    canDisable = true,
                ),
                extraBodyJson = """{"thinking_budget":256}""",
            ),
            images = listOf(
                AgentModelClient.ModelImage(
                    reference = "data:image/png;base64,abc",
                    mimeType = "image/png",
                    bytes = 123,
                    width = 1080,
                    height = 2400,
                    source = "screenshot",
                )
            ),
            history = listOf(
                AgentModelClient.ConversationMessage(
                    role = "user",
                    content = "Previous question",
                ),
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "Previous answer",
                ),
            ),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "handoff-1",
                source = "overlay",
                payload = """{"package":"com.tencent.mm"}""",
            ),
        )

        val prepared = AgentRuntimeHistoryTransfer.prepare(
            RuntimeEnvironment.getApplication(),
            request.history,
        )
        val roundTripped = try {
            val bundle = AgentRuntimeWire.toLegacyBundle(request, prepared.descriptor)
            assertEquals(true, bundle.containsKey("browser_tools"))
            assertEquals(true, bundle.getBoolean("browser_tools"))
            AgentRuntimeWire.runRequestFromBundle(bundle)
        } finally {
            prepared.close()
        }

        assertEquals("original-user-turn", roundTripped.effectiveTurnId)
        assertEquals("run-1", request.copy(turnId = "").effectiveTurnId)
        assertEquals(request, roundTripped)
        assertEquals(262_144, roundTripped.config.contextWindow)
        assertEquals(ReasoningEffort.HIGH, roundTripped.config.reasoningEffort)
    }

    @Test
    fun legacyRunRequestBundleDefaultsBrowserToolsToEnabled() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-legacy",
            prompt = "Read webpage",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "test-key",
                model = "gpt-test",
                systemPrompt = "You are a mobile Agent",
                browserTools = false,
            ),
            images = emptyList(),
        )
        val legacyBundle = AgentRuntimeWire.toLegacyBundle(request, emptyHistoryDescriptor()).apply {
            remove("browser_tools")
            remove("context_window")
            remove("reasoning_effort")
            putBoolean("thinking_enabled", true)
        }

        val roundTripped = AgentRuntimeWire.runRequestFromBundle(legacyBundle)

        assertEquals(true, roundTripped.config.browserTools)
        assertNull(roundTripped.config.contextWindow)
        assertEquals(ReasoningEffort.DEFAULT, roundTripped.config.reasoningEffort)
    }

    @Test
    fun legacyRunRequestUsesSafeDeviceToolDefaults() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-legacy-device-tools",
            prompt = "View device status",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "test-key",
                model = "gpt-test",
                systemPrompt = "You are a mobile Agent",
                deviceDirectTools = false,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
            ),
            images = emptyList(),
        )
        val legacyBundle = AgentRuntimeWire.toLegacyBundle(request, emptyHistoryDescriptor()).apply {
            remove("device_direct_tools")
            remove("device_sensitive_read_tools")
            remove("device_sensitive_action_tools")
        }

        val config = AgentRuntimeWire.runRequestFromBundle(legacyBundle).config

        assertEquals(true, config.deviceDirectTools)
        assertEquals(false, config.deviceSensitiveReadTools)
        assertEquals(false, config.deviceSensitiveActionTools)
    }

    @Test
    fun browserToolArgumentsSummaryOmitsSensitiveInputAndUrlParts() {
        val summary = AgentModelClient.summarizeBrowserToolArguments(
            """{"action":"type","url":"https://user:password@example.com/private?q=token#fragment","text":"secret input"}"""
        )

        assertEquals("Enter content · example.com", summary)
    }

    @Test
    fun browserToolResultSummaryKeepsSafeMetadataAndFailureState() {
        val content = """{"ok":%s,"action":"get_readable","url":"https://user:password@example.com/private?q=token#fragment","title":"Example","text":"secret body","elements":[{},{}],"truncated":true}"""

        val failure = AgentModelClient.summarizeToolResult(
            toolName = "browser_use",
            result = AgentModelClient.ToolResult(content = content.format("false")),
        )
        assertEquals("Failed", failure)

        val success = AgentModelClient.summarizeToolResult(
            toolName = "browser_use",
            result = AgentModelClient.ToolResult(content = content.format("true")),
        )
        assertEquals("Content extracted · example.com · \"Example\" · About 11 characters · 2 elements · Truncated", success)

        listOf("user:password", "secret body", "private", "token", "ok=").forEach { leaked ->
            assertTrue("$leaked leaked: $success", !success.contains(leaked))
            assertTrue("$leaked leaked: $failure", !failure.contains(leaked))
        }
    }

    @Test
    fun openUriArgumentsSummaryOmitsPathCredentialsAndQuery() {
        val summary = AgentModelClient.summarizeOpenUriArguments(
            """{"uri":"https://user:password@example.com/private/access_token/value?q=secret#fragment"}"""
        )

        assertEquals("Hand off to external app · https · example.com", summary)
    }

    @Test
    fun eventBundleRoundTripPreservesReasoningAndUsage() {
        val events = listOf(
            AgentEvent.AssistantBlockStart(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
            ),
            AgentEvent.AssistantBlockDelta(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                deltaChars = 4,
                delta = "Thinking",
            ),
            AgentEvent.AssistantBlockEnd(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                contentChars = 4,
                replacementContent = "Thinking",
            ),
            AgentEvent.AssistantReceived(
                round = 2,
                contentChars = 12,
                reasoningContent = "Full thinking content",
                toolNames = listOf("observe_screen", "input_text"),
            ),
            AgentEvent.UsageReceived(
                round = 2,
                usage = AgentTokenUsage(
                    contextTokens = 4096,
                    inputTokens = 1200,
                    outputTokens = 320,
                    reasoningTokens = 80,
                    cachedTokens = 900,
                ),
            ),
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "call_abc",
                name = "run_command",
                argsPreview = "Run command · Android · root",
                command = "pm list packages | head",
            ),
            AgentEvent.ToolFinished(
                round = 2,
                toolCallId = "call_abc",
                name = "observe_screen",
                resultSummary = "ok=true, chars=120",
                imageCount = 1,
                imageBytes = 2048,
                success = true,
            ),
            // Older Runtime versions do not send the success field, and default events must also round-trip completely
            AgentEvent.ToolFinished(
                round = 2,
                toolCallId = "call_legacy",
                name = "tap",
                resultSummary = "ok=false, chars=24",
                imageCount = 0,
                imageBytes = 0,
            ),
            AgentEvent.ContextCompactionStarted(round = 2),
            AgentEvent.ContextCompacted(
                round = 2,
                applied = true,
                originalCount = 12,
                compactedCount = 5,
                history = listOf(
                    AgentModelClient.ConversationMessage(
                        role = "system",
                        content = "[Conversation summary]\nOld context",
                    ),
                    AgentModelClient.ConversationMessage(role = "user", content = "Continue"),
                ),
                compressorLabel = "Fish · grok",
            ),
        )

        events.forEach { event ->
            assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
        }
    }

    @Test
    fun runResultBundleRoundTripPreservesReasoningContent() {
        val result = AgentRuntimeWire.RunResult(
            runId = "run-1",
            ok = true,
            content = "Final answer",
            reasoningContent = "Analyze the problem first, then call tools, and summarize at the end.",
            transcript = listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "Final answer",
                    reasoningContent = "Analyze the problem first, then call tools, and summarize at the end.",
                )
            ),
        )

        val roundTripped = AgentRuntimeWire.runResultFromBundle(AgentRuntimeWire.toBundle(result))

        assertEquals(result, roundTripped)
    }

    @Test
    fun entryHandoffBundleRoundTripPreservesEntrySurfacePolicy() {
        val handoff = AgentRuntimeWire.EntryHandoff(
            id = "handoff-1",
            source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
            payload = """{"userText":"open settings"}""",
            dismissEntrySurfaceOnForegroundOperation = true,
        )

        val roundTripped = AgentRuntimeWire.entryHandoffFromBundle(AgentRuntimeWire.toBundle(handoff))

        assertEquals(handoff, roundTripped)
    }

    @Test
    fun entryHandoffDefaultsToKeepingEntrySurfaceVisible() {
        val handoff = AgentRuntimeWire.entryHandoffFromBundle(
            AgentRuntimeWire.toBundle(
                AgentRuntimeWire.EntryHandoff(
                    id = "handoff-1",
                    source = "app",
                    payload = "{}",
                )
            )
        )

        assertEquals(false, handoff.dismissEntrySurfaceOnForegroundOperation)
    }

    @Test
    fun handoffWithoutDismissFlagDefaultsToKeepingEntrySurfaceVisible() {
        val bundle = AgentRuntimeWire.toBundle(
            AgentRuntimeWire.EntryHandoff(
                id = "handoff-1",
                source = "app",
                payload = "{}",
            )
        ).apply {
            remove("handoff_dismiss_entry_surface_on_foreground_operation")
        }

        val handoff = AgentRuntimeWire.entryHandoffFromBundle(bundle)

        assertEquals(false, handoff.dismissEntrySurfaceOnForegroundOperation)
    }

    @Test
    fun runRequestBundleRoundTripPreservesHistoryAlreadyCompacted() {
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            reasoningEffort = ReasoningEffort.OFF,
        )
        val compacted = AgentRuntimeWire.RunRequest(
            runId = "run-compacted",
            prompt = "hello",
            config = config,
            images = emptyList(),
            historyAlreadyCompacted = true,
        )
        val compactedRoundTrip = AgentRuntimeWire.runRequestFromBundle(
            AgentRuntimeWire.toLegacyBundle(compacted, emptyHistoryDescriptor()),
        )
        assertTrue(compactedRoundTrip.historyAlreadyCompacted)

        val missingKey = AgentRuntimeWire.toLegacyBundle(
            compacted.copy(runId = "run-legacy"),
            emptyHistoryDescriptor(),
        ).apply {
            remove("history_already_compacted")
        }
        val legacyRoundTrip = AgentRuntimeWire.runRequestFromBundle(missingKey)
        assertFalse(legacyRoundTrip.historyAlreadyCompacted)
    }

    @Test
    fun largeResultTranscriptUsesFileDescriptorAndStaysOutOfBinderBundle() {
        val transcript = List(20) { index ->
            AgentModelClient.ConversationMessage(
                role = "assistant",
                content = "answer-$index-${"x".repeat(20_000)}",
            )
        }
        val result = AgentRuntimeWire.RunResult(
            runId = "run-large-transcript",
            ok = true,
            content = "final-answer",
            transcript = transcript,
        )

        AgentRuntimeTranscriptTransfer.prepare(
            RuntimeEnvironment.getApplication(),
            transcript,
        ).use { prepared ->
            val bundle = AgentRuntimeWire.toBundle(result, prepared.descriptor)
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(bundle)
                assertTrue(parcel.dataSize() < 64_000)
            } finally {
                parcel.recycle()
            }

            assertNull(bundle.getString(AgentRuntimeWire.KEY_TRANSCRIPT_JSON))
            val roundTripped = AgentRuntimeWire.runResultFromBundle(bundle)
            assertEquals(result.runId, roundTripped.runId)
            assertEquals(result.ok, roundTripped.ok)
            assertEquals(result.content, roundTripped.content)
            assertEquals(transcript.size, roundTripped.transcript.size)
            assertEquals(transcript.first().content, roundTripped.transcript.first().content)
            assertEquals(transcript.last().content, roundTripped.transcript.last().content)
        }
    }

}
