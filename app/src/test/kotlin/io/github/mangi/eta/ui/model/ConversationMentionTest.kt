package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.MentionedConversation
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConversationMentionTest {
    private fun summary(id: String, title: String = id, time: Long = 0L) = ConversationSummaryUi(
        id = id, title = title, preview = "Preview", timeLabel = "", updatedAtMillis = time, mode = ConversationModeUi.Chat,
    )
    @Test fun querySupportsChineseAndCursorInMiddle() {
        assertEquals("价格", ConversationMention.queryAtCursor("结合@价格看看", 5)?.query)
        assertEquals("", ConversationMention.queryAtCursor("@", 1)?.query)
        assertEquals("旧 会话", ConversationMention.queryAtCursor("@旧 会话", 5)?.query)
        assertNull(ConversationMention.queryAtCursor("user@example.com", 16))
        assertNull(ConversationMention.queryAtCursor("@旧\n消息", 5))
    }
    @Test fun candidatesExcludeSelfAndDuplicatesAndSortByRecent() {
        val all = listOf(summary("a", time = 1), summary("b", time = 9), summary("c", time = 3), summary("d", time = 4))
        assertEquals(listOf("d", "c"), ConversationMention.candidates(all, "", "a", setOf("b")).map { it.id })
        assertEquals(listOf("b"), ConversationMention.candidates(all, "b", null, emptySet()).map { it.id })
        assertEquals(1, ConversationMention.candidates(all, "Preview", null, emptySet(), 1).size)
    }
    @Test fun oversizedSingleMessageIsBoundedAndMarked() {
        val text = ConversationMention.transcript(listOf(AgentMessageUi("a", "Start" + "x".repeat(20_000) + "End")), 180)
        assertTrue(text.length <= 180)
        assertTrue(text.contains("Start"))
        assertTrue(text.contains("End"))
        assertTrue(text.contains("middle records omitted"))
        assertEquals("", ConversationMention.transcript(listOf(AgentMessageUi("a", "text")), 0))
    }
    @Test fun fullConversationIsKeptInOriginalOrder() {
        val text = ConversationMention.transcript(listOf(UserMessageUi("1", "Start"), AgentMessageUi("2", "Reply")))
        assertEquals("User: Start\n\nAssistant: Reply", text)
        assertFalse(text.contains("Truncated"))
    }
    @Test fun overflowKeepsStartAndEnd() {
        val messages = (1..40).map { index ->
            if (index % 2 == 1) UserMessageUi("$index", "Question$index")
            else AgentMessageUi("$index", "Answer$index")
        }
        val text = ConversationMention.transcript(messages, 120)
        assertTrue(text.contains("Question1"))
        assertTrue(text.contains("Answer40"))
        assertFalse(text.contains("Question20"))
        assertTrue(text.contains("middle records omitted"))
    }
    @Test fun thinkingAndToolSummaryAreIncluded() {
        val text = ConversationMention.transcript(listOf(
            ThinkingMessageUi("t", "Check the context first", isStreaming = false),
            ToolSummaryMessageUi("s", listOf("read_file", "terminal")),
            AgentMessageUi("a", "Conclusion"),
        ))
        assertTrue(text.contains("Thinking: Check the context first"))
        assertTrue(text.contains("Tools: read_file, terminal"))
        assertTrue(text.contains("Assistant: Conclusion"))
    }
    @Test fun quotedReferencesNeverRecursivelyExpand() {
        val content = AgentFileReferencePromptCodec.format("Refer to it", emptyList(), listOf(MentionedConversation("id", "Old conversation", "do not recursively copy me")))
        val text = ConversationMention.transcript(listOf(UserMessageUi("u", content)))
        assertTrue(text.contains("Old conversation"))
        assertTrue(text.contains("Refer to it"))
        assertFalse(text.contains("do not recursively copy me"))
    }
    @Test fun toolEvidenceIncludesArgumentsAndResults() {
        val text = ConversationMention.transcript(listOf(ToolActivityMessageUi(
            id = "t",
            toolName = "read_file",
            status = ToolActivityStatusUi.Success,
            argumentsSummary = "path=/workspace/Eta/README.md",
            command = "cat README.md",
            resultSummary = "# Eta",
            imageCount = 2,
        )))
        assertTrue(text.contains("read_file"))
        assertTrue(text.contains("Success"))
        assertTrue(text.contains("path=/workspace/Eta/README.md"))
        assertTrue(text.contains("cat README.md"))
        assertTrue(text.contains("# Eta"))
        assertTrue(text.contains("Images: 2"))
        assertFalse(text.contains("could not be exported"))
    }

    @Test fun toolEvidenceWritesDetailsToWorkspaceFileWhenFilesDirIsProvided() {
        val filesDir = File.createTempFile("mention-files", null).apply {
            delete()
            mkdirs()
        }
        val text = ConversationMention.transcript(
            listOf(ToolActivityMessageUi(
                id = "tool-1",
                toolName = "read_file",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "path=/workspace/Eta/README.md",
                command = "cat README.md",
                resultSummary = "# Eta",
            )),
            filesDir = filesDir,
            conversationId = "conv-a",
        )
        assertTrue(text.contains("Details file:"))
        assertTrue(text.contains("conv-a"))
        assertTrue(text.contains("Snapshot cache/tools"))
        assertTrue(text.contains("read_file"))
        assertFalse(text.contains("path=/workspace/Eta/README.md"))
        val file = File(text.substringAfter("Details file: ").substringBefore('\n'))
        assertTrue(file.isFile)
        val details = file.readText()
        assertTrue(details.contains("path=/workspace/Eta/README.md"))
        assertTrue(details.contains("cat README.md"))
        assertTrue(details.contains("# Eta"))
        filesDir.deleteRecursively()
    }

    @Test fun toolEvidenceOmitsBlankOptionalFields() {
        val text = ConversationMention.transcript(listOf(ToolActivityMessageUi(
            id = "t",
            toolName = "search_files",
            status = ToolActivityStatusUi.Running,
            argumentsSummary = " ",
        )))
        assertEquals("Tool search_files: Running", text)
    }
    @Test fun terminalNoticeIsRetained() {
        val text = ConversationMention.transcript(listOf(SystemNoticeMessageUi("s", SystemNoticeCode.Stopped)))
        assertTrue(text.contains("Stopped"))
    }
    @Test fun totalBudgetIncludesAttachedSnapshots() {
        assertEquals(1_000, ConversationMention.remainingTranscriptBudget(listOf(
            PendingConversationMentionUi("1", "one", "one", "x".repeat(ConversationMention.MAX_TOTAL_CHARS - 1_000)),
        )))
    }
}
