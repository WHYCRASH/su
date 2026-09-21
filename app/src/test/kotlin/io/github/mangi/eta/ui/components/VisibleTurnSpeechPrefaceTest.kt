package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VisibleTurnSpeechPrefaceTest {
    @Test fun bulkPrefacesMatchIndividualScansWithLinearHistoryVisits() {
        val history = buildList<AgentChatMessageUi> {
            repeat(1000) { turn ->
                add(UserMessageUi(id = "u$turn", content = "question"))
                add(AgentMessageUi(id = "mid$turn", content = "  preface $turn  "))
                add(UserMessageUi(id = "u-supplement-$turn", content = "more"))
                add(AgentMessageUi(id = "final$turn", content = "answer"))
            }
        }
        var visits = 0
        val counted = object : AbstractList<AgentChatMessageUi>() {
            override val size get() = history.size
            override fun get(index: Int): AgentChatMessageUi { visits++; return history[index] }
        }
        val ids = (0 until 1000).mapTo(linkedSetOf()) { "final$it" }
        val actual = visibleTurnSpeechPrefaces(counted, ids)
        assertEquals(history.size, visits)
        visits = 0
        val oldResult = ids.associateWith { visibleTurnSpeechPreface(counted, it) }
        assertEquals(oldResult, actual)
        assertEquals(2_002_000, visits) // old per-answer rescans versus 4,000 above
        println("preface history visits: old=$visits new=${history.size}; equal output for 1,000 turns")
        assertEquals("preface 999", actual["final999"])
    }

    @Test fun readsVisibleAssistantTextAndSkipsThinking() {
        val messages = listOf(
            UserMessageUi(id = "u", content = "What is the third item"),
            ThinkingMessageUi(
                id = "t",
                content = "Internal reasoning: double-check Voice Mode before answering; this part should not be read aloud.",
                isStreaming = false,
            ),
            AgentMessageUi(
                id = "mid",
                content = "First explain RikkaHub's Voice Mode flow: it is voice-in, voice-out conversation, not tap-to-read-aloud.",
            ),
            ToolActivityMessageUi(
                id = "tool",
                toolName = "web_search",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "search",
            ),
            AgentMessageUi(id = "final", content = "The third item is Voice Mode: voice conversation, not read-aloud."),
        )
        val preface = visibleTurnSpeechPreface(messages, "final")
        assertEquals(
            "First explain RikkaHub's Voice Mode flow: it is voice-in, voice-out conversation, not tap-to-read-aloud.",
            preface,
        )
        assertFalse(preface.contains("Internal reasoning"))
    }
    @Test fun doesNotLeakVisibleTextFromThePreviousTurn() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "First question"),
            AgentMessageUi(id = "a1", content = "First-round visible text"),
            UserMessageUi(id = "u2", content = "Second question"),
            AgentMessageUi(id = "mid2", content = "Second-round leading text"),
            AgentMessageUi(id = "final2", content = "Second-round final text"),
        )

        assertEquals("Second-round leading text", visibleTurnSpeechPreface(messages, "final2"))
    }

    @Test fun keepsVisibleTextAcrossSteeringSupplement() {
        val messages = listOf(
            UserMessageUi(id = "u", content = "Start task"),
            AgentMessageUi(id = "mid1", content = "First visible section"),
            UserMessageUi(id = "u-supplement-1", content = "Additional request"),
            AgentMessageUi(id = "mid2", content = "Second visible section"),
            AgentMessageUi(id = "final", content = "Final text"),
        )

        assertEquals(
            "First visible section\n\nSecond visible section",
            visibleTurnSpeechPreface(messages, "final"),
        )
    }

}
