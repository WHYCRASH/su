package io.github.mangi.eta.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectedContinueTest {
    @Test fun oldRetryBeforeTerminalBoundaryDoesNotEnableContinueForANewerStop() {
        val messages = listOf(
            UserMessageUi("user-run-old", "task"),
            SystemNoticeMessageUi("assistant-run-old-retry-1", SystemNoticeCode.ModelRetry),
            SystemNoticeMessageUi("fail", SystemNoticeCode.RuntimeFailed),
            AgentMessageUi("assistant-run-new-1", "retry output"),
            SystemNoticeMessageUi("stop", SystemNoticeCode.Stopped),
        )
        assertFalse(messages.stoppedDuringModelRetry())
        assertFalse(canContinueDisconnectedRun(messages))
    }

    @Test
    fun runtimeFailureNoticeEnablesContinue() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Continue the task from before"),
            AgentMessageUi(id = "assistant-1", content = "Completed 4 steps"),
            SystemNoticeMessageUi(id = "fail-1", code = SystemNoticeCode.RuntimeFailed, detail = "connection closed"),
        )
        assertTrue(canContinueDisconnectedRun(messages))
        assertFalse(canContinuePausedGeneration(messages))
    }

    @Test
    fun laterUserMessageDisablesDisconnectedContinue() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Task"),
            SystemNoticeMessageUi(id = "fail-1", code = SystemNoticeCode.RuntimeFailed, detail = "connection closed"),
            UserMessageUi(id = "user-2", content = "A different question"),
        )
        assertFalse(canContinueDisconnectedRun(messages))
    }

    @Test
    fun emptyResultDoesNotLookLikeDisconnect() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Task"),
            SystemNoticeMessageUi(id = "empty-1", code = SystemNoticeCode.EmptyResult),
        )
        assertFalse(canContinueDisconnectedRun(messages))
    }

    @Test
    fun resumeSupplementDoesNotHideFailureNotice() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Task"),
            AgentMessageUi(id = "assistant-1", content = "Partial body text"),
            SystemNoticeMessageUi(id = "fail-1", code = SystemNoticeCode.RuntimeFailed, detail = "connection closed"),
            UserMessageUi(id = "user-run-2-supplement-resume", content = "Pick up directly where it was interrupted."),
        )
        assertTrue(canContinueDisconnectedRun(messages))
    }

    @Test
    fun failedRoundStaysClosedAfterNotice() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi(id = "user-1", content = "Task"),
                AgentMessageUi(id = "assistant-1", content = "Completed 4 steps"),
                SystemNoticeMessageUi(id = "fail-1", code = SystemNoticeCode.RuntimeFailed, detail = "connection closed"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        assertFalse(state.hasPartialAssistantAfterLastUser())
        assertFalse(state.hasStartedCurrentTurnOutput())
        assertTrue(canContinueDisconnectedRun(state.messages))
    }

    @Test
    fun pauseAndSteerStayOnTheSameOpenTurn() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi(id = "user-1", content = "Task"),
                AgentMessageUi(id = "assistant-1", content = "Halfway done", isStreaming = false),
                UserMessageUi(id = "user-1-supplement-1", content = "One more thing"),
            ),
            input = "",
            isStreaming = true,
            isPaused = true,
            thinkingEnabled = false,
        )
        assertTrue(state.hasPartialAssistantAfterLastUser())
        assertTrue(state.hasStartedCurrentTurnOutput())
        assertFalse(canContinueDisconnectedRun(state.messages))
    }

    @Test
    fun stoppingWhileWaitingForApiRetryEnablesContinue() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Task"),
            SystemNoticeMessageUi(
                id = "assistant-run-1-retry-1",
                code = SystemNoticeCode.ModelRetry,
                detail = "Model request was briefly interrupted; retrying in 2 seconds (1/3)",
            ),
            SystemNoticeMessageUi(id = "fail-1", code = SystemNoticeCode.Stopped),
        )
        assertTrue(messages.stoppedDuringModelRetry())
        assertTrue(canContinueDisconnectedRun(messages))
        assertFalse(AgentChatUiState(
            messages = messages,
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        ).hasPartialAssistantAfterLastUser())
    }

    @Test
    fun ordinaryStopWithoutRetryDoesNotLookLikeDisconnect() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Task"),
            AgentMessageUi(id = "assistant-1", content = "Halfway written"),
            SystemNoticeMessageUi(id = "stop-1", code = SystemNoticeCode.Stopped),
        )
        assertFalse(messages.stoppedDuringModelRetry())
        assertFalse(canContinueDisconnectedRun(messages))
    }
}
