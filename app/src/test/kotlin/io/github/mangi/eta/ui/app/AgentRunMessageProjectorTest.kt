package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunMessageProjectorTest {
    @Test
    fun supplementSeparatesResumedTextAndReplacementFromPausedBubble() {
        val projector = AgentRunMessageProjector()
        var messages: List<io.github.mangi.eta.ui.model.AgentChatMessageUi> = emptyList()
        messages = projector.appendTextDelta("run", 1, 0, "Before pause", messages)
        messages = messages + io.github.mangi.eta.ui.model.UserMessageUi(id = "user-run-supplement-1", content = "Addendum")
        messages = projector.appendTextDelta("run", 1, 1, "New output", messages)
        messages = projector.finalizeTextBlock("run", 1, 1, "New output finalized", messages)
        assertEquals(listOf("Before pause", "Addendum", "New output finalized"), messages.map {
            when (it) {
                is io.github.mangi.eta.ui.model.AgentMessageUi -> it.content
                is io.github.mangi.eta.ui.model.UserMessageUi -> it.content
                else -> error("Unexpected message")
            }
        })
    }

    @Test fun pausedBlockRetainsWhitespaceUntilTerminalFinalization() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta("resume", 1, 0, "first \n", emptyList())
        messages = projector.finalizeTextBlock("resume", 1, 0, null, messages)
        assertEquals("first \n", (messages.single() as AgentMessageUi).content)
        messages = projector.appendTextDelta("resume", 1, 0, "second", messages)
        assertEquals("first \nsecond", (messages.single() as AgentMessageUi).content)
        assertTrue((messages.single() as AgentMessageUi).isStreaming)
    }

    @Test
    fun retryKeepsFailedAttemptSeparateAndReplayClearsItsNotice() {
        val projector = AgentRunMessageProjector { 1_000L }
        val partial = projector.appendTextDelta("retry-run", 2, 0, "Incomplete", emptyList())
        val event = AgentEvent.ModelRetryScheduled(2, 1, 3, 2_000, "MODEL_TIMEOUT")
        val retrying = projector.scheduleModelRetry("retry-run", event, partial)
        assertEquals(-1, AgentRunMessageProjector.resultTargetIndex("retry-run", retrying))
        assertEquals("assistant-retry-run-3-result", AgentRunMessageProjector.resultFallbackId("retry-run", retrying))
        val repeated = projector.scheduleModelRetry("retry-run", event, retrying)
        assertEquals(retrying, repeated)
        val resumed = projector.appendTextDelta("retry-run", 3, 0, "Complete answer", retrying)
        assertEquals(listOf("Incomplete", "Complete answer"), resumed.filterIsInstance<AgentMessageUi>().map { it.content })
        assertFalse(resumed.filterIsInstance<AgentMessageUi>().first().isStreaming)
        assertEquals(SystemNoticeCode.ModelRetry, resumed.filterIsInstance<SystemNoticeMessageUi>().single().code)
        assertTrue(projector.resetForReplay("retry-run", resumed).isEmpty())
    }

    @Test
    fun runCompletionFinalizesUnclosedBlocksAndUnknownToolWithoutChangingOtherRuns() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-final"
        val knownTools = listOf(
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-success",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
                resultSummary = "Done",
            ),
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-failed",
                toolName = "run_command",
                status = ToolActivityStatusUi.Failed,
                argumentsSummary = "{}",
                resultSummary = "Execution failed",
            ),
        )
        val otherRunMessages = listOf(
            AgentMessageUi(id = "assistant-run-other-1-0", content = "Still outputting", isStreaming = true),
            ThinkingMessageUi(id = "run-other-thinking-1-0", content = "Still thinking", isStreaming = true),
            ToolActivityMessageUi(
                id = "run-other-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Running,
                argumentsSummary = "{}",
            ),
        )
        val unfinishedTool = ToolActivityMessageUi(
            id = "$runId-tool-1-call-unknown",
            toolName = "run_command",
            status = ToolActivityStatusUi.Running,
            argumentsSummary = "{}",
        )
        val messages = otherRunMessages + knownTools + listOf(
            AgentMessageUi(id = "assistant-$runId", content = "", isStreaming = true),
            AgentMessageUi(id = "assistant-$runId-1-0", content = "Answer\n\n", isStreaming = true),
            ThinkingMessageUi(id = "$runId-thinking-1-0", content = "Thinking", isStreaming = true),
            unfinishedTool,
        )

        val finalized = projector.finalizeRun(runId, messages)

        assertEquals(otherRunMessages + knownTools, finalized.take(otherRunMessages.size + knownTools.size))
        val assistantMessages = finalized.filterIsInstance<AgentMessageUi>()
            .filter { it.id.startsWith("assistant-$runId") }
        assertTrue(assistantMessages.all { !it.isStreaming && it.renderMarkdown })
        assertEquals("Answer", assistantMessages.last().content)
        val thinking = finalized.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith(runId) }
        assertFalse(thinking.isStreaming)
        assertTrue(thinking.collapsed)
        assertEquals(unfinishedTool.copy(status = ToolActivityStatusUi.Unknown), finalized.last())
    }

    @Test
    fun replayResetRemovesOnlyRebuildableTraceAndExplicitlyReplayedSupplements() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-replay"
        val user = UserMessageUi(
            id = "user-$runId",
            content = "Analyze screenshot",
            images = listOf("image-preview"),
        )
        val legacySupplement = UserMessageUi(id = "user-$runId-supplement-1", content = "Original addendum")
        val replayedSupplement = UserMessageUi(id = "user-$runId-supplement-2", content = "Continue checking")
        val otherRunMessages = listOf(
            UserMessageUi(id = "user-other-run", content = "Previous question"),
            AgentMessageUi(id = "assistant-other-run-1-0", content = "Previous answer"),
            ThinkingMessageUi(id = "other-run-thinking-1-0", content = "Previous thinking", isStreaming = false),
            ToolActivityMessageUi(
                id = "other-run-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            UserMessageUi(id = "user-other-run-supplement-2", content = "Previous addendum"),
        )
        val messages = otherRunMessages + listOf(
            user,
            AgentMessageUi(id = "assistant-$runId", content = "", isStreaming = true),
            AgentMessageUi(id = "assistant-$runId-1-0", content = "Incomplete answer", isStreaming = true),
            ThinkingMessageUi(id = "$runId-thinking-1-fallback", content = "Incomplete thinking", isStreaming = true),
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Unknown,
                argumentsSummary = "{}",
            ),
            legacySupplement,
            replayedSupplement,
            SystemNoticeMessageUi(id = "assistant-$runId-2", code = SystemNoticeCode.RuntimeFailed),
            SystemNoticeMessageUi(id = "interrupted-$runId", code = SystemNoticeCode.Interrupted),
        )

        val reset = projector.resetForReplay(runId, messages, replaySupplementIndexes = setOf(2))

        assertEquals(otherRunMessages + listOf(user, legacySupplement), reset)
        assertEquals(reset, projector.resetForReplay(runId, reset, replaySupplementIndexes = setOf(2)))
    }

    @Test
    fun repeatedReplayRebuildsTextReasoningAndToolsWithoutAccumulatingContent() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-repeat"
        val user = UserMessageUi(id = "user-$runId", content = "Look at screen")
        val toolStart = AgentEvent.ToolStarted(
            round = 1,
            toolCallId = "call-1",
            name = "observe_screen",
            argsPreview = "{}",
        )
        val toolEnd = AgentEvent.ToolFinished(
            round = 1,
            toolCallId = "call-1",
            name = "observe_screen",
            resultSummary = "Done",
            imageCount = 0,
            imageBytes = 0,
            success = true,
        )
        var messages: List<AgentChatMessageUi> = listOf(user)

        repeat(3) {
            messages = projector.resetForReplay(runId, messages)
            messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "Check first", messages)
            messages = projector.finalizeThinking(runId, messages)
            messages = projector.startTool(runId, toolStart, messages)
            messages = projector.finishTool(runId, toolEnd, messages)
            messages = projector.appendTextDelta(runId, round = 2, index = 0, delta = "Check", messages)
            messages = projector.appendTextDelta(runId, round = 2, index = 0, delta = "Done", messages)
            messages = projector.finalizeText(runId, messages)

            assertEquals(4, messages.size)
            assertEquals(user, messages.first())
            assertEquals("Check first", messages.filterIsInstance<ThinkingMessageUi>().single().content)
            assertEquals("CheckDone", messages.filterIsInstance<AgentMessageUi>().single().content)
            assertEquals(ToolActivityStatusUi.Success, messages.filterIsInstance<ToolActivityMessageUi>().single().status)
        }
    }

    @Test
    fun replayResetClearsOnlyThatRunsThinkingClockAndKeepsUnreplayedUserInputs() {
        var now = 1_000L
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { now })
        val supplement = UserMessageUi(id = "user-run-reset-supplement-1", content = "Keep this addendum")
        var messages: List<AgentChatMessageUi> = listOf(supplement)
        messages = projector.appendReasoningDelta("run-reset", round = 1, index = 0, delta = "Previous thinking", messages)
        messages = projector.appendReasoningDelta("run-other", round = 1, index = 0, delta = "Other thoughts", messages)
        now = 9_000L

        messages = projector.resetForReplay("run-reset", messages)
        messages = projector.appendReasoningDelta("run-reset", round = 1, index = 0, delta = "Resume thinking", messages)
        now = 11_000L
        messages = projector.finalizeThinking("run-reset", messages)
        messages = projector.finalizeThinking("run-other", messages)

        assertTrue(messages.contains(supplement))
        assertEquals(2, messages.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith("run-reset-") }.elapsedSeconds)
        assertEquals(10, messages.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith("run-other-") }.elapsedSeconds)
    }

    @Test
    fun projectsReasoningAndToolsByRoundAndToolCallId() {
        var now = 1_000L
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { now })
        val runId = "run-1"
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "user-$runId", content = "Look at screen"))

        messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "Observe first", messages)
        now = 4_000L
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_observe_1",
                name = "observe_screen",
                argsPreview = "{}",
            ),
            projector.finalizeThinkingRound(runId, round = 1, messages)
        )
        messages = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_observe_1",
                name = "observe_screen",
                resultSummary = "ok=true, chars=10",
                imageCount = 1,
                imageBytes = 200,
            ),
            messages
        )

        now = 5_000L
        messages = projector.appendReasoningDelta(runId, round = 2, index = 0, delta = "Confirm again", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "call_observe_2",
                name = "run_command",
                argsPreview = "Run command · Android · root",
                command = "pm list packages | head",
            ),
            projector.finalizeThinkingRound(runId, round = 2, messages)
        )

        assertEquals(
            listOf(
                "user-$runId",
                "$runId-thinking-1-0",
                "$runId-tool-1-call_observe_1",
                "$runId-thinking-2-0",
                "$runId-tool-2-call_observe_2",
            ),
            messages.map { it.id }
        )

        val firstThinking = messages[1] as ThinkingMessageUi
        assertFalse(firstThinking.isStreaming)
        assertEquals(3, firstThinking.elapsedSeconds)

        val firstTool = messages[2] as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Success, firstTool.status)
        assertEquals(1, firstTool.imageCount)

        val secondTool = messages[4] as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Running, secondTool.status)
        assertEquals("Run command · Android · root", secondTool.argumentsSummary)
        assertEquals("pm list packages | head", secondTool.command)
    }

    @Test
    fun keepsAssistantTextSeparatedByRound() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-text"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "Analyze"),
            AgentMessageUi(id = "assistant-$runId-1", content = "First round", isStreaming = false),
        )

        messages = projector.appendReasoningDelta(runId, round = 2, index = 0, delta = "Continue reasoning", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "call_2",
                name = "observe_screen",
                argsPreview = "{}",
            ),
            projector.finalizeThinkingRound(runId, round = 2, messages)
        )
        messages = projector.appendTextDelta(runId, round = 2, index = 1, delta = "Second round", messages)
        messages = projector.appendTextDelta(runId, round = 2, index = 1, delta = "Answer", messages)

        assertEquals(
            listOf(
                "user-$runId",
                "assistant-$runId-1",
                "$runId-thinking-2-0",
                "$runId-tool-2-call_2",
                "assistant-$runId-2-1",
            ),
            messages.map { it.id }
        )
        val roundTwoAssistant = messages.last() as AgentMessageUi
        assertEquals("Second roundAnswer", roundTwoAssistant.content)
        assertTrue(roundTwoAssistant.isStreaming)
    }

    @Test
    fun keepsFallbackToolCallIdsDistinctAcrossRounds() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-fallback"
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "user-$runId", content = "Operate phone"))

        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "tool_call_0",
                name = "search_apps",
                argsPreview = """{"query":"camera"}""",
            ),
            messages
        )
        messages = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "tool_call_0",
                name = "search_apps",
                resultSummary = "ok=true",
                imageCount = 0,
                imageBytes = 0,
            ),
            messages
        )
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "tool_call_0",
                name = "observe_screen",
                argsPreview = """{"include_screenshot":true}""",
            ),
            messages
        )

        val tools = messages.filterIsInstance<ToolActivityMessageUi>()
        assertEquals(2, tools.size)
        assertEquals("search_apps", tools[0].toolName)
        assertEquals(ToolActivityStatusUi.Success, tools[0].status)
        assertEquals("observe_screen", tools[1].toolName)
        assertEquals(ToolActivityStatusUi.Running, tools[1].status)
    }

    @Test
    fun toolActivityFollowsAssistantTextStreamedInSameRound() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-order"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "Search")
        )

        messages = projector.appendTextDelta(runId, round = 1, index = 0, delta = "Find the app first", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_1",
                name = "search_apps",
                argsPreview = "{}",
            ),
            messages
        )

        assertEquals(
            listOf(
                "user-$runId",
                "assistant-$runId-1-0",
                "$runId-tool-1-call_1",
            ),
            messages.map { it.id }
        )
    }

    @Test
    fun finalizingTextTrimsTrailingWhitespace() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-trim"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "Hello")
        )

        messages = projector.appendTextDelta(runId, round = 1, index = 0, delta = "Answer.\n\n", messages)
        messages = projector.finalizeTextRound(runId, round = 1, messages)

        val assistant = messages.last() as AgentMessageUi
        assertEquals("Answer.", assistant.content)
        assertFalse(assistant.isStreaming)
    }

    @Test
    fun interruptedToolBecomesUnknownInsteadOfFailed() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-interrupted"
        val running = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                argsPreview = "Run command",
            ),
            listOf(UserMessageUi(id = "user-$runId", content = "Restart device")),
        )

        val interrupted = projector.interruptRunningTools("Task interrupted", running)
        val tool = interrupted.filterIsInstance<ToolActivityMessageUi>().single()

        assertEquals(ToolActivityStatusUi.Unknown, tool.status)
        assertEquals("Task interrupted", tool.resultSummary)
    }

    @Test
    fun keepsInterleavedReasoningTextAndHostedToolInEventOrder() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-interleaved"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "Search for the latest news"),
        )

        messages = projector.appendReasoningDelta(
            runId,
            round = 1,
            index = 0,
            delta = "First determine if a search is needed",
            messages,
        )
        messages = projector.appendTextDelta(
            runId,
            round = 1,
            index = 1,
            delta = "Let me check first.",
            messages,
        )
        messages = projector.startHostedTool(
            runId,
            AgentEvent.HostedToolStarted(round = 1, toolCallId = "ws_1", name = "Web search"),
            projector.finalizeTextRound(runId, round = 1, messages),
        )
        messages = projector.finishHostedTool(
            runId,
            AgentEvent.HostedToolFinished(
                round = 1,
                toolCallId = "ws_1",
                name = "Web search",
                success = true,
            ),
            messages,
        )
        messages = projector.appendReasoningDelta(
            runId,
            round = 1,
            index = 2,
            delta = "Organize search results",
            messages,
        )
        messages = projector.appendTextDelta(
            runId,
            round = 1,
            index = 3,
            delta = "This is the final answer.",
            messages,
        )

        assertEquals(
            listOf(
                "user-$runId",
                "$runId-thinking-1-0",
                "assistant-$runId-1-1",
                "$runId-tool-1-ws_1",
                "$runId-thinking-1-2",
                "assistant-$runId-1-3",
            ),
            messages.map { it.id },
        )
        assertFalse((messages[1] as ThinkingMessageUi).isStreaming)
        assertFalse((messages[2] as AgentMessageUi).isStreaming)
        assertEquals(ToolActivityStatusUi.Success, (messages[3] as ToolActivityMessageUi).status)
        assertFalse((messages[4] as ThinkingMessageUi).isStreaming)
        assertTrue((messages[5] as AgentMessageUi).isStreaming)
    }

    @Test
    fun ignoresReasoningAndTextAfterFinalizeRun() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-sealed"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "Write a story"),
        )
        messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "Outline the plot", messages)
        messages = projector.appendTextDelta(runId, round = 1, index = 1, delta = "Once upon a time, there was a mountain.", messages)
        messages = projector.finalizeRun(runId, messages)

        val finalized = messages
        messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "Need to think some more.", messages)
        messages = projector.appendTextDelta(runId, round = 1, index = 1, delta = "Continue writing", messages)
        messages = projector.startAssistantBlock(
            runId,
            AgentEvent.AssistantBlockStart(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
            ),
            messages,
        )
        messages = projector.ensureCompletedThinking(
            runId = runId,
            round = 2,
            content = "Reflection after finishing",
            messages = messages,
        )

        assertEquals(finalized, messages)
        val thinking = messages.filterIsInstance<ThinkingMessageUi>().single()
        assertFalse(thinking.isStreaming)
        assertTrue(thinking.collapsed)
        assertEquals("Outline the plot", thinking.content)
        val assistant = messages.filterIsInstance<AgentMessageUi>().single()
        assertEquals("Once upon a time, there was a mountain.", assistant.content)
        assertFalse(assistant.isStreaming)
    }

    @Test
    fun replayAfterFinalizeAllowsReasoningAgain() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-replay-seal"
        var messages: List<AgentChatMessageUi> = projector.appendReasoningDelta(
            runId,
            round = 1,
            index = 0,
            delta = "Think first",
            messages = emptyList(),
        )
        messages = projector.finalizeRun(runId, messages)
        messages = projector.resetForReplay(runId, messages)
        messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "Replay reasoning", messages)
        val thinking = messages.filterIsInstance<ThinkingMessageUi>().single()
        assertEquals("Replay reasoning", thinking.content)
        assertTrue(thinking.isStreaming)
        assertFalse(thinking.collapsed)
    }

    @Test
    fun ignoresDuplicateReasoningAfterAnswer() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-dup-think"
        var messages: List<AgentChatMessageUi> = projector.appendReasoningDelta(
            runId, round = 1, index = 0, delta = "Think through the structure first", messages = emptyList(),
        )
        messages = projector.finalizeThinkingRound(runId, 1, messages)
        messages = projector.appendTextDelta(runId, round = 1, index = 1, delta = "The watchmaker's clock", messages)
        val afterAnswer = messages
        messages = projector.startAssistantBlock(
            runId,
            AgentEvent.AssistantBlockStart(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 2,
            ),
            messages,
        )
        messages = projector.appendReasoningDelta(
            runId, round = 1, index = 2, delta = "Think through the structure first", messages,
        )
        assertEquals(afterAnswer, messages)
        assertEquals(1, messages.filterIsInstance<ThinkingMessageUi>().size)
    }

    @Test fun completedFallbackDoesNotReplaceVisibleStreamingAnswer() {
        assertEquals("Displayed answer", mergeCompletedAssistantContent("Displayed answer", "A completely different final state", 1))
        assertEquals("Displayed answer.", mergeCompletedAssistantContent("Displayed answer", "Displayed answer.", 1))
        assertEquals("Displayed answer", mergeCompletedAssistantContent("Displayed answer\n", "Displayed answer", 1))
        assertEquals("Short", mergeCompletedAssistantContent("Short", "", 1))
        assertEquals("Final state", mergeCompletedAssistantContent("", "Final state", 1))
        assertEquals("First paragraph", mergeCompletedAssistantContent("First paragraph", "Another paragraph", 2))
    }
}
