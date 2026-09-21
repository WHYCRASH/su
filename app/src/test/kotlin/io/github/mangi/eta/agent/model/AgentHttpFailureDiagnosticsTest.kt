package io.github.mangi.eta.agent.model

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentHttpFailureDiagnosticsTest {
    @Test fun googleQuotaAndRetryDetailsSurviveWithoutCredentialsOrRequestBody() {
        val body = JSONObject().put("error", JSONObject()
            .put("code", 429).put("status", "RESOURCE_EXHAUSTED")
            .put("message", "Model busy; Bearer secret-value; user@example.com")
            .put("request", "private conversation text")
            .put("details", JSONArray()
                .put(JSONObject().put("@type", "type.googleapis.com/google.rpc.ErrorInfo")
                    .put("reason", "RATE_LIMIT_EXCEEDED").put("domain", "googleapis.com")
                    .put("metadata", JSONObject().put("quota_metric", "requests_per_minute")
                        .put("access_token", "hidden-token").put("consumer", "private-project")))
                .put(JSONObject().put("@type", "type.googleapis.com/google.rpc.QuotaFailure")
                    .put("violations", JSONArray().put(JSONObject().put("description", "Request rate limit")
                        .put("subject", "private-account").put("quotaId", "rpm"))))
                .put(JSONObject().put("@type", "type.googleapis.com/google.rpc.RetryInfo").put("retryDelay", "45s"))))
        val headers = Headers.Builder().add("Retry-After", "45").add("x-request-id", "request-123")
            .add("Set-Cookie", "private-cookie").build()
        val failure = AgentModelFailure.http(429, body.toString(), headers, listOf("secret-value"))
        for (expected in listOf("RESOURCE_EXHAUSTED", "RATE_LIMIT_EXCEEDED", "requests_per_minute", "rpm", "45s", "request-123")) {
            assertTrue(expected, failure.diagnostic.contains(expected))
        }
        for (secret in listOf("secret-value", "hidden-token", "private-project", "private-account", "private-cookie", "private conversation text", "user@example.com")) {
            assertFalse(secret, failure.diagnostic.contains(secret))
        }
        assertTrue(failure.message!!.contains("Model busy"))
        assertTrue(failure.message!!.contains("Retry-After: 45"))
        assertFalse(failure.message!!.contains("secret-value"))
    }

    @Test fun emptyOrNonJsonBodyStillPreservesHttpAndRequestId() {
        val result = AgentHttpFailureDiagnostics.collect(429, "<html>private page</html>",
            Headers.Builder().add("x-goog-request-id", "r1").build(), emptyList())
        assertTrue(result.contains("http=429"))
        assertTrue(result.contains("r1"))
        assertFalse(result.contains("private page"))
    }

    @Test fun diagnosticsAreBoundedAndSingleLine() {
        val result = AgentHttpFailureDiagnostics.collect(429,
            JSONObject().put("error", JSONObject().put("message", "x\n".repeat(10000))).toString(), null, emptyList())
        assertTrue(result.length <= 4000)
        assertFalse(result.contains('\n'))
        assertEquals("url=https://example.com/path", AgentHttpFailureDiagnostics.safe("url=https://example.com/path?token=secret"))
    }
}
