package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelFailureTest {
    @Test
    fun extractsGoogleValidationUrlFrom403() {
        val body = """{"error":{"code":403,"message":"Verify your account to continue.","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"VALIDATION_REQUIRED","metadata":{"validation_url":"https://accounts.google.com/signin/continue?sarp=1&plt=token"}}]}}"""
        val failure = AgentModelFailure.http(403, body)
        assertTrue(failure.message!!.contains("accounts.google.com/signin/continue"))
        assertTrue(failure.message!!.contains("incognito"))
        assertEquals(
            "https://accounts.google.com/signin/continue?sarp=1&plt=token",
            AgentModelFailure.extractGoogleValidationUrl(
                org.json.JSONObject(body).getJSONObject("error"),
                body,
            ),
        )
    }

    @Test
    fun classifiesHtmlContentTypeInsteadOfLeakingOkHttpMessage() {
        val failure = AgentModelFailure.transport(
            IllegalStateException("Invalid content-type: text/html; charset=utf-8"),
        )
        assertTrue(failure is AgentModelFailure)
        assertTrue(failure!!.message!!.contains("web page"))
        assertTrue(!failure.message!!.startsWith("Invalid content-type"))
        assertEquals("HTTP_200", failure.code)
    }

    @Test
    fun htmlErrorPageUsesTitle() {
        val failure = AgentModelFailure.unexpectedResponse(
            status = 502,
            contentType = "text/html; charset=utf-8",
            body = "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>",
        )
        assertTrue(failure.message!!.contains("502 Bad Gateway"))
        assertTrue(failure.message!!.contains("web page"))
        assertTrue(failure.retryable)
    }

    @Test
    fun htmlTitleCollapsesWhitespace() {
        val failure = AgentModelFailure.unexpectedResponse(
            status = 502,
            contentType = "text/html",
            body = "<html><head><title>  502\nBad   Gateway  </title></head></html>",
        )
        assertTrue(failure.message!!.contains("502 Bad Gateway"))
    }

    @Test
    fun responsesEventStreamOnChatEndpointAsksToSwitchMode() {
        val failure = AgentModelFailure.unexpectedResponse(
            status = 200,
            contentType = "text/plain",
            body = "event: response.created\ndata: {\"type\":\"response.created\"}\n\n",
        )
        assertTrue(failure.message!!.contains("Responses API"))
        assertTrue(!failure.retryable)
    }
}
