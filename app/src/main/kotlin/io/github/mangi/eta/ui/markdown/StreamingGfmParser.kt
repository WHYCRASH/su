package io.github.mangi.eta.ui.markdown

import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * GFM parse session for append-style model output.
 *
 * Every commit runs one full GFM parse on the calling thread; callers should confine the session to a
 * background serial dispatcher. Full parsing keeps block syntax on one CommonMark/GFM rule set instead of
 * having the UI guess node types. While the stream is open, structures still awaiting closure are completed
 * only against a virtual EOF; virtual characters are never written back to the message or the final snapshot.
 */
internal class StreamingGfmParserSession {
    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)
    private val referenceLinkHandler = ReferenceLinkHandlerImpl()
    private var acceptedSource = ""

    fun parse(source: String, isComplete: Boolean): StreamingGfmSnapshot {
        val source = NumericCitationMarkup.strip(source)
        if (!source.startsWith(acceptedSource)) {
            // Re-baseline on session restore or upstream message correction; the parser holds no syntax
            // state that could leak into the new document, so later snapshots stay append-style.
            acceptedSource = ""
        }
        acceptedSource = source

        val renderedSource = StreamingGfmProjection.project(
            source = source,
            isComplete = isComplete,
        )
        val root = parser.buildMarkdownTreeFromString(renderedSource)
        return StreamingGfmSnapshot(
            originalSource = source,
            renderedSource = renderedSource,
            isComplete = isComplete,
            state = State.Success(
                node = root,
                content = renderedSource,
                linksLookedUp = false,
                referenceLinkHandler = referenceLinkHandler,
            ),
        )
    }
}

internal data class StreamingGfmSnapshot(
    val originalSource: String,
    val renderedSource: String,
    val isComplete: Boolean,
    val state: State.Success,
)

/** Returns a snapshot to publish, or null when the visible AST would not change. */
internal fun nextStreamingSnapshot(
    current: StreamingGfmSnapshot?,
    parsed: StreamingGfmSnapshot,
): StreamingGfmSnapshot? {
    if (current == null) return parsed
    if (current.originalSource != parsed.originalSource ||
        current.renderedSource != parsed.renderedSource
    ) {
        return parsed
    }
    if (current.isComplete != parsed.isComplete) {
        return current.copy(isComplete = parsed.isComplete)
    }
    return null
}

/**
 * Give the real EOF and the streaming "no more characters for now" EOF different semantics.
 *
 * - Tables do not publish a candidate header until the delimiter row confirms it, so pipes are not first
 *   shown as a plain paragraph.
 * - Unclosed links stay in the parser buffer instead of exposing a half-written target URL to the UI.
 * - Fenced code, code spans, and emphasis confirmed as started use virtual closers that exist only in
 *   the parse snapshot, keeping one node type from the first moment they can be identified.
 */
internal object StreamingGfmProjection {
    fun project(source: String, isComplete: Boolean): String {
        if (isComplete || source.isEmpty()) return source

        val tableSafeEnd = ambiguousTableStart(source) ?: source.length
        var projected = source.substring(0, tableSafeEnd)

        val openFence = findOpenFence(projected)
        if (openFence != null) {
            if (!projected.endsWith('\n')) projected += "\n"
            return projected + openFence.marker.toString().repeat(openFence.length)
        }

        val pendingLinkStart = findPendingLinkStart(projected)
        if (pendingLinkStart != null) {
            projected = projected.substring(0, pendingLinkStart)
        }

        val inlineClosures = findInlineClosures(projected)
        return projected + inlineClosures
    }

    private fun ambiguousTableStart(source: String): Int? {
        val lines = source.toLineSlices()
        if (lines.isEmpty()) return null

        val blockStart = lines.indexOfLast { it.text.isBlank() }
            .let { blankIndex -> if (blankIndex == -1) 0 else blankIndex + 1 }
        val blockLines = lines.subList(blockStart, lines.size)
        if (blockLines.isEmpty()) return null

        val confirmedTable = (1 until blockLines.size).any { index ->
            containsUnescapedPipe(blockLines[index - 1].text) &&
                isValidTableDelimiter(blockLines[index].text)
        }
        if (confirmedTable) return null

        val current = blockLines.last()
        val previous = blockLines.getOrNull(blockLines.lastIndex - 1)

        if (previous != null && containsUnescapedPipe(previous.text)) {
            if (current.text.isEmpty()) {
                return if (source.endsWith("\n\n")) null else previous.start
            }
            if (isTableDelimiterCandidate(current.text)) {
                return if (isValidTableDelimiter(current.text)) null else previous.start
            }
        }

        return current.start.takeIf {
            current.text.isNotBlank() && containsUnescapedPipe(current.text)
        }
    }

    private fun findOpenFence(source: String): Fence? {
        var openFence: Fence? = null
        source.toLineSlices().forEach { line ->
            val marker = line.fenceMarker() ?: return@forEach
            val current = openFence
            if (current == null) {
                openFence = marker
            } else if (
                marker.marker == current.marker &&
                marker.length >= current.length &&
                marker.isClosing
            ) {
                openFence = null
            }
        }
        return openFence
    }

    private fun findPendingLinkStart(source: String): Int? {
        val bracketStack = ArrayDeque<Int>()
        var inlineCodeTicks = 0
        var openLinkStart: Int? = null
        var linkParenthesisDepth = 0
        var lastClosedBracketStart: Int? = null
        var lastClosedBracketEnd = -1
        var index = 0

        while (index < source.length) {
            if (source[index] == '\\') {
                index += 2
                continue
            }
            if (source[index] == '`') {
                val runLength = source.runLengthAt(index, '`')
                inlineCodeTicks = when {
                    inlineCodeTicks == 0 -> runLength
                    inlineCodeTicks == runLength -> 0
                    else -> inlineCodeTicks
                }
                index += runLength
                continue
            }
            if (inlineCodeTicks != 0) {
                index += 1
                continue
            }

            val char = source[index]
            if (openLinkStart != null) {
                when (char) {
                    '(' -> linkParenthesisDepth += 1
                    ')' -> {
                        linkParenthesisDepth -= 1
                        if (linkParenthesisDepth == 0) {
                            openLinkStart = null
                        }
                    }
                }
                index += 1
                continue
            }

            when (char) {
                '[' -> bracketStack.addLast(index)
                ']' -> if (bracketStack.isNotEmpty()) {
                    lastClosedBracketStart = bracketStack.removeLast()
                    lastClosedBracketEnd = index
                }
                '(' -> if (lastClosedBracketEnd == index - 1) {
                    openLinkStart = lastClosedBracketStart
                    linkParenthesisDepth = 1
                }
            }
            index += 1
        }

        val pendingStart = openLinkStart ?: bracketStack.lastOrNull()
        return pendingStart?.let { start ->
            if (start > 0 && source[start - 1] == '!') start - 1 else start
        }
    }

    private fun findInlineClosures(source: String): String {
        var inlineCodeTicks = 0
        val delimiterStack = ArrayDeque<String>()
        var index = 0

        while (index < source.length) {
            if (source[index] == '\\') {
                index += 2
                continue
            }

            if (source[index] == '`') {
                val runLength = source.runLengthAt(index, '`')
                inlineCodeTicks = when {
                    inlineCodeTicks == 0 -> runLength
                    inlineCodeTicks == runLength -> 0
                    else -> inlineCodeTicks
                }
                index += runLength
                continue
            }
            if (inlineCodeTicks != 0) {
                index += 1
                continue
            }

            val delimiter = source.delimiterAt(index)
            if (delimiter == null) {
                index += 1
                continue
            }

            val previous = source.getOrNull(index - 1)
            val next = source.getOrNull(index + delimiter.length)
            val canOpen = next != null && !next.isWhitespace()
            val canClose = previous != null && !previous.isWhitespace()
            val intrawordUnderscore = delimiter.contains('_') &&
                previous?.isLetterOrDigit() == true &&
                next?.isLetterOrDigit() == true

            when {
                canClose && delimiterStack.lastOrNull() == delimiter -> delimiterStack.removeLast()
                canOpen && !intrawordUnderscore -> delimiterStack.addLast(delimiter)
            }
            index += delimiter.length
        }

        return buildString {
            if (inlineCodeTicks != 0) {
                append("`".repeat(inlineCodeTicks))
            }
            delimiterStack.reversed().forEach(::append)
        }
    }

    private fun String.delimiterAt(index: Int): String? = when {
        startsWith("**", index) -> "**"
        startsWith("__", index) -> "__"
        startsWith("~~", index) -> "~~"
        this[index] == '*' -> "*"
        this[index] == '_' -> "_"
        else -> null
    }

    private fun String.runLengthAt(start: Int, char: Char): Int {
        var end = start
        while (end < length && this[end] == char) end += 1
        return end - start
    }

    private fun containsUnescapedPipe(line: String): Boolean {
        var escaped = false
        line.forEach { char ->
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '|' -> return true
            }
        }
        return false
    }

    private fun isTableDelimiterCandidate(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isNotEmpty() &&
            trimmed.all { char -> char == '|' || char == ':' || char == '-' || char.isWhitespace() }
    }

    private fun isValidTableDelimiter(line: String): Boolean {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        val cells = trimmed.split('|').map(String::trim)
        return cells.isNotEmpty() && cells.all { cell ->
            val withoutColons = cell.removePrefix(":").removeSuffix(":")
            withoutColons.length >= 3 && withoutColons.all { it == '-' }
        }
    }

    private fun String.toLineSlices(): List<LineSlice> {
        val result = mutableListOf<LineSlice>()
        var start = 0
        for (index in indices) {
            if (this[index] != '\n') continue
            val end = if (index > start && this[index - 1] == '\r') index - 1 else index
            result += LineSlice(start = start, text = substring(start, end))
            start = index + 1
        }
        result += LineSlice(start = start, text = substring(start))
        return result
    }

    private fun LineSlice.fenceMarker(): Fence? {
        var index = 0
        while (index < text.length && index < 4 && text[index] == ' ') index += 1
        if (index > 3) return null
        val marker = text.getOrNull(index)?.takeIf { it == '`' || it == '~' } ?: return null
        val runLength = text.runLengthAt(index, marker)
        if (runLength < 3) return null
        val suffix = text.substring(index + runLength)
        return Fence(
            marker = marker,
            length = runLength,
            isClosing = suffix.isBlank(),
        )
    }

    private data class LineSlice(
        val start: Int,
        val text: String,
    )

    private data class Fence(
        val marker: Char,
        val length: Int,
        val isClosing: Boolean,
    )
}


/**
 * Models like Grok pile `[[10]](<url>) [[9]](<url>)` at the start of a paragraph.
 * The link text is just digits, rendering as [10] [9] … [1], which looks bad. Strip such numbered references before display.
 */
internal object NumericCitationMarkup {
    private val CLUSTER = Regex(
        """(?:[ 	]*\[\[(\d{1,3})\]\](?:\(<[^>
]*>\)|\([^)
]*\)))+[ 	]*""",
    )

    fun strip(source: String): String {
        if (!source.contains("[[")) return source
        return CLUSTER.replace(source, "")
    }
}
