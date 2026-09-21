package io.github.mangi.eta.agent.browser.ported

import io.github.mangi.eta.agent.browser.BrowserPayloadLimiter
import io.github.mangi.eta.agent.browser.ported.browser.BrowserAction
import io.github.mangi.eta.agent.browser.ported.browser.BrowserActionInput
import io.github.mangi.eta.agent.model.AgentBrowserToolCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserPortCompatibilityTest {
    @Test fun toolCatalogAndPortedActionsStayInSync() {
        val tools = JSONArray()
        AgentBrowserToolCatalog.appendTo(tools)
        val properties = tools.getJSONObject(0).getJSONObject("function")
            .getJSONObject("parameters").getJSONObject("properties")
        val actions = properties.getJSONObject("action").getJSONArray("enum")
        for (index in 0 until actions.length()) {
            assertNotNull(actions.getString(index), BrowserAction.fromString(actions.getString(index)))
        }
        assertTrue(properties.has("tab_id"))
    }

    @Test fun paginationAndTimeoutAliasesRemainCompatible() {
        val input = requireNotNull(BrowserActionInput.parse("""{"action":"get_readable","tab_id":2,"offset":42,"max_chars":6000,"timeout_ms":1250}"""))
        assertEquals(2, input.tabId)
        assertEquals(42, input.offset)
        assertEquals(6000, input.maxChars)
        assertEquals(1250, input.timeoutMs)
        assertEquals(900, BrowserActionInput.parse("""{"action":"wait_for_selector","timeout":900}""")?.timeoutMs)
    }

    @Test fun coordinatesAndSubmitSurviveParsing() {
        val input = requireNotNull(BrowserActionInput.parse("""{"action":"type","coordinate_x":20,"coordinate_y":40,"text":"Test","submit":true}"""))
        assertEquals(20, input.coordinateX)
        assertEquals(40, input.coordinateY)
        assertTrue(input.submit)
        assertEquals("Test", input.text)
    }

    @Test fun cookieDefaultsDoNotLoseEtaFuzzyMatching() {
        assertTrue(requireNotNull(BrowserActionInput.parse("""{"action":"get_cookies"}""")).fuzzy)
        assertFalse(requireNotNull(BrowserActionInput.parse("""{"action":"get_cookies","fuzzy":false}""")).fuzzy)
    }

    @Test fun allOldAndNewNavigationActionsAreRecognized() {
        listOf("go_back", "go_forward", "reload", "wait_for_selector", "new_tab", "close_tab", "list_tabs").forEach {
            assertNotNull(it, BrowserActionInput.parse(JSONObject().put("action", it).toString()))
        }
        assertNull(BrowserActionInput.parse("""{"action":"unknown"}"""))
    }

    @Test fun payloadTruncationRetainsVerifiedTabId() {
        val input = JSONObject().put("ok", true).put("tab_id", 2).put("huge", "x".repeat(10000))
        val limited = JSONObject(BrowserPayloadLimiter.serialize(input, 512))
        assertEquals(2, limited.getInt("tab_id"))
        assertTrue(limited.getBoolean("payload_truncated"))
    }
}
