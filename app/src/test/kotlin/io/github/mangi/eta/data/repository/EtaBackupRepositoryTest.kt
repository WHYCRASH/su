package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.withApiKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaBackupRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        java.io.File(context.filesDir, "backup-restore").deleteRecursively()
        io.github.mangi.eta.agent.runtime.AgentExecutionService.endBackupMaintenance()
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
        AssistantRepository.init(context)
        McpServerRepository.init(context)
    }

    @Test
    fun exportAndImportRestoresProvidersConversationsAndMemory() = runBlocking {
        val provider = ProviderRepository.addProvider(
            io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting(
                id = "backup-provider",
                name = "Backup",
                baseUrl = "https://api.example.com/v1",
                models = listOf(
                    io.github.mangi.eta.data.model.Model(
                        id = "backup-model",
                        modelId = "model-1",
                        displayName = "Model 1",
                        source = ModelSource.CATALOG,
                    ),
                ),
            ),
        ).withApiKey("sk-backup-test")
        ProviderRepository.updateProvider(provider)
        SettingsDataStore.setSelection(provider.id, provider.models.first().id)
        AgentMemoryRepository.replaceAll("# Core memory\nLikes Kotlin")

        val conversation = ConversationEntity(
            id = "conversation-backup",
            title = "Backup conversation",
            thinkingEnabled = true,
            createdAt = 1L,
            updatedAt = 2L,
            providerId = provider.id,
            modelId = provider.models.first().id,
        )
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = listOf(conversation),
            messages = listOf(
                ConversationMessageEntity(
                    id = "message-backup",
                    conversationId = conversation.id,
                    sortIndex = 0,
                    type = "user",
                    content = "Keep this message",
                ),
            ),
            contextCheckpoints = listOf(
                ConversationContextCheckpointEntity(
                    conversationId = conversation.id,
                    historyJson = "[]",
                ),
            ),
            state = ConversationStateEntity(selectedConversationId = conversation.id),
        )

        val output = ByteArrayOutputStream()
        val exported = EtaBackupRepository.export(context, output)
        assertEquals(1, exported.conversationCount)
        assertTrue(exported.providerCount > 0)
        assertEquals("# Core memory\nLikes Kotlin", AgentMemoryRepository.snapshot().content)

        ProviderRepository.updateProvider(provider.withApiKey("changed"))
        AgentMemoryRepository.replaceAll("changed")
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = emptyList(),
            messages = emptyList(),
            contextCheckpoints = emptyList(),
            state = null,
        )

        val imported = EtaBackupRepository.import(
            context,
            ByteArrayInputStream(output.toByteArray()),
        )
        assertEquals(1, imported.conversationCount)
        assertEquals("# Core memory\nLikes Kotlin", AgentMemoryRepository.snapshot().content)
        assertEquals(
            "Keep this message",
            EtaDatabase.get(context).conversationDao().messages().single().content,
        )
        val restoredSettings = SettingsDataStore.settings()
        assertEquals(provider.id, restoredSettings.selectedProviderId)
        assertEquals(provider.models.first().id, restoredSettings.selectedModelId)
        val restoredConversation = EtaDatabase.get(context).conversationDao().conversationEntities().single()
        assertEquals(provider.id, restoredConversation.providerId)
        assertEquals(provider.models.first().id, restoredConversation.modelId)
        assertEquals("sk-backup-test", ProviderRepository.providerById(provider.id)?.apiKey)
        assertEquals(ModelSource.CATALOG, ProviderRepository.providerById(provider.id)?.models?.first()?.source)
    }

    @Test(expected = EtaBackupException::class)
    fun rejectsUnknownBackupFormatBeforeChangingData(): Unit = runBlocking {
        EtaBackupRepository.inspect(
            ByteArrayInputStream("{\"format\":\"other\",\"schemaVersion\":1,\"exportedAt\":0}".toByteArray())
        )
    }
}
