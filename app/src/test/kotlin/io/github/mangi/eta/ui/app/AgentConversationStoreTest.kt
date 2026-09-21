package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun saveAndLoadPreservesConversations() {
        val conversation = AgentChatHomeUiState(
            messages = listOf(
                UserMessageUi(
                    id = "user-1",
                    content = "Take a look at the current screen",
                    isEdited = true,
                ),
                ThinkingMessageUi(
                    id = "thinking-1",
                    content = "Need to observe the screen first",
                    isStreaming = false,
                    elapsedSeconds = 3,
                    collapsed = true,
                ),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "Run command · Android · root",
                    command = "pm list packages | head",
                    resultSummary = "ok=true, chars=100",
                    imageCount = 1,
                ),
                AgentMessageUi(
                    id = "assistant-1",
                    content = "| Item | Content |\n| --- | --- |\n| Battery | 88% |",
                    isStreaming = false,
                    renderMarkdown = true,
                    generatedAtMillis = 1_800_000_000_000L,
                    usage = TokenUsageUi(
                        contextTokens = 100,
                        inputTokens = 30,
                        outputTokens = 40,
                        reasoningTokens = 20,
                        cachedTokens = 10,
                    ),
                ),
            ),
            history = listOf(
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "user",
                    content = "Take a look at the current screen",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "",
                    reasoningContent = "Need to observe the screen first",
                    toolCallsJson = """[{"id":"toolu_1","type":"function","function":{"name":"observe_screen","arguments":"{}"}}]""",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "tool",
                    content = "{\"ok\":true}",
                    toolCallId = "toolu_1",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "| Item | Content |\n| --- | --- |\n| Battery | 88% |",
                ),
            ),
            input = "Should not save draft",
            isStreaming = true,
            thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH,
            providerId = "provider-1",
            modelId = "model-1",
        )

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf("conv-1" to conversation),
                titles = mapOf("conv-1" to "Screen analysis"),
                updatedAt = mapOf("conv-1" to 1234L),
            )
        }

        val snapshot = AgentConversationStore.load(context)

        assertEquals("conv-1", snapshot.selectedConversationId)
        assertEquals("Screen analysis", snapshot.titles.getValue("conv-1"))
        assertEquals(1234L, snapshot.updatedAt.getValue("conv-1"))
        val restored = snapshot.conversationsById.getValue("conv-1")
        assertEquals("", restored.input)
        assertFalse(restored.isStreaming)
        assertTrue(restored.thinkingEnabled)
        assertEquals(ReasoningEffort.HIGH, restored.reasoningEffort)
        assertEquals("provider-1", restored.providerId)
        assertEquals("model-1", restored.modelId)
        assertEquals(conversation.messages, restored.messages)
        assertEquals(conversation.history, restored.history)
    }

    @Test
    fun saveAndLoadKeepsDifferentModelsPerConversation() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-a",
                conversationsById = mapOf(
                    "conv-a" to AgentChatHomeUiState(
                        messages = emptyList(),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                        providerId = "provider-a",
                        modelId = "model-a",
                    ),
                    "conv-b" to AgentChatHomeUiState(
                        messages = emptyList(),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                        providerId = "provider-b",
                        modelId = "model-b",
                    ),
                ),
                titles = mapOf("conv-a" to "A", "conv-b" to "B"),
                updatedAt = mapOf("conv-a" to 2L, "conv-b" to 1L),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertEquals("provider-a", snapshot.conversationsById.getValue("conv-a").providerId)
        assertEquals("model-a", snapshot.conversationsById.getValue("conv-a").modelId)
        assertEquals("provider-b", snapshot.conversationsById.getValue("conv-b").providerId)
        assertEquals("model-b", snapshot.conversationsById.getValue("conv-b").modelId)
    }

    @Test
    fun saveAndLoadPreservesSemanticSystemNoticesWithoutTranslatedContent() {
        val notice = SystemNoticeMessageUi(
            id = "assistant-run-1-1",
            code = SystemNoticeCode.RuntimeFailed,
            detail = "upstream timeout",
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-notice",
                conversationsById = mapOf(
                    "conv-notice" to AgentChatHomeUiState(
                        messages = listOf(notice),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-notice" to ""),
                updatedAt = mapOf("conv-notice" to 1L),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertEquals("", snapshot.titles.getValue("conv-notice"))
        assertEquals(
            notice,
            snapshot.conversationsById.getValue("conv-notice").messages.single(),
        )
    }

    @Test
    fun unknownStoredEffortFallsBackToDefault() {
        runBlocking {
            EtaDatabase.get(context).conversationDao().replaceAll(
                conversations = listOf(
                    ConversationEntity(
                        id = "conv-unknown",
                        title = "Unknown",
                        thinkingEnabled = false,
                        reasoningEffort = "future_effort",
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                ),
                messages = emptyList(),
                state = ConversationStateEntity(selectedConversationId = "conv-unknown"),
            )
        }

        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-unknown")

        assertEquals(ReasoningEffort.DEFAULT, restored.reasoningEffort)
        assertTrue(restored.thinkingEnabled)
    }

    @Test
    fun saveAndLoadPreservesAllConversationsAndMessagesWithoutClipping() {
        val longContent = "x".repeat(20_000)
        val primaryMessages = buildList {
            add(UserMessageUi(id = "conv-0-user-long", content = longContent))
            repeat(130) { index ->
                add(
                    AgentMessageUi(
                        id = "conv-0-assistant-$index",
                        content = "assistant-$index",
                        isStreaming = false,
                    )
                )
            }
        }
        val conversations = buildMap {
            put(
                "conv-0",
                AgentChatHomeUiState(
                    messages = primaryMessages,
                    input = "",
                    isStreaming = false,
                    thinkingEnabled = false,
                )
            )
            repeat(59) { index ->
                val id = "conv-${index + 1}"
                put(
                    id,
                    AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "$id-user", content = "message-$id")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                )
            }
        }
        val titles = conversations.keys.associateWith { id -> "title-$id" }
        val updatedAt = conversations.keys.associateWith { id -> id.removePrefix("conv-").toLong() }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-0",
                conversationsById = conversations,
                titles = titles,
                updatedAt = updatedAt,
            )
        }

        val snapshot = AgentConversationStore.load(context)

        assertEquals(60, snapshot.conversationsById.size)
        val restored = snapshot.conversationsById.getValue("conv-0")
        assertEquals(131, restored.messages.size)
        assertEquals(longContent, (restored.messages.first() as UserMessageUi).content)
        assertEquals("assistant-129", (restored.messages.last() as AgentMessageUi).content)
    }

    @Test
    fun saveBoundsConversationCheckpointWithoutClippingDisplayedMessages() {
        val displayedContent = "Show message-${"d".repeat(120_000)}"
        val history = buildList {
            repeat(30) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "History-$index-${"h".repeat(50_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "Latest context"))
        }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-large",
                conversationsById = mapOf(
                    "conv-large" to AgentChatHomeUiState(
                        messages = listOf(
                            UserMessageUi(id = "user-large", content = displayedContent)
                        ),
                        history = history,
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-large" to "Long conversation"),
                updatedAt = mapOf("conv-large" to 1L),
            )
        }

        val checkpoint = runBlocking {
            EtaDatabase.get(context)
                .conversationDao()
                .contextCheckpoint("conv-large")!!
        }
        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-large")

        assertTrue(
            checkpoint.historyJson.length <=
                AgentConversationCodec.MAX_CONVERSATION_CHECKPOINT_CHARS
        )
        assertEquals(displayedContent, (restored.messages.single() as UserMessageUi).content)
        assertTrue(restored.history.first().content.contains("were compacted"))
        assertEquals("Latest context", restored.history.last().content)
    }

    @Test
    fun loadIgnoresLegacyHistoryColumnAndFallsBackToMessageRows() {
        runBlocking {
            val dao = EtaDatabase.get(context).conversationDao()
            dao.insertConversations(
                listOf(
                    ConversationEntity(
                        id = "conv-legacy-large",
                        title = "Old long conversation",
                        thinkingEnabled = false,
                        historyJson = "x".repeat(2_500_000),
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                )
            )
            dao.insertMessages(
                listOf(
                    ConversationMessageEntity(
                        id = "legacy-user",
                        conversationId = "conv-legacy-large",
                        sortIndex = 0,
                        type = "user",
                        content = "Restore from message history",
                    )
                )
            )
        }

        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-legacy-large")

        assertEquals("Restore from message history", restored.history.single().content)
        assertEquals("Restore from message history", (restored.messages.single() as UserMessageUi).content)
    }

    @Test
    fun loadKeepsDatabaseEmptyUntilFirstMessageIsSent() {
        val snapshot = AgentConversationStore.load(context)

        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }

    @Test
    fun creatingConversationKeepsEmptyStateOutOfHistoryAndDatabase() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope)

            state.createConversation()
            state.createConversation()

            assertEquals(null, state.conversationPaneState.selectedConversationId)
            assertTrue(state.conversationPaneState.conversations.isEmpty())
            assertTrue(
                runBlocking {
                    EtaDatabase.get(context).conversationDao().conversations().isEmpty()
                }
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun savingEmptySnapshotClearsPreviouslyPersistedConversations() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf(
                    "conv-1" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-1", content = "hello")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-1" to "hello"),
                updatedAt = mapOf("conv-1" to 1L),
            )
            AgentConversationStore.save(
                context = context,
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }

    @Test
    fun saveAndLoadPreservesFoldersPinAndFolderAssignment() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf(
                    "conv-1" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-1", content = "hello")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                    "conv-2" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-2", content = "world")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-1" to "hello", "conv-2" to "world"),
                updatedAt = mapOf("conv-1" to 2L, "conv-2" to 1L),
                folderIds = mapOf("conv-1" to "folder-work"),
                pinnedIds = setOf("conv-2"),
                folders = listOf(
                    ConversationFolderUi(id = "folder-work", name = "Work", sortIndex = 0),
                ),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertEquals("folder-work", snapshot.folderIds["conv-1"])
        assertEquals(null, snapshot.folderIds["conv-2"])
        assertEquals(setOf("conv-2"), snapshot.pinnedIds)
        assertEquals(listOf("folder-work"), snapshot.folders.map { it.id })
        assertEquals("Work", snapshot.folders.single().name)
    }

    @Test
    fun saveAndLoadPreservesContextCompactedMarker() {
        val marker = ContextCompactedMessageUi(
            id = "compacted-1",
            compactedCount = 6,
            summary = "The user wants to check Actions; the push succeeded.",
            compressorLabel = "su · grok-4.6",
            baselineTokens = 1800,
            resumeRound = 2,
            preservedUsage = ConversationTokenUsageUi(
                inputTokens = 2200,
                outputTokens = 90,
                cachedTokens = 700,
            ),
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-compact",
                conversationsById = mapOf(
                    "conv-compact" to AgentChatHomeUiState(
                        messages = listOf(
                            UserMessageUi(id = "u1", content = "Old messages"),
                            marker,
                            UserMessageUi(id = "u2", content = "Continue"),
                        ),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-compact" to "Compress"),
                updatedAt = mapOf("conv-compact" to 2L),
            )
        }
        val restored = AgentConversationStore.load(context)
            .conversationsById.getValue("conv-compact").messages
        assertEquals(listOf("u1", "compacted-1", "u2"), restored.map { it.id })
        val loaded = restored[1] as ContextCompactedMessageUi
        assertEquals(6, loaded.compactedCount)
        assertEquals("The user wants to check Actions; the push succeeded.", loaded.summary)
        assertEquals("su · grok-4.6", loaded.compressorLabel)
        assertEquals(1800, loaded.baselineTokens)
        assertEquals(2, loaded.resumeRound)
        assertEquals(2200L, loaded.preservedUsage.inputTokens)
        assertEquals(90L, loaded.preservedUsage.outputTokens)
        assertEquals(700L, loaded.preservedUsage.cachedTokens)
    }

    @Test
    fun saveAndLoadPreservesAssistantBinding() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-assistant",
                conversationsById = mapOf(
                    "conv-assistant" to AgentChatHomeUiState(
                        messages = emptyList(),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                        assistantId = "assistant-bound",
                    ),
                ),
                titles = mapOf("conv-assistant" to "Bound"),
                updatedAt = mapOf("conv-assistant" to 1L),
            )
        }
        val restored = AgentConversationStore.load(context)
        assertEquals("assistant-bound", restored.conversationsById.getValue("conv-assistant").assistantId)
    }

}
