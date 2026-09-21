package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryIsolationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        AgentMemoryRepository.init(context)
    }

    @Test
    fun assistantsKeepSeparateMemoryFiles() {
        AgentMemoryRepository.replaceAll("Assistant A memory", "assistant-a")
        AgentMemoryRepository.replaceAll("Assistant B memory", "assistant-b")

        assertEquals("Assistant A memory", AgentMemoryRepository.snapshot("assistant-a").content)
        assertEquals("Assistant B memory", AgentMemoryRepository.snapshot("assistant-b").content)

        AgentMemoryRepository.replaceAll("Assistant A updated", "assistant-a")
        assertEquals("Assistant A updated", AgentMemoryRepository.snapshot("assistant-a").content)
        assertEquals("Assistant B memory", AgentMemoryRepository.snapshot("assistant-b").content)
    }
}
