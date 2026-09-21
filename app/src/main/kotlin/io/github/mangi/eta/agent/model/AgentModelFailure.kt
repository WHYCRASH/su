package io.github.mangi.eta.agent.model

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Provider boundary only classifies failures; retry budget and context are owned by the Loop. */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
    val diagnostic: String = "",
) : IllegalStateException(message, cause) {
    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
        )

        fun http(
            status: Int,
            body: String,
            headers: okhttp3.Headers? = null,
            secrets: List<String> = emptyList(),
        ): AgentModelFailure {
            val diagnostic = AgentHttpFailureDiagnostics.collect(status, body, headers, secrets)
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            if (status in setOf(400, 413) && isContextOverflow(error)) {
                return AgentModelFailure("CONTEXT_WINDOW_EXCEEDED", false, "Provider reported context overflow; shrink the context and retry.", diagnostic = diagnostic)
            }
            val permanent = isPermanent(error, body)
            val providerMessage = error?.optString("message")
                ?.replace('\n', ' ')
                ?.replace('\r', ' ')
                ?.trim()
                .orEmpty()
            val safeProviderMessage = AgentHttpFailureDiagnostics.safe(providerMessage, secrets, 400)
            val retryAfter = headers?.get("Retry-After")?.let { AgentHttpFailureDiagnostics.safe(it, secrets, 100) }
            val validationUrl = extractGoogleValidationUrl(error, body)
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                message = if (permanent) "Model quota or billing is limited (HTTP $status); check the provider account." +
                    safeProviderMessage.takeIf { it.isNotBlank() }?.let { " Server: $it" }.orEmpty()
                else when (status) {
                    400 -> {
                        val detail = safeProviderMessage.takeIf { it.isNotBlank() }
                            ?: body.replace('\n', ' ').replace('\r', ' ').trim().take(400)
                                .takeIf { it.isNotBlank() }
                        if (detail != null) "Invalid model request parameters (HTTP 400): $detail"
                        else "Invalid model request parameters (HTTP 400); check the model configuration."
                    }
                    401 -> {
                        val detail = safeProviderMessage.takeIf { it.isNotBlank() }
                        if (detail != null && detail.contains("verify", true))
                            "Google requires verification for this account (HTTP 401): $detail"
                        else "Model authentication failed (HTTP 401). For OAuth sign in again; for API keys check the key."
                    }
                    403 -> {
                        val detail = safeProviderMessage.takeIf { it.isNotBlank() }
                            ?: body.replace('\n', ' ').replace('\r', ' ').trim().take(400)
                                .takeIf { it.isNotBlank() }
                        val verify = validationUrl != null || (
                            detail != null && detail.contains("verify your account", ignoreCase = true)
                        )
                        when {
                            validationUrl != null ->
                                "Google requires verification for this account (HTTP 403). Open this link in an incognito window: $validationUrl sign in to the flagged account to complete verification, then send your message. The homepage usually does not show the verification."
                            verify -> "Google requires verification for this account first (HTTP 403). Open the validation_url from the API response in an incognito window to complete verification."
                            detail != null -> "Model access denied (HTTP 403): $detail"
                            else -> "Model access denied (HTTP 403); check account and model permissions."
                        }
                    }
                    404 -> "Model endpoint or model not found (HTTP 404); check the endpoint URL and model name."
                    429 -> "Model rate-limited temporarily (HTTP 429)." +
                        safeProviderMessage.takeIf { it.isNotBlank() }?.let { " Server: $it" }.orEmpty() +
                        retryAfter?.let { " Retry-After: $it" }.orEmpty()
                    else -> {
                        val title = htmlTitle(body)
                        if (title != null || body.contains("<html", ignoreCase = true) ||
                            body.contains("<!DOCTYPE", ignoreCase = true)
                        ) {
                            "Model endpoint returned an error page (HTTP $status${title?.let { ": $it" } ?: ""})"
                        } else {
                            "Model endpoint returned HTTP $status"
                        }
                    }
                },
                diagnostic = diagnostic,
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            // A stream may already have emitted visible text or invoked hosted tools.
            // Do not turn its late error into a replay of possible side effects.
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "Model request timed out (connect/write timeout, or waited over ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} minutes for a response).",
            )
            is SSLException, is ProtocolException -> null
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "Model connection dropped or temporarily unavailable; check the network and provider status.", failure,
            )
            else -> unexpectedMediaType(failure)
        }

        fun unexpectedResponse(
            status: Int?,
            contentType: String?,
            body: String,
            cause: Throwable? = null,
        ): AgentModelFailure {
            val trimmed = body.trim()
            val type = contentType.orEmpty()
            if (trimmed.startsWith("{")) {
                return http(status ?: 200, trimmed)
            }
            if (trimmed.contains("event: response.") || trimmed.contains("response.created")) {
                return AgentModelFailure(
                    "HTTP_${status ?: 200}",
                    false,
                    "Endpoint returned a Responses API event stream. Switch this provider's endpoint mode to Responses API.",
                    cause,
                )
            }
            val html = type.contains("html", ignoreCase = true) ||
                trimmed.startsWith("<!doctype", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true)
            val title = htmlTitle(trimmed)
            val statusLabel = status?.let { "HTTP $it" } ?: type.ifBlank { "unknown type" }
            val detail = title
                ?: trimmed.replace('\n', ' ').replace('\r', ' ').trim().take(120).ifBlank { null }
            return AgentModelFailure(
                code = "HTTP_${status ?: 200}",
                retryable = status == null || status in transientStatus,
                message = if (html) {
                    "Model endpoint returned a web page instead of a data stream ($statusLabel${detail?.let { ": $it" } ?: ""}). Chat Completions may be intercepted by a proxy; switch to Responses API or check the tunnel/upstream."
                } else {
                    "Model endpoint returned an unparseable response ($statusLabel)${detail?.let { ": $it" } ?: ""}"
                },
                cause = cause,
            )
        }

        private fun unexpectedMediaType(failure: Exception): AgentModelFailure? {
            val message = failure.message.orEmpty()
            if (!message.startsWith("Invalid content-type")) return null
            val contentType = message.substringAfter("Invalid content-type:", "").trim().ifBlank { null }
            return unexpectedResponse(status = null, contentType = contentType, body = "", cause = failure)
        }

        private fun htmlTitle(body: String): String? {
            val start = body.indexOf("<title", ignoreCase = true).takeIf { it >= 0 } ?: return null
            val openEnd = body.indexOf('>', start).takeIf { it >= 0 } ?: return null
            val close = body.indexOf("</title>", openEnd + 1, ignoreCase = true).takeIf { it >= 0 } ?: return null
            val title = body.substring(openEnd + 1, close)
            val collapsed = buildString(title.length) {
                var gap = false
                title.forEach { ch ->
                    if (ch.isWhitespace()) {
                        if (!gap) {
                            append(' ')
                            gap = true
                        }
                    } else {
                        append(ch)
                        gap = false
                    }
                }
            }.trim()
            return collapsed.take(80).ifBlank { null }
        }

        internal fun extractGoogleValidationUrl(error: JSONObject?, body: String): String? {
            val details = error?.optJSONArray("details")
            if (details != null) {
                for (index in 0 until details.length()) {
                    val item = details.optJSONObject(index) ?: continue
                    val url = item.optJSONObject("metadata")?.optString("validation_url")
                        ?.takeIf { it.isNotBlank() }
                    if (url != null && (
                            item.optString("reason").equals("VALIDATION_REQUIRED", true) ||
                                url.startsWith("https://accounts.google.com/")
                            )
                    ) {
                        return url
                    }
                }
            }
            val match = Regex("""https://accounts\.google\.com/signin/continue[^"\\\s]+""").find(body)
            return match?.value
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            val code = error.optString("code").lowercase()
            val type = error.optString("type").lowercase()
            if (code in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long") ||
                type in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long")) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("context window exceeded")
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
