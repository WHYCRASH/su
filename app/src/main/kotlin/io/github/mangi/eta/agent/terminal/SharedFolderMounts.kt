package io.github.mangi.eta.agent.terminal

import android.content.SharedPreferences
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** One shared-folder entry: the Android-side directory [sourcePath] appears inside the Linux environment at /workspace/mounts/[name]. */
internal data class SharedFolderMount(
    val name: String,
    val sourcePath: String,
)

/** Persistence and validation for the shared-folder configuration.
 *
 * Mounts never apply Android-globally: each Linux session bind-mounts the current configuration when its own mount namespace is created,
 * so config changes take effect for the next command/session with no global state left to unmount or clean up after reboot.
 * Configuration lives in localAgent SharedPreferences (JSON), read synchronously on the execution path.
 */
internal object SharedFolderMounts {
    const val PREFS_KEY = "linux_shared_mounts"
    const val MAX_MOUNTS = 16
    const val LINUX_MOUNTS_ROOT = "/workspace/mounts"
    const val ANDROID_MOUNTS_ROOT = "/data/local/tmp/eta/mounts"

    private val NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,48}")

    /** Mount-source exclusion zone: system-critical trees, the workspace itself (already fully visible in the environment), and /. */
    private val FORBIDDEN_ROOTS = listOf(
        "/",
        "/proc",
        "/sys",
        "/dev",
        "/system",
        "/vendor",
        "/apex",
        "/product",
        "/data/local/tmp/eta",
    )

    enum class SourceError {
        INVALID_PATH,
        FORBIDDEN_ROOT,
        DUPLICATE,
    }

    enum class NameError {
        INVALID,
        DUPLICATE,
    }

    fun current(preferences: SharedPreferences? = Prefs.localAgentPreferences()): List<SharedFolderMount> {
        val json = runCatching { preferences?.getString(PREFS_KEY, null) }.getOrNull()
        return decode(json.orEmpty())
    }

    fun save(
        mounts: List<SharedFolderMount>,
        preferences: SharedPreferences? = Prefs.localAgentPreferences(),
    ): Boolean {
        val prefs = preferences ?: return false
        return runCatching { prefs.edit().putString(PREFS_KEY, encode(mounts)).commit() }.getOrDefault(false)
    }

    /** Lexical normalization only: symlinks are not resolved, keeping the path shape the user picked (/sdcard is never rewritten). */
    fun normalizeSourcePath(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/") || '\n' in trimmed || '\r' in trimmed) return null
        val segments = mutableListOf<String>()
        trimmed.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        if (segments.isEmpty()) return null
        return "/" + segments.joinToString("/")
    }

    fun validateSource(
        sourcePath: String,
        existing: List<SharedFolderMount>,
        extraForbiddenRoots: List<String> = emptyList(),
    ): SourceError? {
        val normalized = normalizeSourcePath(sourcePath) ?: return SourceError.INVALID_PATH
        val forbidden = FORBIDDEN_ROOTS + extraForbiddenRoots.mapNotNull(::normalizeSourcePath)
        if (forbidden.any { normalized == it || normalized.startsWith("$it/") }) {
            return SourceError.FORBIDDEN_ROOT
        }
        if (existing.any { it.sourcePath == normalized }) return SourceError.DUPLICATE
        return null
    }

    fun validateName(name: String, existing: List<SharedFolderMount>): NameError? {
        val trimmed = name.trim()
        if (!NAME_PATTERN.matches(trimmed) || trimmed == "." || trimmed == "..") return NameError.INVALID
        if (existing.any { it.name == trimmed }) return NameError.DUPLICATE
        return null
    }

    /** Derive the default mount name from the source directory basename; fall back to share when stripping non-ASCII leaves nothing. */
    fun defaultName(sourcePath: String): String {
        val base = sourcePath.trimEnd('/').substringAfterLast('/').trim()
        val candidate = base.filter { char ->
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '.' || char == '_' || char == '-'
        }.take(48)
        return candidate.ifBlank { "share" }
    }

    fun encode(mounts: List<SharedFolderMount>): String {
        val array = JSONArray()
        mounts.forEach { mount ->
            array.put(
                JSONObject()
                    .put("name", mount.name)
                    .put("source", mount.sourcePath)
            )
        }
        return array.toString()
    }

    /** Skip entries that fail to decode or validate; a fully corrupt payload returns an empty list. */
    fun decode(json: String): List<SharedFolderMount> {
        if (json.isBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val mounts = mutableListOf<SharedFolderMount>()
        for (index in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            val name = item.optString("name").trim()
            val source = normalizeSourcePath(item.optString("source")) ?: continue
            if (!NAME_PATTERN.matches(name) || name == "." || name == "..") continue
            if (mounts.any { it.name == name || it.sourcePath == source }) continue
            mounts += SharedFolderMount(name = name, sourcePath = source)
        }
        return mounts.take(MAX_MOUNTS)
    }
}
