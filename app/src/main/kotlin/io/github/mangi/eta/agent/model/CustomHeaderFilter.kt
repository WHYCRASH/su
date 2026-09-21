package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomHeader

/**
 * Custom HTTP header security management.
 *
 * Filters out headers that would break the HTTP protocol or are managed automatically by the framework,
 * and redacts sensitive headers in logs.
 */
internal object CustomHeaderFilter {

    /**
     * Header names users must not set manually (case-insensitive).
     */
    private val FORBIDDEN_NAMES = setOf(
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "content-encoding",
        "accept-encoding",
        "expect",
        "keep-alive",
        "proxy-connection",
        "upgrade",
        "authorization",
        "x-api-key",
        "anthropic-version"
    )

    /**
     * Header names to redact in logs (case-insensitive).
     */
    private val SENSITIVE_NAMES = setOf(
        "authorization",
        "x-api-key",
        "api-key"
    )

    /**
     * Whether a header name is forbidden.
     */
    fun isForbidden(name: String): Boolean =
        name.trim().isBlank() || name.trim().lowercase() in FORBIDDEN_NAMES

    /**
     * Filters illegal headers from the list.
     */
    fun sanitize(headers: List<CustomHeader>): List<CustomHeader> =
        headers.filterNot { isForbidden(it.name) }

    fun validationError(headers: List<CustomHeader>): String? {
        val names = mutableSetOf<String>()
        headers.forEachIndexed { index, header ->
            val name = header.name.trim()
            val prefix = "Header ${index + 1}"
            if (isForbidden(name)) return "${prefix}: name is empty or managed by the system"
            if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) {
                return "${prefix}: name contains invalid characters"
            }
            if (header.value.any { it != '\t' && it !in ' '..'~' }) {
                return "${prefix}: value must contain only printable ASCII characters or tabs"
            }
            if (!names.add(name.lowercase())) return "${prefix}: duplicate name (case-insensitive)"
        }
        return null
    }

    /**
     * Merges custom headers into the OkHttp headers builder; later entries overwrite earlier ones with the same name.
     */
    fun mergeInto(
        builder: okhttp3.Headers.Builder,
        headers: List<CustomHeader>
    ) {
        sanitize(headers).forEach { header ->
            builder.set(header.name.trim(), header.value)
        }
    }

    /**
     * Redacts sensitive headers for log output.
     */
    fun redactForLog(headers: List<CustomHeader>): List<Pair<String, String>> =
        sanitize(headers).map { header ->
            val nameLower = header.name.lowercase()
            val value = if (nameLower in SENSITIVE_NAMES) {
                "***"
            } else {
                header.value
            }
            header.name to value
        }
}
