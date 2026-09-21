package io.github.mangi.eta.ui.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingGfmParserTest {
    @Test
    fun numericCitationClusterIsStrippedBeforeParse() {
        val raw = "[[10]](<https://github.com/a>) [[9]](<https://github.com/b>) [[1]](<https://github.com/c>)See the DeepSeek harness first"
        assertEquals(
            "See the DeepSeek harness first",
            NumericCitationMarkup.strip(raw),
        )
        val snapshot = StreamingGfmParserSession().parse(raw, isComplete = true)
        assertFalse(snapshot.renderedSource.contains("[[10]]"))
        assertTrue(snapshot.renderedSource.contains("See the DeepSeek harness first"))
    }

    @Test
    fun headingIsParsedAsHeadingFromFirstStreamingSnapshot() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "## Title",
            isComplete = false,
        )

        assertEquals("## Title", snapshot.renderedSource)
        assertEquals(MarkdownElementTypes.ATX_2, snapshot.state.node.children.single().type)
    }

    @Test
    fun tableHeaderIsBufferedUntilDelimiterConfirmsTheBlock() {
        assertEquals(
            "",
            StreamingGfmProjection.project(
                source = "| Metric | Value |",
                isComplete = false,
            ),
        )
        assertEquals(
            "",
            StreamingGfmProjection.project(
                source = "| Metric | Value |\n| --",
                isComplete = false,
            ),
        )

        val confirmed = "| Metric | Value |\n| --- | --- |"
        val snapshot = StreamingGfmParserSession().parse(
            source = confirmed,
            isComplete = false,
        )

        assertEquals(confirmed, snapshot.renderedSource)
        assertEquals(GFMElementTypes.TABLE, snapshot.state.node.children.single().type)
    }

    @Test
    fun ordinaryPipeTextIsReleasedWhenNextLineCannotBeATableDelimiter() {
        val source = "Pick A | B\nThis is not a delimiter row"

        assertEquals(
            source,
            StreamingGfmProjection.project(source = source, isComplete = false),
        )
    }

    @Test
    fun incompleteStrongDelimiterUsesVirtualEofClosure() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "Note **pressure",
            isComplete = false,
        )

        assertEquals("Note **pressure**", snapshot.renderedSource)
        assertNotNull(snapshot.state.node.findRecursively(MarkdownElementTypes.STRONG))
    }

    @Test
    fun incompleteInlineCodeUsesVirtualEofClosure() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "Run `adb shell",
            isComplete = false,
        )

        assertEquals("Run `adb shell`", snapshot.renderedSource)
        assertNotNull(snapshot.state.node.findRecursively(MarkdownElementTypes.CODE_SPAN))
    }

    @Test
    fun incompleteLinkIsNotPublishedAsRawMarkdown() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "See [docs](https://example.com/do",
            isComplete = false,
        )

        assertEquals("See ", snapshot.renderedSource)
        assertFalse(snapshot.renderedSource.contains('['))
    }

    @Test
    fun openFenceRendersAsCodeWithoutChangingStoredSource() {
        val source = "```kotlin\nval answer = 42"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = false,
        )

        assertEquals(source, snapshot.originalSource)
        assertTrue(snapshot.renderedSource.endsWith("\n```"))
        assertNotNull(snapshot.state.node.findRecursively(MarkdownElementTypes.CODE_FENCE))
    }

    @Test
    fun completionParsesExactOriginalSourceWithoutVirtualCharacters() {
        val source = "Unclosed **markup"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = true,
        )

        assertEquals(source, snapshot.renderedSource)
        assertTrue(snapshot.isComplete)
    }

    @Test
    fun identicalStreamingParseKeepsAstIdentity() {
        val session = StreamingGfmParserSession()
        val first = session.parse("Body", isComplete = false)
        assertEquals(null, nextStreamingSnapshot(first, session.parse("Body", isComplete = false)))
        val completed = nextStreamingSnapshot(first, session.parse("Body", isComplete = true))
        assertEquals(true, completed?.isComplete)
        assertTrue(completed?.state === first.state)
        val grown = nextStreamingSnapshot(first, session.parse("Body plus delta", isComplete = false))
        assertEquals("Body plus delta", grown?.originalSource)
        assertEquals(first, nextStreamingSnapshot(null, first))
    }

    private fun ASTNode.findRecursively(type: IElementType): ASTNode? {
        if (this.type == type) return this
        return children.firstNotNullOfOrNull { child -> child.findRecursively(type) }
    }
}
