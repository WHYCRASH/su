package io.github.mangi.eta.agent.voice.tts

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Extract visible prose through the same GFM parser as the chat renderer, not punctuation stripping. */
internal object SpeechSpeakableText {
    const val MAX_SENTENCE_CHARS = 400
    const val MAX_SOURCE_CHARS = 100_000
    const val MIN_NEWLINE_SENTENCE_CHARS = 24

    fun speakable(markdown: String): String {
        require(markdown.length <= MAX_SOURCE_CHARS) { "Reply is too long; read it in segments" }
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
        val out = StringBuilder()
        fun visit(node: ASTNode) {
            // JetBrains debug names may include the "Markdown:" namespace.
            val type = node.type.toString().substringAfterLast(':')
            when (type) {
                "CODE_FENCE", "CODE_BLOCK", "IMAGE", "LINK_DEFINITION", "HTML_BLOCK", "HTML_TAG" -> out.append(' ')
                "INLINE_LINK", "FULL_REFERENCE_LINK", "SHORT_REFERENCE_LINK" -> {
                    node.children.firstOrNull { it.type.toString().substringAfterLast(':') == "LINK_TEXT" }?.let(::visit)
                }
                "LINK_DESTINATION", "LINK_TITLE", "AUTOLINK", "GFM_AUTOLINK" -> Unit
                "CODE_SPAN" -> out.append(markdown.substring(node.startOffset, node.endOffset).trim('`').replace('\n', ' '))
                else -> if (node.children.isNotEmpty()) {
                    node.children.forEach { child ->
                        val raw = markdown.substring(child.startOffset, child.endOffset)
                        val delimiter = child.children.isEmpty() && (
                            (type in setOf("EMPH", "STRONG", "STRIKETHROUGH") && raw.all { it in "*_~" }) ||
                                (type == "LINK_TEXT" && raw in setOf("[", "]"))
                            )
                        if (!delimiter) visit(child)
                    }
                    if (type in setOf("PARAGRAPH", "ATX_1", "ATX_2", "ATX_3", "ATX_4", "ATX_5", "ATX_6", "SETEXT_1", "SETEXT_2", "LIST_ITEM", "TABLE_ROW", "ROW", "HEADER")) out.append('\n')
                } else {
                    if (type !in setOf("ATX_HEADER", "ATX_CONTENT_START", "LIST_BULLET", "LIST_NUMBER", "BLOCK_QUOTE", "TABLE_SEPARATOR", "TABLE_DELIMITER", "HORIZONTAL_RULE", "SETEXT_1", "SETEXT_2")) {
                        out.append(markdown, node.startOffset, node.endOffset)
                    }
                }
            }
        }
        visit(root)
        return out.toString().replace(Regex("[ \\t\\r]+"), " ").replace(Regex("\\n\\s*\\n"), "\n").trim()
    }

    /** maxChars counts Unicode code points: never split a surrogate pair. */
    fun committedSentences(markdown: String, finalized: Boolean): List<String> {
        val parts = sentences(markdown, mergeLeading = false)
        if (finalized || parts.isEmpty()) return parts
        val text = speakable(markdown).trimEnd()
        if (text.isEmpty()) return emptyList()
        val last = text.codePointBefore(text.length)
        val committed = last == '\n'.code ||
            last.toChar() in "。！？；!?" ||
            last == '.'.code
        return if (committed) parts else parts.dropLast(1)
    }

    fun sentences(markdown: String, maxChars: Int = MAX_SENTENCE_CHARS, mergeLeading: Boolean = true): List<String> {
        require(maxChars in 1..2_000)
        val text = speakable(markdown)
        val result = mutableListOf<String>()
        var start = 0
        var cursor = 0
        var count = 0
        while (cursor < text.length) {
            val cp = text.codePointAt(cursor)
            cursor += Character.charCount(cp)
            count++
            val newline = cp == '\n'.code
            val punct = cp.toChar() in "。！？；!?" ||
                (cp == '.'.code && (cursor == text.length || text[cursor].isWhitespace()))
            // Short heading lines would otherwise become the first utterance and get dropped by some engines.
            val boundary = punct || (newline && count >= MIN_NEWLINE_SENTENCE_CHARS)
            if (count >= maxChars || boundary || cursor == text.length) {
                text.substring(start, cursor).trim().takeIf(String::isNotEmpty)?.let(result::add)
                start = cursor
                count = 0
            }
        }
        return if (!mergeLeading || maxChars < MIN_NEWLINE_SENTENCE_CHARS) result else mergeShortLeading(result)
    }

    private fun mergeShortLeading(parts: List<String>): List<String> {
        if (parts.size < 2) return parts
        val out = parts.toMutableList()
        while (out.size >= 2 && out[0].count { !it.isWhitespace() } < MIN_NEWLINE_SENTENCE_CHARS) {
            out[1] = out[0] + " " + out[1]
            out.removeAt(0)
        }
        return out
    }
}
