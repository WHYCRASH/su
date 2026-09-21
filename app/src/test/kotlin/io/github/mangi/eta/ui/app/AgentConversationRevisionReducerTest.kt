package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRevisionReducerTest {
    @Test fun missingHistoryWithoutSummaryBlocksAllDestructiveRevisionPaths() {
        val state = conversationState().copy(history = conversationState().history.drop(4))
        val before = state.copy()
        assertNull(AgentConversationRevisionReducer.boundary(state, "user-1"))
        assertNull(AgentConversationRevisionReducer.deleteFromTurn(state, "user-1"))
        assertNull(AgentConversationRevisionReducer.branchPrefix(state, "assistant-1"))
        assertEquals(before, state)
    }

    @Test fun attachmentEnvelopeDifferenceMatchesWithinTheSameTurn() {
        val envelope = "# Files mentioned by the user:\n\n## photo.jpg: /cache/photo.jpg\n\n## My request:\nWhat is in the picture"
        val prefix = listOf(AgentModelClient.ConversationMessage("user", "earlier", turnId = "run-old"),
            AgentModelClient.ConversationMessage("assistant", "previous answer", turnId = "run-old"))
        val state = conversationState().copy(
            messages = listOf(UserMessageUi("user-run-old", "earlier"),
                UserMessageUi("user-run-image", envelope, images = listOf("preview"))),
            history = prefix + AgentModelClient.ConversationMessage("user", contentJson =
                """[{"type":"text","text":"What is in the picture"},{"type":"image_file","path":"/cache/photo.jpg"}]""",
                turnId = "run-image"),
        )
        val boundary = AgentConversationRevisionReducer.boundary(state, "user-run-image")!!
        assertFalse(boundary.contextWasCompacted)
        assertEquals(prefix, boundary.historyPrefix)
    }

    @Test fun attachmentNormalizationCannotMatchAnotherTurnWithTheSameQuestion() {
        val envelope = "# Files mentioned by the user:\n\n## photo.jpg: /cache/photo.jpg\n\n## My request:\nWhat is in the picture"
        val state = conversationState().copy(
            messages = listOf(UserMessageUi("user-run-image", envelope, images = listOf("preview"))),
            history = listOf(AgentModelClient.ConversationMessage("user", "What is in the picture", turnId = "run-other")),
        )
        assertNull(AgentConversationRevisionReducer.boundary(state, "user-run-image"))
    }

    @Test fun missingMessageAfterRealMarkerIsNotAssumedCompacted() {
        val marker = io.github.mangi.eta.ui.model.ContextCompactedMessageUi("marker", 2, "Summary")
        val state = conversationState().copy(messages = listOf(marker, UserMessageUi("user-new", "new")),
            history = listOf(AgentModelClient.ConversationMessage("system", "[Conversation Summary] old")))
        assertNull(AgentConversationRevisionReducer.boundary(state, "user-new"))
    }

    @Test fun realMarkerBeforeRetainedTailAllowsRevisingRemovedMessage() {
        val marker = io.github.mangi.eta.ui.model.ContextCompactedMessageUi("marker", 2, "Summary")
        val state = conversationState().copy(
            messages = listOf(UserMessageUi("user-old", "old"), marker, UserMessageUi("user-new", "new")),
            history = listOf(AgentModelClient.ConversationMessage("system", "[Conversation Summary] old"),
                AgentModelClient.ConversationMessage("user", "new")),
        )
        assertTrue(AgentConversationRevisionReducer.boundary(state, "user-old")!!.contextWasCompacted)
        assertFalse(AgentConversationRevisionReducer.boundary(state, "user-new")!!.contextWasCompacted)
    }

    @Test fun toolPruningMarkerDoesNotProveSummaryCompaction() {
        val marker = io.github.mangi.eta.ui.model.ContextCompactedMessageUi("pruned", 0, "",
            compressorLabel = "Tool output pruning (not a summary)")
        val state = conversationState().copy(messages = listOf(UserMessageUi("user-old", "missing"), marker),
            history = listOf(AgentModelClient.ConversationMessage("user", "unrelated")))
        assertNull(AgentConversationRevisionReducer.boundary(state, "user-old"))
    }

    @Test fun existingTurnWithDifferentPayloadCannotBeCalledCompacted() {
        val marker = io.github.mangi.eta.ui.model.ContextCompactedMessageUi("marker", 2, "Summary")
        val state = conversationState().copy(messages = listOf(UserMessageUi("user-run-task", "changed"), marker),
            history = listOf(AgentModelClient.ConversationMessage("user", "original", turnId = "run-task")))
        assertNull(AgentConversationRevisionReducer.boundary(state, "user-run-task"))
    }

    @Test fun genericSystemTextDoesNotProveCompaction() {
        val state = conversationState().copy(history = listOf(
            AgentModelClient.ConversationMessage("system", "Compressed"),
            AgentModelClient.ConversationMessage("user", "Second question")))
        assertNull(AgentConversationRevisionReducer.boundary(state, "user-1"))
    }

    @Test fun missingSupplementCannotEraseItsOriginalTurn() {
        val state = conversationState().copy(
            messages = listOf(
                UserMessageUi("user-run-old", "earlier"),
                UserMessageUi("user-run-task", "mention task"),
                UserMessageUi("user-run-task-supplement-1", "will context grow?"),
                UserMessageUi("user-run-task-supplement-2", "review changes"),
            ),
            history = listOf(
                AgentModelClient.ConversationMessage("user", "earlier", turnId = "run-old"),
                AgentModelClient.ConversationMessage("user", "mention task", turnId = "run-task"),
            ),
        )
        assertNull(AgentConversationRevisionReducer.boundary(state, "user-run-task-supplement-2"))
        assertNull(AgentConversationRevisionReducer.branchPrefix(state, "user-run-task-supplement-2"))
        val boundary = AgentConversationRevisionReducer.boundary(state, "user-run-task")!!
        assertEquals(listOf("earlier"), boundary.historyPrefix.map { it.content })
        assertEquals(0, boundary.laterTurnCount)
    }

    @Test fun retainedSupplementKeepsOriginalQuestionAndCompletedTools() {
        val prior = listOf(
            AgentModelClient.ConversationMessage("user", "task", turnId = "run-task"),
            AgentModelClient.ConversationMessage("assistant", "work", turnId = "run-task"),
            AgentModelClient.ConversationMessage("tool", "actual result", turnId = "run-task"),
        )
        val state = conversationState().copy(
            messages = listOf(UserMessageUi("user-run-task", "task"),
                UserMessageUi("user-run-task-supplement-1", "review")),
            history = prior + AgentModelClient.ConversationMessage("user",
                io.github.mangi.eta.agent.model.AgentContextCompactor.steeringUserContent("review"), turnId = "run-task"),
        )
        assertEquals(prior, AgentConversationRevisionReducer.boundary(state, "user-run-task-supplement-1")!!.historyPrefix)
    }

    @Test fun oldAssistantIsNotReappendedAfterASupplement() {
        val history = listOf(AgentModelClient.ConversationMessage("user", "task", turnId = "run-task"),
            AgentModelClient.ConversationMessage("assistant", "old text", turnId = "run-task"),
            AgentModelClient.ConversationMessage("user", "supplement", turnId = "run-task"))
        val ui = listOf(UserMessageUi("user-run-task", "task"),
            AgentMessageUi("assistant-run-task-1", "old text"),
            UserMessageUi("user-run-task-supplement-1", "supplement"))
        assertEquals(history, AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(history, ui))
    }

    @Test fun stoppingDoesNotReplaceEarlierDistinctAssistantOrToolCall() {
        val original = AgentModelClient.ConversationMessage("assistant", "earlier answer", turnId = "turn")
        val partial = AgentMessageUi(id = "assistant-run-1-0", content = "new partial")
        val history = listOf(AgentModelClient.ConversationMessage("user", "task", turnId = "turn"), original)
        val updated = AgentConversationRevisionReducer.historyWithTrailingPartial(history, partial)
        assertEquals(listOf("task", "earlier answer", "new partial"), updated.map { it.content })
        val tool = original.copy(toolCallsJson = "[{\"id\":\"call\"}]")
        val withTool = AgentConversationRevisionReducer.historyWithTrailingPartial(listOf(history.first(), tool),
            partial.copy(content = "earlier answer continued"))
        assertEquals(tool, withTool[1])
        assertEquals(3, withTool.size)
    }

    @Test fun stoppedPartialRetainsOriginalUserTurnAcrossSupplements() {
        val turn = "same-user-turn"
        val history = listOf(
            AgentModelClient.ConversationMessage("user", "task", turnId = turn),
            AgentModelClient.ConversationMessage("user", "Additional user instruction: more", turnId = turn),
        )
        val updated = AgentConversationRevisionReducer.historyWithTrailingPartial(history,
            AgentMessageUi(id = "assistant-run-1-0", content = "partial"))
        assertEquals(listOf(turn, turn, turn), updated.map { it.turnId })
        assertEquals(0, io.github.mangi.eta.agent.model.AgentContextCompactor.recentKeepStartIndex(updated, 1))
    }

    @Test
    fun boundaryMapsAssistantToItsUserTurnAndKeepsToolTranscriptPrefix() {
        val state = conversationState()

        val boundary = AgentConversationRevisionReducer.boundary(state, "assistant-2")!!

        assertEquals("user-2", boundary.userMessage.id)
        assertEquals(4, boundary.userMessageIndex)
        assertEquals(1, boundary.laterTurnCount)
        assertFalse(boundary.contextWasCompacted)
        assertEquals(
            listOf("user", "assistant", "tool", "assistant"),
            boundary.historyPrefix.map { it.role },
        )
    }

    @Test
    fun deleteFromMiddleTurnTruncatesMessagesAndHistoryTogether() {
        val state = conversationState().copy(appliedRuntimeRunIds = listOf("run-1", "run-2"))

        val revised = AgentConversationRevisionReducer.deleteFromTurn(state, "assistant-2")!!

        assertEquals(
            listOf("user-1", "thinking-1", "tool-1", "assistant-1"),
            revised.messages.map { it.id },
        )
        assertEquals(listOf("user", "assistant", "tool", "assistant"), revised.history.map { it.role })
        assertEquals(listOf("run-1", "run-2"), revised.appliedRuntimeRunIds)
    }

    @Test
    fun compactedCheckpointAlignsRetainedTurnsFromTheTail() {
        val full = conversationState()
        val compacted = full.copy(
            history = listOf(
                AgentModelClient.ConversationMessage(role = "system", content = "[Conversation summary]\nFirst round archived"),
                AgentModelClient.ConversationMessage(role = "user", content = "Second question"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "Second answer"),
                AgentModelClient.ConversationMessage(role = "user", content = "Third question"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "Third answer"),
            )
        )

        val missing = AgentConversationRevisionReducer.boundary(compacted, "user-1")!!
        val retained = AgentConversationRevisionReducer.boundary(compacted, "user-2")!!

        assertTrue(missing.contextWasCompacted)
        assertTrue(missing.historyPrefix.isEmpty())
        assertFalse(retained.contextWasCompacted)
        assertEquals(listOf("system"), retained.historyPrefix.map { it.role })
    }

    @Test
    fun visibleMessagesStopAtEditedUserWithoutMutatingTheSource() {
        val messages = conversationState().messages

        val visible = AgentConversationRevisionReducer.visibleMessagesForEdit(messages, "user-2")

        assertEquals(listOf("user-1", "thinking-1", "tool-1", "assistant-1", "user-2"), visible.map { it.id })
        assertEquals(8, messages.size)
    }


    @Test
    fun outboundHistoryUsesPrefixWhileEditing() {
        val state = conversationState().copy(
            messageEdit = MessageEditUiState(
                targetMessageId = "user-2",
                previousInput = "",
                previousImages = emptyList(),
                previousFileReferences = emptyList(),
                hasLaterTurns = true,
            ),
        )

        val editing = AgentConversationRevisionReducer.outboundHistory(state)
        val idle = AgentConversationRevisionReducer.outboundHistory(state.copy(messageEdit = null))

        assertEquals(listOf("user", "assistant", "tool", "assistant"), editing.map { it.role })
        assertEquals(state.history, idle)
    }

    @Test
    fun invalidMessageDoesNotChangeConversation() {
        val state = conversationState()

        assertNull(AgentConversationRevisionReducer.boundary(state, "missing"))
        assertNull(AgentConversationRevisionReducer.deleteFromTurn(state, "missing"))
        assertEquals(
            state.messages,
            AgentConversationRevisionReducer.visibleMessagesForEdit(state.messages, "missing"),
        )
    }

    private fun conversationState(): AgentChatUiState = AgentChatUiState(
        messages = listOf(
            UserMessageUi(id = "user-1", content = "First question"),
            ThinkingMessageUi(id = "thinking-1", content = "Thinking", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool-1",
                toolName = "test",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            AgentMessageUi(id = "assistant-1", content = "First answer"),
            UserMessageUi(id = "user-2", content = "Second question", images = listOf("data:image/png;base64,AA==")),
            AgentMessageUi(id = "assistant-2", content = "Second answer"),
            UserMessageUi(id = "user-3", content = "Third question"),
            AgentMessageUi(id = "assistant-3", content = "Third answer"),
        ),
        history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "First question"),
            AgentModelClient.ConversationMessage(role = "assistant", toolCallsJson = "[]"),
            AgentModelClient.ConversationMessage(role = "tool", content = "Result"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "First answer"),
            AgentModelClient.ConversationMessage(role = "user", content = "Second question"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "Second answer"),
            AgentModelClient.ConversationMessage(role = "user", content = "Third question"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "Third answer"),
        ),
        input = "Draft",
        isStreaming = false,
        thinkingEnabled = false,
    )

    @Test
    fun branchPrefixKeepsTargetAssistantTurn() {
        val state = conversationState()
        val branched = AgentConversationRevisionReducer.branchPrefix(state, "assistant-2")!!
        assertEquals(
            listOf("user-1", "thinking-1", "tool-1", "assistant-1", "user-2", "assistant-2"),
            branched.messages.map { it.id },
        )
        assertEquals(
            listOf("user", "assistant", "tool", "assistant", "user", "assistant"),
            branched.history.map { it.role },
        )
        assertEquals("Second answer", branched.history.last().content)
    }

    @Test
    fun branchPrefixFromUserKeepsPromptWithoutLaterReplies() {
        val state = conversationState()
        val branched = AgentConversationRevisionReducer.branchPrefix(state, "user-2")!!
        assertEquals(
            listOf("user-1", "thinking-1", "tool-1", "assistant-1", "user-2"),
            branched.messages.map { it.id },
        )
        assertEquals(
            listOf("user", "assistant", "tool", "assistant", "user"),
            branched.history.map { it.role },
        )
        assertEquals("Second question", branched.history.last().content)
    }

    @Test
    fun branchPrefixReconstructsHistoryWhenTurnWasCompactedAway() {
        val full = conversationState()
        val compacted = full.copy(
            history = listOf(
                AgentModelClient.ConversationMessage(role = "system", content = "[Conversation summary]\nFirst round archived"),
                AgentModelClient.ConversationMessage(role = "user", content = "Second question"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "Second answer"),
                AgentModelClient.ConversationMessage(role = "user", content = "Third question"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "Third answer"),
            ),
        )
        val branched = AgentConversationRevisionReducer.branchPrefix(compacted, "assistant-1")!!
        assertEquals(listOf("user-1", "thinking-1", "tool-1", "assistant-1"), branched.messages.map { it.id })
        assertEquals(listOf("user", "assistant"), branched.history.map { it.role })
        assertEquals("First question", branched.history.first().content)
        assertEquals("First answer", branched.history.last().content)
    }

    @Test
    fun abortKeepsVisibleAssistantInOutboundHistory() {
        val history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "Write 2000 words"),
        )
        val messages = listOf(
            UserMessageUi(id = "u1", content = "Write 2000 words"),
            AgentMessageUi(id = "a1", content = "Pausing locks the typewriter in catch-up mode.", isStreaming = false),
        )
        val next = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(history, messages)
        assertEquals("assistant", next.last().role)
        assertEquals("Pausing locks the typewriter in catch-up mode.", next.last().content)
    }
}
