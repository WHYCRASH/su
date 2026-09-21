package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextCompactionUiTest {
    @Test
    fun pendingPruningThenSummaryFailureKeepsLatestBillInsteadOfOldNineteenPercentBaseline() {
        val messages = listOf<AgentChatMessageUi>(
            ContextCompactedMessageUi("old", compactedCount = 10, summary = "old summary",
                baselineTokens = 51_680),
            UserMessageUi("u", "continue"),
        )
        // Before the fix, clearing livePromptTokens made this old 19% baseline visible.
        assertEquals(51_680, latestBilledContextTokens(messages))
        val afterPruning = AgentContextCompactionUi.pendingPruningUsage(238_000, messages)
        assertEquals(238_000, afterPruning)
        // A failed summary has no new baseline; repeated pruning must not discard this bill.
        assertEquals(238_000, AgentContextCompactionUi.pendingPruningUsage(afterPruning, messages))
    }

    @Test
    fun pendingPruningWithoutLiveBillKeepsExistingUsageSource() {
        val messages = listOf<AgentChatMessageUi>(
            ContextCompactedMessageUi("old", compactedCount = 10, summary = "old summary",
                baselineTokens = 51_680),
        )
        assertEquals(51_680, AgentContextCompactionUi.pendingPruningUsage(null, messages))
        assertEquals(null, AgentContextCompactionUi.pendingPruningUsage(null, emptyList()))
    }

    @Test
    fun runtimePruningEventIsRecognizedEvenWhenUiHistoryHasNotCaughtUp() {
        val old = listOf(msg("user", "task"))
        val live = old + msg("assistant", "tool call") + msg("tool", "trimmed")
        val updated = AgentContextCompactionUi.applyMarker(
            messages = listOf(UserMessageUi("u", "task")), originalHistory = old, compressedHistory = live,
            compressorLabel = "Tool-output budget pruning (originals readable)", markerId = "pruned",
        )
        assertEquals(0, updated.filterIsInstance<ContextCompactedMessageUi>().single().compactedCount)
    }

    @Test
    fun pruningOnlyIsNotReportedAsANewConversationSummary() {
        val original = listOf(
            msg("user", "[Conversation summary]\nold checkpoint"),
            msg("user", "task"),
            AgentModelClient.ConversationMessage("tool", "long tool output", toolCallId = "call"),
        )
        val compressed = original.dropLast(1) + original.last().copy(
            content = "head ${AgentContextCompactor.LEGACY_TOOL_PRUNED_PREFIX} original: context-checkpoint:test] tail")
        val updated = AgentContextCompactionUi.applyMarker(
            messages = listOf(UserMessageUi("u", "task")),
            originalHistory = original, compressedHistory = compressed,
            compressorLabel = "summary model", markerId = "pruned",
        )
        val marker = updated.filterIsInstance<ContextCompactedMessageUi>().single()
        assertEquals(0, marker.compactedCount)
        assertEquals("Tool output pruning (not a summary)", marker.compressorLabel)
        assertTrue(marker.summary.isEmpty())
        assertTrue(!marker.summary.contains("old checkpoint"))
    }

    @Test
    fun insertMarkerSitsBeforeKeptUserMessages() {
        val messages = listOf(
            UserMessageUi("u1", "Old question"),
            AgentMessageUi("a1", "Old answer"),
            UserMessageUi("u2", "Continue"),
            AgentMessageUi("a2", "New answer"),
        )
        val marker = ContextCompactedMessageUi("c1", compactedCount = 2, summary = "Old question resolved")
        val updated = AgentContextCompactionUi.insertMarker(messages, marker, keptUserCount = 1)
        assertEquals(listOf("u1", "a1", "c1", "u2", "a2"), updated.map { it.id })
        assertEquals(2, (updated[2] as ContextCompactedMessageUi).compactedCount)
    }

    @Test
    fun insertMarkerReplacesExistingMarkerAtSameBoundary() {
        val messages = listOf(
            UserMessageUi("u1", "Old question"),
            ContextCompactedMessageUi("old", compactedCount = 1, summary = "Old"),
            UserMessageUi("u2", "Continue"),
        )
        val marker = ContextCompactedMessageUi("new", compactedCount = 3, summary = "New summary")
        val updated = AgentContextCompactionUi.insertMarker(messages, marker, keptUserCount = 1)
        assertEquals(listOf("u1", "new", "u2"), updated.map { it.id })
        assertEquals("New summary", (updated[1] as ContextCompactedMessageUi).summary)
    }

    @Test
    fun applyMarkerCountsVisibleMessagesAndKeepsSummaryText() {
        val original = listOf(
            msg("user", "First round"),
            msg("assistant", "First-round answer"),
            msg("tool", "observe_screen ok"),
            msg("user", "Second round"),
            msg("assistant", "Second-round answer"),
        )
        val compressed = listOf(
            msg("system", "${AgentContextCompactor.SUMMARY_PREFIX}\nAlready saw the screen in the first round"),
            msg("user", "Second round"),
            msg("assistant", "Second-round answer"),
        )
        val ui = listOf(
            UserMessageUi("u1", "First round"),
            AgentMessageUi("a1", "First-round answer"),
            UserMessageUi("u2", "Second round"),
            AgentMessageUi("a2", "Second-round answer"),
            UserMessageUi("u3", "Just sent"),
        )
        val updated = AgentContextCompactionUi.applyMarker(
            messages = ui,
            originalHistory = original,
            compressedHistory = compressed,
            extraKeptUserMessages = 1,
            compressorLabel = "Fish · grok-4.6",
            markerId = "c1",
        )
        assertEquals(listOf("u1", "a1", "c1", "u2", "a2", "u3"), updated.map { it.id })
        val marker = updated[2] as ContextCompactedMessageUi
        assertEquals(2, marker.compactedCount)
        assertEquals("Already saw the screen in the first round", marker.summary)
        assertEquals("Fish · grok-4.6", marker.compressorLabel)
        assertTrue("summary should not keep the prefix", !marker.summary.startsWith("["))
    }

    @Test
    fun applyMarkerKeepsCumulativeUsageOnMarkerAndClearsAssistantBills() {
        val original = listOf(
            msg("user", "First round"),
            msg("assistant", "First-round answer"),
            msg("user", "Second round"),
            msg("assistant", "Second-round answer"),
        )
        val compressed = listOf(
            msg("system", "${AgentContextCompactor.SUMMARY_PREFIX}\nAlready saw the screen in the first round"),
            msg("user", "Second round"),
            msg("assistant", "Second-round answer"),
        )
        val ui = listOf(
            UserMessageUi("u1", "First round"),
            AgentMessageUi(
                "a1",
                "First-round answer",
                usage = TokenUsageUi(inputTokens = 100, outputTokens = 20, cachedTokens = 40),
            ),
            UserMessageUi("u2", "Second round"),
            AgentMessageUi(
                "a2",
                "Second-round answer",
                usage = TokenUsageUi(inputTokens = 50, outputTokens = 10, cachedTokens = 10),
            ),
        )
        val updated = AgentContextCompactionUi.applyMarker(
            messages = ui,
            originalHistory = original,
            compressedHistory = compressed,
            markerId = "c1",
        )
        val marker = updated.filterIsInstance<ContextCompactedMessageUi>().single()
        assertEquals(150L, marker.preservedUsage.inputTokens)
        assertEquals(30L, marker.preservedUsage.outputTokens)
        assertEquals(50L, marker.preservedUsage.cachedTokens)
        assertTrue(updated.filterIsInstance<AgentMessageUi>().all { it.usage == null })
        val usage = conversationTokenUsage(updated)
        assertEquals(150L, usage.inputTokens)
        assertEquals(30L, usage.outputTokens)
        assertEquals(50L, usage.cachedTokens)
    }

    @Test
    fun applyMarkerFoldsExistingMarkerUsageWhenCompressingAgain() {
        val original = listOf(
            msg("user", "Second round"),
            msg("assistant", "Second-round answer"),
        )
        val compressed = listOf(
            msg("system", "${AgentContextCompactor.SUMMARY_PREFIX}\nCompress again"),
            msg("user", "Second round"),
            msg("assistant", "Second-round answer"),
        )
        val ui = listOf(
            ContextCompactedMessageUi(
                id = "old",
                compactedCount = 4,
                summary = "Old summary",
                preservedUsage = ConversationTokenUsageUi(
                    inputTokens = 150,
                    outputTokens = 30,
                    cachedTokens = 50,
                ),
            ),
            UserMessageUi("u2", "Second round"),
            AgentMessageUi(
                "a2",
                "Second-round answer",
                usage = TokenUsageUi(inputTokens = 80, outputTokens = 12, cachedTokens = 20),
            ),
        )
        val updated = AgentContextCompactionUi.applyMarker(
            messages = ui,
            originalHistory = original,
            compressedHistory = compressed,
            markerId = "new",
        )
        val marker = updated.filterIsInstance<ContextCompactedMessageUi>().single()
        assertEquals("new", marker.id)
        assertEquals(230L, marker.preservedUsage.inputTokens)
        assertEquals(42L, marker.preservedUsage.outputTokens)
        assertEquals(70L, marker.preservedUsage.cachedTokens)
        val usage = conversationTokenUsage(updated)
        assertEquals(230L, usage.inputTokens)
        assertEquals(42L, usage.outputTokens)
        assertEquals(70L, usage.cachedTokens)
    }

    @Test
    fun completionToastCountMatchesTimelineVisibleMessageCount() {
        val original = listOf(msg("user", "old"), msg("assistant", "answer"),
            msg("tool", "tool output"), msg("user", "keep"))
        val compressed = listOf(msg("user", "${AgentContextCompactor.SUMMARY_PREFIX}\nsummary"), original.last())
        val count = AgentContextCompactionUi.completedMessageCount(original, compressed)
        val marker = AgentContextCompactionUi.applyMarker(
            messages = listOf(UserMessageUi("old", "old"), UserMessageUi("keep", "keep")),
            originalHistory = original, compressedHistory = compressed,
        ).filterIsInstance<ContextCompactedMessageUi>().single()
        assertEquals(2, count)
        assertEquals(marker.compactedCount, count)
    }

    @Test
    fun unchangedDuplicateAndEmptyResultsDoNotAnnounceSuccess() {
        val summary = listOf(msg("user", "${AgentContextCompactor.SUMMARY_PREFIX}\nsummary"))
        assertEquals(0, AgentContextCompactionUi.completedMessageCount(summary, summary))
        assertEquals(0, AgentContextCompactionUi.completedMessageCount(summary, emptyList()))
    }

    @Test
    fun maintenanceWithOldSummaryDoesNotAnnounceSuccess() {
        val original = listOf(msg("user", "${AgentContextCompactor.SUMMARY_PREFIX}\nold"),
            msg("tool", "long"))
        val pruned = original.dropLast(1) + msg("tool", "${AgentContextCompactor.TOOL_PRUNED_PREFIX} archive]")
        assertEquals(0, AgentContextCompactionUi.completedMessageCount(original, pruned))
        assertEquals(0, AgentContextCompactionUi.completedMessageCount(original,
            pruned + msg("tool", "new"), "Tool-output budget pruning (originals readable)"))
    }

    @Test
    fun nonSummaryHistoryChangesDoNotAnnounceSuccess() {
        assertEquals(0, AgentContextCompactionUi.completedMessageCount(
            listOf(msg("user", "old")), listOf(msg("user", "new"))))
    }

    private fun msg(role: String, content: String) =
        AgentModelClient.ConversationMessage(role = role, content = content)
}
