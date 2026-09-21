package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileReferencePromptCodecTest {
    @Test
    fun formatAndParse_roundTripsFilesDirectoriesAndUnicode() {
        val references = listOf(
            AgentFileReference(
                displayName = "Café report – final.txt",
                absolutePath = "/storage/emulated/0/Download/report-final.txt",
                kind = AgentFileReferenceKind.File,
            ),
            AgentFileReference(
                displayName = "Project Materials",
                absolutePath = "/data/local/tmp/project-files",
                kind = AgentFileReferenceKind.Directory,
            ),
        )

        val formatted = AgentFileReferencePromptCodec.format("Summarize this content", references)

        assertEquals(
            """# Files mentioned by the user:

## Café report – final.txt: /storage/emulated/0/Download/report-final.txt

## Project Materials/: /data/local/tmp/project-files

## My request:
Summarize this content""",
            formatted,
        )
        assertEquals(
            AgentFileReferencePrompt(request = "Summarize this content", references = references),
            AgentFileReferencePromptCodec.parse(formatted),
        )
    }

    @Test
    fun format_supportsFileOnlyMessageAndDeduplicatesPaths() {
        val reference = AgentFileReference(
            displayName = "archive.zip",
            absolutePath = "/storage/emulated/0/Download/archive.zip",
            kind = AgentFileReferenceKind.File,
        )

        val parsed = AgentFileReferencePromptCodec.parse(
            AgentFileReferencePromptCodec.format("", listOf(reference, reference.copy(displayName = "Repeat")))
        )

        assertEquals("", parsed.request)
        assertEquals(listOf(reference), parsed.references)
    }

    @Test
    fun parse_preservesOrdinaryOrMalformedUserText() {
        val ordinary = "# Files mentioned by the user:\n\nThis is just plain text"

        assertEquals(
            AgentFileReferencePrompt(request = ordinary, references = emptyList()),
            AgentFileReferencePromptCodec.parse(ordinary),
        )
        assertEquals("Original request", AgentFileReferencePromptCodec.format("Original request", emptyList()))
    }

    @Test
    fun policy_requiresTerminalToolsAndUsesFirstFileForEmptyTitle() {
        val reference = AgentFileReference(
            displayName = "device.log",
            absolutePath = "/data/local/tmp/device.log",
            kind = AgentFileReferenceKind.File,
        )

        assertFalse(AgentFileReferencePolicy.canSend(listOf(reference), terminalToolsEnabled = false))
        assertTrue(AgentFileReferencePolicy.canSend(listOf(reference), terminalToolsEnabled = true))
        assertTrue(AgentFileReferencePolicy.canSend(emptyList(), terminalToolsEnabled = false))
        assertEquals("device.log", AgentFileReferencePolicy.titleSource("", listOf(reference)))
        assertEquals("Analyze logs", AgentFileReferencePolicy.titleSource("Analyze logs", listOf(reference)))
    }
}
