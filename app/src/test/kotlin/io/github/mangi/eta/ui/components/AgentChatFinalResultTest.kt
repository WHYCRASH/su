package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentChatFinalResultTest {
    @Test fun failureStaysClosedWhileRetryStreamsAndAfterItCompletes() {
        val messages = listOf(
            UserMessageUi("user-run-old", "task"),
            AgentMessageUi("assistant-run-old-1", "partial"),
            io.github.mangi.eta.ui.model.SystemNoticeMessageUi("failure",
                io.github.mangi.eta.ui.model.SystemNoticeCode.RuntimeFailed),
            AgentMessageUi("assistant-run-new-1", "new result"),
        )
        assertEquals(setOf("failure"), resolveFinalResultMessageIds(messages, isStreaming = true))
        assertEquals(setOf("failure", "assistant-run-new-1"), resolveFinalResultMessageIds(messages))
    }


    @Test
    fun onlyLastAgentMessageOfEachTurnIsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Look up some reference material"),
            AgentMessageUi(id = "agent-1", content = "I'll search for you."),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-2", content = "The search results show…"),
            toolActivity("tool-2"),
            AgentMessageUi(id = "agent-3", content = "Final answer"),
        )

        assertEquals(setOf("agent-3"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun multipleTurnsEachHaveTheirOwnFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "First question"),
            AgentMessageUi(id = "agent-1", content = "First answer"),
            UserMessageUi(id = "user-2", content = "Second question"),
            AgentMessageUi(id = "agent-2", content = "Intermediate step"),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-3", content = "Second answer"),
        )

        assertEquals(setOf("agent-1", "agent-3"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun turnInterruptedAfterToolKeepsPreviousAgentMessageAsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Task"),
            AgentMessageUi(id = "agent-1", content = "Let me try it first."),
            toolActivity("tool-1"),
        )

        assertEquals(setOf("agent-1"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun turnWithoutAgentMessageProducesNoFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Task"),
            toolActivity("tool-1"),
        )

        assertEquals(emptySet<String>(), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun streamingTurnDoesNotMarkIntermediateMessageAsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Task"),
            AgentMessageUi(id = "agent-1", content = "I'll search for you."),
            toolActivity("tool-1"),
        )

        assertEquals(
            emptySet<String>(),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
    }

    @Test
    fun streamingKeepsFinalResultOfCompletedEarlierTurns() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "First question"),
            AgentMessageUi(id = "agent-1", content = "First answer"),
            UserMessageUi(id = "user-2", content = "Second question"),
            AgentMessageUi(id = "agent-2", content = "Intermediate step"),
            toolActivity("tool-1"),
        )

        assertEquals(
            setOf("agent-1"),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
    }

    @Test
    fun streamingEndRestoresFinalResultOfLastTurn() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Task"),
            AgentMessageUi(id = "agent-1", content = "Intermediate step"),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-2", content = "Final answer"),
        )

        assertEquals(
            emptySet<String>(),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
        assertEquals(
            setOf("agent-2"),
            resolveFinalResultMessageIds(messages, isStreaming = false),
        )
    }

    private fun toolActivity(id: String): AgentChatMessageUi = ToolActivityMessageUi(
        id = id,
        toolName = "browser_use",
        status = ToolActivityStatusUi.Success,
        argumentsSummary = "query",
    )

    @Test
    fun steerSupplementDoesNotCloseTurnWhileContinuing() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Task"),
            AgentMessageUi(id = "agent-1", content = "I'll do this step first."),
            UserMessageUi(id = "user-run-1-supplement-0", content = "Plus this addition"),
            AgentMessageUi(id = "agent-2", content = "Still generating"),
        )
        assertEquals(
            emptySet<String>(),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
        assertEquals(
            setOf("agent-2"),
            resolveFinalResultMessageIds(messages, isStreaming = false),
        )
    }

    @Test
    fun steerSupplementKeepsEarlierTurnFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "First question"),
            AgentMessageUi(id = "agent-1", content = "First answer"),
            UserMessageUi(id = "user-2", content = "Second question"),
            AgentMessageUi(id = "agent-2", content = "Interrupted"),
            UserMessageUi(id = "user-run-2-supplement-0", content = "Follow-up"),
        )
        assertEquals(
            setOf("agent-1"),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
        assertEquals(
            setOf("agent-1", "agent-2"),
            resolveFinalResultMessageIds(messages, isStreaming = false),
        )
    }

    @Test
    fun compressingTurnDoesNotMarkCurrentResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "Write a story"),
            AgentMessageUi(id = "agent-1", content = "Halfway through"),
        )
        assertEquals(
            emptySet<String>(),
            resolveFinalResultMessageIds(messages, isCompressingContext = true),
        )
        assertEquals(
            setOf("agent-1"),
            resolveFinalResultMessageIds(messages, isCompressingContext = false),
        )
    }

    @Test
    fun compressingKeepsFinalResultOfCompletedEarlierTurns() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "First question"),
            AgentMessageUi(id = "agent-1", content = "First answer"),
            UserMessageUi(id = "user-2", content = "Write a story"),
            AgentMessageUi(id = "agent-2", content = "Halfway through"),
        )
        assertEquals(
            setOf("agent-1"),
            resolveFinalResultMessageIds(messages, isCompressingContext = true),
        )
    }
}
