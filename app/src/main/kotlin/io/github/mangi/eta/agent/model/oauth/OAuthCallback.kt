package io.github.mangi.eta.agent.model.oauth

import android.net.Uri
import java.net.URI
import java.net.URLDecoder

internal object OAuthCallback {
    fun isRedirect(uri: Uri): Boolean = isRedirectUrl(uri.toString())

    fun parse(uri: Uri): Pair<String, String?> = parseUrlOrThrow(uri.toString())

    fun parseUrl(url: String): Pair<String, String?>? {
        if (!isRedirectUrl(url)) return null
        return parseUrlOrThrow(url)
    }

    fun isRedirectUrl(url: String): Boolean {
        val parsed = parseComponents(url) ?: return false
        val host = parsed.host.removePrefix("[").removeSuffix("]")
        if (host != "localhost" && host != "127.0.0.1" && host != "::1") return false
        return parsed.path.contains("callback")
    }

    fun parseUrlOrThrow(url: String): Pair<String, String?> {
        val parsed = parseComponents(url) ?: error("Invalid login callback URL")
        val error = parsed.query["error"]
        if (!error.isNullOrBlank()) {
            val description = parsed.query["error_description"]?.takeIf { it.isNotBlank() }
            error(description ?: error)
        }
        val code = parsed.query["code"]?.takeIf { it.isNotBlank() }
            ?: error("Login callback is missing the authorization code")
        return code to parsed.query["state"]
    }

    fun hostOf(url: String): String? = parseComponents(url)?.host?.takeIf { it.isNotBlank() }

    private data class Components(
        val host: String,
        val path: String,
        val query: Map<String, String>,
    )

    private fun parseComponents(raw: String): Components? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().trim().lowercase()
        val path = uri.path.orEmpty()
        val query = uri.rawQuery.orEmpty()
            .split("&")
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val kv = pair.split("=", limit = 2)
                val key = decode(kv[0])
                if (key.isBlank()) return@mapNotNull null
                val value = if (kv.size > 1) decode(kv[1]) else ""
                key to value
            }
            .toMap()
        return Components(host = host, path = path, query = query)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)
}
