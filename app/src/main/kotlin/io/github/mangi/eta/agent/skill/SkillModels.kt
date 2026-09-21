package io.github.mangi.eta.agent.skill

import androidx.compose.runtime.Immutable

/**
 * Skill index entry — metadata for one [SKILL.md] file on disk.
 */
@Immutable
data class SkillIndexEntry(
    val id: String,
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val rootPath: String,
    val skillFilePath: String,
    val hasScripts: Boolean,
    val hasReferences: Boolean,
    val hasAssets: Boolean,
    val hasEvals: Boolean,
    val enabled: Boolean = true,
    val source: String = "user",
    val installed: Boolean = true,
)

/**
 * Resolved skill context — the SKILL.md body plus its companion directory paths.
 */
@Immutable
data class ResolvedSkillContext(
    val skillId: String,
    val frontmatter: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
    val bodyMarkdown: String,
    val loadedReferences: List<String> = emptyList(),
    val scriptsDir: String? = null,
    val assetsDir: String? = null,
    val triggerReason: String,
)

@Immutable
data class SkillCompatibilityResult(
    val available: Boolean,
    val reason: String? = null,
)

/**
 * Skill contexts resolved during a single Agent run.
 */
@Immutable
data class SkillContext(
    val installedSkills: List<SkillIndexEntry> = emptyList(),
) {
    companion object {
        val EMPTY = SkillContext()
    }
}

/** SKILL.md file parse result. */
internal data class ParsedSkillFile(
    val frontmatter: Map<String, String>,
    val body: String,
)
