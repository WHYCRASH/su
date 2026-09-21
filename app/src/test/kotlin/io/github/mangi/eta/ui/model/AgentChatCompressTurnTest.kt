package io.github.mangi.eta.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatCompressTurnTest {
    @Test
    fun previousAssistantDoesNotCountAsCurrentTurnOutput() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "Previous round"),
                AgentMessageUi("a1", "Old reply"),
                UserMessageUi("u2", "New question"),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        assertFalse(state.hasStartedCurrentTurnOutput())
        assertFalse(state.hasRunningTools())
    }

    @Test
    fun currentTurnThinkingCounts() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "Previous round"),
                AgentMessageUi("a1", "Old reply"),
                UserMessageUi("u2", "New question"),
                ThinkingMessageUi("t2", "Thinking", isStreaming = true),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = true,
        )
        assertTrue(state.hasStartedCurrentTurnOutput())
    }

    @Test
    fun currentTurnRunningToolCountsAsRunningAndStarted() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "New question"),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Running,
                    argumentsSummary = "ls",
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        assertTrue(state.hasRunningTools())
        assertTrue(state.hasStartedCurrentTurnOutput())
    }

    @Test
    fun finishedToolsDoNotCountAsRunning() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "New question"),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "ls",
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        assertFalse(state.hasRunningTools())
        assertTrue(state.hasStartedCurrentTurnOutput())
    }

    @Test
    fun previousTurnToolsDoNotCountAsCurrentTurnTools() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "Previous round"),
                ToolActivityMessageUi(
                    id = "tool-old",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "ls",
                ),
                UserMessageUi("u2", "New question"),
                ThinkingMessageUi("t2", "Thinking", isStreaming = true),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = true,
        )
        assertFalse(state.hasCurrentTurnTools())
        assertTrue(state.hasStartedCurrentTurnOutput())
    }

    @Test
    fun currentTurnFinishedToolsCount() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "New question"),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "ls",
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        assertTrue(state.hasCurrentTurnTools())
    }

    @Test
    fun partialAssistantAfterLastUserShouldContinue() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "Write a 5000-word story"),
                AgentMessageUi("a1", "Story halfway written"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        assertTrue(state.hasPartialAssistantAfterLastUser())
    }

    @Test
    fun steerAfterPartialShouldNotCountAsPartialToContinue() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "Write a 5000-word story"),
                AgentMessageUi("a1", "Story halfway written"),
                UserMessageUi("u2", "Any more?"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        assertFalse(state.hasPartialAssistantAfterLastUser())
    }

    @Test
    fun appendSupplementStaysInSameTurnForKeepAndResume() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "Write a 5000-word story"),
                AgentMessageUi("a1", "Story halfway written"),
                UserMessageUi("user-run-1-supplement-0", "Any more?"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        assertTrue(state.hasPartialAssistantAfterLastUser())
        assertTrue(state.hasStartedCurrentTurnOutput())
        assertFalse(state.hasCurrentTurnTools())
    }

    @Test
    fun userOnlyTurnHasNoPartialAssistant() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "Write a 5000-word story"),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        assertFalse(state.hasPartialAssistantAfterLastUser())
    }
}
