package io.github.mangi.eta.agent.skill

/**
 * Skill runtime check shared by pre-install and pre-read paths.
 * Judges statically from SKILL.md metadata and body only; never executes scripts.
 */
object SkillCompatibilityChecker {
    fun evaluate(entry: SkillIndexEntry): SkillCompatibilityResult = evaluate(
        id = entry.id,
        name = entry.name,
        description = entry.description,
        compatibility = entry.compatibility,
        metadata = entry.metadata,
        body = "",
    )

    fun evaluate(
        id: String,
        name: String = id,
        description: String,
        compatibility: String? = null,
        metadata: Map<String, String> = emptyMap(),
        body: String = "",
    ): SkillCompatibilityResult {
        val haystack = buildString {
            append(id)
            append('\n')
            append(name)
            append('\n')
            append(compatibility.orEmpty())
            append('\n')
            append(description)
            append('\n')
            append(metadata.values.joinToString("\n"))
            append('\n')
            append(body)
        }.lowercase()
        return when {
            containsAny(haystack, APPLE_RUNTIME) ->
                SkillCompatibilityResult(available = false, reason = "Apple-only runtime is not supported")
            containsAny(haystack, MINIS_ANDROID_CLI) ->
                SkillCompatibilityResult(available = false, reason = "Requires the MiniS-only Android CLI, which su cannot run")
            containsAny(haystack, MINIS_IOS_RUNTIME) ->
                SkillCompatibilityResult(available = false, reason = "Requires the MiniS / iSH iOS sandbox, which su cannot run")
            mentionsIosOnly(haystack) ->
                SkillCompatibilityResult(available = false, reason = "This Skill is marked iOS-only")
            else -> SkillCompatibilityResult(available = true)
        }
    }

    private fun mentionsIosOnly(haystack: String): Boolean {
        if (!IOS_TOKEN.containsMatchIn(haystack)) return false
        return !containsAny(haystack, ANDROID_MARKERS)
    }

    private fun containsAny(haystack: String, needles: Array<String>): Boolean =
        needles.any { it in haystack }

    private val APPLE_RUNTIME = arrayOf(
        "apple-",
        "homekit",
        "healthkit",
        "apple health",
        "apple reminders",
        "ios shortcut",
        "icloud drive",
    )
    private val MINIS_ANDROID_CLI = arrayOf(
        "android-a11y-cli",
        "minis accessibility service",
        "bundled android-a11y-cli",
        "android-open clis",
        "/usr/local/bin/android-open",
    )
    private val MINIS_IOS_RUNTIME = arrayOf(
        "codex-on-ish",
        "inside the minis/ish",
        "ish alpine",
        "generative-ui-minis",
        "minis ios render",
        "app store connect cli",
    )
    private val ANDROID_MARKERS = arrayOf("android", "termux")
    private val IOS_TOKEN = Regex("""\bios\b|iphone|ipad|ios-only""")
}
