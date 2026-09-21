package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.data.db.EtaDatabase
import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRunArchiveStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun saveAndLoadPreservesHandoffEventsAndResult() {
        val createdAt = System.currentTimeMillis()
        val archivedRun = AgentRunArchiveStore.ArchivedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "run-1",
                source = "external_test",
                payload = AgentExternalArchivePayload(
                    userText = "Check system status",
                    conversationKey = "session-1",
                    title = "External entry",
                ).toJson(),
            ),
            events = listOf(
                AgentEvent.AssistantBlockDelta(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.THINKING,
                    index = 0,
                    deltaChars = 2,
                    delta = "First",
                ),
                AgentEvent.AssistantBlockDelta(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.THINKING,
                    index = 0,
                    deltaChars = 2,
                    delta = "View",
                ),
                AgentEvent.ToolStarted(
                    round = 1,
                    toolCallId = "call-1",
                    name = "run_command",
                    argsPreview = "Execute command · Android · root",
                    command = "uptime",
                ),
                AgentEvent.ToolFinished(
                    round = 1,
                    toolCallId = "call-1",
                    name = "run_command",
                    resultSummary = "ok=true, chars=42",
                    imageCount = 0,
                    imageBytes = 0,
                ),
                AgentEvent.RunFinished(
                    round = 1,
                    contentChars = 12,
                ),
            ),
            result = AgentRuntimeWire.RunResult(
                runId = "run-1",
                ok = true,
                content = "System status is normal",
                reasoningContent = "Check system status first",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "System status is normal",
                    )
                ),
            ),
            createdAt = createdAt,
            userImagePreviews = listOf(
                "data:image/png;base64,cHJldmlldw==",
            ),
        )

        AgentRunArchiveStore.add(context, archivedRun)

        val restored = AgentRunArchiveStore.list(context).single()

        assertEquals(archivedRun.handoff, restored.handoff)
        assertEquals(archivedRun.result, restored.result)
        assertEquals(archivedRun.createdAt, restored.createdAt)
        assertEquals(archivedRun.userImagePreviews, restored.userImagePreviews)
        assertEquals(
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                deltaChars = 4,
                delta = "FirstView",
            ),
            restored.events.first()
        )
        assertEquals(4, restored.events.size)
        assertEquals(archivedRun.events[2], restored.events[1])
    }

    @Test
    fun removeDeletesByRunIdOrHandoffId() {
        val createdAt = System.currentTimeMillis()
        AgentRunArchiveStore.add(
            context,
            AgentRunArchiveStore.ArchivedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = "handoff-1",
                    source = "external_test",
                    payload = AgentExternalArchivePayload(
                        userText = "Check system status",
                        conversationKey = "session-1",
                        title = "External entry",
                    ).toJson(),
                ),
                events = emptyList(),
                result = AgentRuntimeWire.RunResult(
                    runId = "run-1",
                    ok = true,
                    content = "done",
                ),
                createdAt = createdAt,
            )
        )

        AgentRunArchiveStore.remove(context, "run-1")

        assertEquals(emptyList<AgentRunArchiveStore.ArchivedRun>(), AgentRunArchiveStore.list(context))
    }
}
