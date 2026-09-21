package io.github.mangi.eta.agent.skill

import java.io.File

/**
 * SKILL.md parser—extracts structured information from YAML frontmatter + Markdown body.
 *
 * Supports `>` / `|` multiline blocks, indented sub-blocks, and regular `key: value` lines.
 * Pure string processing; does not depend on an external YAML library.
 */
internal object SkillParser {

    /**
     * Reads and parses [skillFile], returning a frontmatter map + body string.
     * Returns null if the file does not exist or is not a file.
     */
    fun parseSkillFile(skillFile: File): ParsedSkillFile? {
        if (!skillFile.exists() || !skillFile.isFile) return null
        val raw = skillFile.readText()
        if (!raw.startsWith("---")) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }
        val markerIndex = raw.indexOf("\n---", startIndex = 3)
        if (markerIndex <= 0) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }
        val frontmatterText = raw.substring(3, markerIndex).trim('\n', '\r')
        val body = raw.substring(markerIndex + 4).trim()
        return ParsedSkillFile(
            frontmatter = parseSimpleFrontmatter(frontmatterText),
            body = body,
        )
    }

    /**
     * Simple YAML frontmatter parsing.
     *
     * Supports:
     * - `key: value` single line
     * - `key: >` folded multiline block
     * - `key: |` literal multiline block
     * - `key:` followed by an indented sub-block
     */
    fun parseSimpleFrontmatter(frontmatter: String): Map<String, String> {
        if (frontmatter.isBlank()) return emptyMap()
        val lines = frontmatter.lines()
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            if (rawLine.isBlank()) {
                index += 1
                continue
            }
            val keyMatch = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(rawLine)
            if (keyMatch == null) {
                index += 1
                continue
            }
            val key = keyMatch.groupValues[1]
            val value = keyMatch.groupValues[2]
            if (YAML_BLOCK_SCALAR.matches(value)) {
                val literal = value.startsWith('|')
                val blockLines = mutableListOf<String>()
                index += 1
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].isBlank())) {
                    val next = lines[index]
                    blockLines += if (next.isBlank()) "" else next.trim()
                    index += 1
                }
                result[key] = if (literal) {
                    blockLines.joinToString("\n").trim()
                } else {
                    foldYamlLines(blockLines)
                }
                continue
            }
            if (value.isBlank()) {
                val builder = StringBuilder()
                index += 1
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].startsWith("\t"))) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(lines[index].trimEnd())
                    index += 1
                }
                result[key] = builder.toString().trim()
                continue
            }
            result[key] = unquoteScalar(value)
            index += 1
        }
        return result
    }

    /** Supports common single- and double-quoted YAML scalars; complex escapes are still left to Skill authors to avoid. */
    private fun unquoteScalar(raw: String): String {
        val value = raw.trim()
        if (value.length < 2) return value
        val quoted = (value.first() == '"' && value.last() == '"') ||
            (value.first() == '\'' && value.last() == '\'')
        return if (quoted) value.substring(1, value.lastIndex) else value
    }

    /** `>` folds newlines and preserves paragraphs formed by blank lines; trailing chomp makes no semantic difference for metadata. */
    private fun foldYamlLines(lines: List<String>): String = buildString {
        var pendingBlankLines = 0
        lines.forEach { line ->
            if (line.isBlank()) {
                pendingBlankLines += 1
            } else {
                if (isNotEmpty()) {
                    if (pendingBlankLines == 0) append(' ')
                    else repeat(pendingBlankLines + 1) { append('\n') }
                }
                append(line)
                pendingBlankLines = 0
            }
        }
    }.trim()

    /**
     * Parses indented sub-blocks into a key-value map (used for the metadata field).
     */
    fun parseIndentedBlock(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val match = Regex("^\\s*([A-Za-z0-9_.-]+):\\s*(.*)$").find(line) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2].trim().trim('"')
        }.toMap()
    }

    /**
     * Generates a normalized skill id from the directory name and frontmatter name.
     */
    fun sanitizeSkillId(directoryName: String, frontmatterName: String?): String {
        val candidate = frontmatterName?.trim().takeUnless { it.isNullOrBlank() } ?: directoryName
        return candidate.lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { directoryName.lowercase() }
    }

    /**
     * Normalizes a lookup string—used for id/name/path matching.
     */
    fun normalizeSkillLookup(value: String): String =
        value.trim()
            .lowercase()
            .replace('\\', '/')
            .removeSuffix("/skill.md")
            .removeSuffix("/")
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("_", "")

    private val YAML_BLOCK_SCALAR = Regex("[>|][+-]?")

}
