package io.github.mangi.eta.agent.device

/** Extracts the exact foreground package from dumpsys window, so `contains` cannot mistake similar package names for the same app. */
internal object FocusedWindowParser {
    data class Result(
        val packageName: String,
        val component: String,
        val rawLine: String,
    )

    fun parse(output: String): Result? {
        val lines = output.lineSequence().map(String::trim).toList()
        val candidates = sequenceOf("mCurrentFocus=", "mFocusedApp=")
            .flatMap { marker -> lines.asSequence().filter { line -> marker in line } }
        for (line in candidates) {
            if (line.substringAfter('=', "").trim().startsWith("null")) continue
            val component = COMPONENT.find(line)?.value ?: continue
            return Result(
                packageName = component.substringBefore('/'),
                component = component,
                rawLine = line,
            )
        }
        return null
    }

    private val COMPONENT = Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+""")
}
