package io.github.mangi.eta.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLogSinkTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appendWritesUtf8AndAddsTrailingNewline() {
        val sink = FileLogSink(temporaryFolder.root, "eta-app.log")
        sink.append("Hello")
        sink.append("world\n")
        sink.close()

        assertEquals("Hello\nworld\n", File(temporaryFolder.root, "eta-app.log").readText())
        assertEquals(listOf("eta-app.log"), sink.files().map { it.name })
    }

    @Test
    fun rotateKeepsNewestFilesAndDropsOldest() {
        val sink = FileLogSink(
            directory = temporaryFolder.root,
            fileName = "eta-app.log",
            maxBytes = 24,
            maxRotatedFiles = 2,
        )
        repeat(8) { index ->
            sink.append("line-$index-XXXX")
        }
        sink.close()

        val names = temporaryFolder.root.list()?.sorted().orEmpty()
        assertTrue(names.contains("eta-app.log") || names.contains("eta-app.1.log"))
        assertTrue("eta-app.1.log" in names)
        assertTrue("eta-app.2.log" in names)
        assertFalse(temporaryFolder.root.list()?.contains("eta-app.3.log") == true)

        val combined = sink.files().joinToString("") { it.readText() }
        assertTrue(combined.contains("line-7-XXXX"))
        assertFalse(combined.contains("line-0-XXXX"))
    }

    @Test
    fun closeRejectsLaterAppends() {
        val sink = FileLogSink(temporaryFolder.root, "eta-app.log")
        sink.append("keep")
        sink.close()
        sink.append("drop")

        assertEquals("keep\n", File(temporaryFolder.root, "eta-app.log").readText())
    }

    @Test
    fun clearDeletesCurrentAndRotatedFiles() {
        val sink = FileLogSink(
            directory = temporaryFolder.root,
            fileName = "eta-app.log",
            maxBytes = 16,
            maxRotatedFiles = 2,
        )
        repeat(6) { sink.append("payload-$it") }
        sink.clear()

        assertTrue(temporaryFolder.root.list().orEmpty().none { it.startsWith("eta-app") })
        assertTrue(sink.files().isEmpty())
    }
}
