package io.github.mangi.eta.agent.browser

import io.github.mangi.eta.agent.terminal.LinuxGuestPathResolver
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Normalize address bar or navigate input into a URL the WebView can open.
 * Full URLs and bare domains go to https; Linux /workspace and MiniS paths resolve to file://; otherwise treat as a search term.
 */
internal object BrowserNavigation {
    const val SEARCH_ENDPOINT = "https://www.bing.com/search?q="

    fun normalize(
        input: String,
        resolveWorkspace: (String) -> String = { it },
    ): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "url cannot be empty" }

        localFileUrl(trimmed, resolveWorkspace)?.let { return it }
        if (trimmed.startsWith("about:") || "://" in trimmed) return trimmed
        if (looksLikeUrl(trimmed)) return "https://$trimmed"
        return SEARCH_ENDPOINT + URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
    }

    fun looksLikeUrl(value: String): Boolean {
        if (value.isEmpty() || value.any { it.isWhitespace() }) return false
        val host = value.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.equals("localhost", ignoreCase = true) || host.startsWith("localhost:")) return true
        if (IPV4.matches(host) || IPV4_PORT.matches(host)) return true
        if (host.startsWith(".") || host.endsWith(".")) return false
        val labels = host.split('.')
        return labels.size >= 2 && labels.all { it.isNotEmpty() }
    }

    private fun localFileUrl(input: String, resolveWorkspace: (String) -> String): String? {
        val guest = LinuxGuestPathResolver.guestPath(input) ?: return null
        val isWorkspace = guest == "/workspace" || guest.startsWith("/workspace/")
        val isMinis = guest == LinuxGuestPathResolver.MINIS_ROOT ||
            guest.startsWith(LinuxGuestPathResolver.MINIS_ROOT + "/")
        if (!isWorkspace && !isMinis) return null
        val host = resolveWorkspace(guest)
        val path = if (host.startsWith("/")) host else guest
        return "file://$path"
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV4_PORT = Regex("""^\d{1,3}(\.\d{1,3}){3}:\d{1,5}$""")
}
