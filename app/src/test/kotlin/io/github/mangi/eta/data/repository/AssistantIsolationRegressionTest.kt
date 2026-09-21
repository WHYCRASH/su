package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.AssistantStorage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AssistantIsolationRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun staleEditorCannotOverwriteToolMutation() {
        val store = AgentMemoryStore(temporary.newFolder("assistant-a"))
        val draft = store.replaceAll("original")
        store.mutate(AgentMemoryMutation.Append(draft.revision, "tool update"))
        val error = assertThrows(AgentMemoryException::class.java) { store.replaceAll("stale UI", draft.revision) }
        assertEquals("MEMORY_CONFLICT", error.code)
        assertTrue(store.snapshot().content.contains("tool update"))
    }

    @Test fun clearUsesRevisionAndCannotEraseNewContent() {
        val store = AgentMemoryStore(temporary.newFolder("assistant-a"))
        val draft = store.snapshot()
        store.replaceAll("new memory")
        assertThrows(AgentMemoryException::class.java) { store.replaceAll("", draft.revision) }
        assertEquals("new memory", store.snapshot().content)
    }

    @Test fun separateStoreInstancesShareTheSameCasLock() {
        val directory = temporary.newFolder("assistant-a")
        val first = AgentMemoryStore(directory)
        val second = AgentMemoryStore(directory)
        val initial = first.replaceAll("base")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val conflicts = AtomicInteger()
        val workers = listOf(first, second).mapIndexed { index, store ->
            thread {
                ready.countDown()
                start.await()
                try { store.replaceAll("writer-$index", initial.revision); successes.incrementAndGet() }
                catch (error: AgentMemoryException) { if (error.code == "MEMORY_CONFLICT") conflicts.incrementAndGet() else throw error }
            }
        }
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
        assertEquals(1, successes.get())
        assertEquals(1, conflicts.get())
    }

    @Test fun deletedStoreCannotBeRecreatedByAnOldHandle() {
        val directory = temporary.newFolder("assistant-a")
        val first = AgentMemoryStore(directory)
        val second = AgentMemoryStore(directory)
        val initial = first.replaceAll("private")
        first.delete()
        assertEquals("ASSISTANT_DELETED", assertThrows(AgentMemoryException::class.java) {
            second.replaceAll("resurrect", initial.revision)
        }.code)
        assertFalse(directory.exists())
    }

    @Test fun explicitRestoreCanReviveOnlyTheTargetStore() {
        val a = AgentMemoryStore(File(temporary.root, "assistant-a"))
        val b = AgentMemoryStore(File(temporary.root, "assistant-b"))
        a.replaceAll("A"); b.replaceAll("B"); a.delete()
        a.restore("restored A")
        assertEquals("restored A", a.snapshot().content)
        assertEquals("B", b.snapshot().content)
    }

    @Test fun idsAreValidatedWithoutLossyNormalization() {
        listOf("", ".", "..", "../other", "/absolute", "a/b", "a\\b", " a", "a ", "café", "a".repeat(81)).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { AssistantStorage.id(raw) }
        }
        assertEquals("a.b", AssistantStorage.id("a.b"))
        assertEquals("a_b", AssistantStorage.id("a_b"))
        assertNotEquals(AssistantStorage.id("a.b"), AssistantStorage.id("a_b"))
    }
}
