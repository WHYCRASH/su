package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryContextBuilderTest {
    @Test
    fun coreBudgetTracksWindowWithSafeUnknownFallback() {
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(null))
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(128_000))
        assertEquals(16_000, AgentMemoryContextBuilder.coreBudgetChars(256_000))
        assertEquals(62_500, AgentMemoryContextBuilder.coreBudgetChars(1_000_000))
        assertEquals(4_000, AgentMemoryContextBuilder.coreBudgetChars(16_000))
    }

    @Test
    fun injectsOnlyCoreSectionAndBoundedHeadingIndex() {
        val content = "# Core Memory\nLong-Term Preferences\n## Relationships\nFamily\n# Detailed Background\nShould not be automatically injected"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals("# Core Memory\nLong-Term Preferences\n## Relationships\nFamily", context.coreContent)
        assertFalse(context.coreContent.contains("Should not be automatically injected"))
        assertEquals("# Core Memory\n## Relationships\n# Detailed Background", context.headingIndex)
        assertFalse(context.coreTruncated)
    }

    @Test
    fun oversizedCoreIsTruncatedWithoutDroppingRevision() {
        val content = "# Core Memory\n" + "a".repeat(10_000)
        val snapshot = snapshot(content)
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        assertEquals(8_000, context.coreContent.length)
        assertTrue(context.coreTruncated)
        assertEquals(snapshot.revision, context.revision)
    }

    @Test
    fun detailsWithoutCoreHeadingAreIndexedButNotAutomaticallyInjected() {
        val context = AgentMemoryContextBuilder.build(
            snapshot("# Projects\nDetails that should only be read on demand"),
            256_000,
        )

        assertEquals("", context.coreContent)
        assertEquals("# Projects", context.headingIndex)
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
