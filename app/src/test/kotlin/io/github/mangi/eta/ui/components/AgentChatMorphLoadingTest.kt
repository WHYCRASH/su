package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatMorphLoadingTest {
    @Test fun retryWaitsForItsOwnFirstOutputNotTheFailedTurnsOutput() {
        val messages = listOf(
            UserMessageUi("user-old", "task"),
            AgentMessageUi("assistant-old", "partial output"),
            io.github.mangi.eta.ui.model.SystemNoticeMessageUi("failed",
                io.github.mangi.eta.ui.model.SystemNoticeCode.RuntimeFailed),
        )
        assertTrue(isWaitingForFirstModelOutput(messages))
    }


    @Test
    fun waitingAfterSendBeforeAnyOutput() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Hello"),
            AgentMessageUi(id = "agent-1", content = "", isStreaming = true),
        )
        assertTrue(isWaitingForFirstModelOutput(messages))
        assertTrue(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
    }

    @Test
    fun hidesOnceThinkingStarts() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Hello"),
            ThinkingMessageUi(id = "think-1", content = "Let me think first", isStreaming = true),
        )
        assertFalse(isWaitingForFirstModelOutput(messages))
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
    }

    @Test
    fun hidesOnceAssistantTextStarts() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Hello"),
            AgentMessageUi(id = "agent-1", content = "OK", isStreaming = true),
        )
        assertFalse(isWaitingForFirstModelOutput(messages))
    }

    @Test
    fun hidesOnceToolStarts() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Look this up"),
            ToolActivityMessageUi(
                id = "tool-1",
                toolName = "search",
                status = ToolActivityStatusUi.Running,
                argumentsSummary = "q=eta",
            ),
        )
        assertFalse(isWaitingForFirstModelOutput(messages))
    }

    @Test
    fun blankPlaceholderDoesNotCountAsOutput() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Hello"),
        )
        assertTrue(isWaitingForFirstModelOutput(messages))
    }

    @Test
    fun entireGenerationModeKeepsShowingAfterOutput() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Hello"),
            AgentMessageUi(id = "agent-1", content = "OK", isStreaming = true),
        )
        assertTrue(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                enabled = true,
                beforeResponseOnly = false,
            ),
        )
    }

    @Test
    fun disabledOrPausedOrCompressingHidesIndicator() {
        val messages = listOf(UserMessageUi(id = "user-1", content = "Hello"))
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                enabled = false,
                beforeResponseOnly = true,
            ),
        )
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                isPaused = true,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                isCompressingContext = true,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = false,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
    }

    @Test
    fun steerSupplementDoesNotCountAsNewWait() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Hello"),
            AgentMessageUi(id = "agent-1", content = "Stopping here for now", isStreaming = true),
            UserMessageUi(id = "user-1-supplement-0", content = "Continue"),
        )
        assertFalse(isWaitingForFirstModelOutput(messages))
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
    }
}
