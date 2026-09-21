package io.github.mangi.eta.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationTest {
    @Test
    fun searchTermsBecomeSearchUrls() {
        val url = BrowserNavigation.normalize("Today's Tech News")
        assertTrue(url.startsWith(BrowserNavigation.SEARCH_ENDPOINT))
        assertTrue(url.contains("q="))
        assertTrue(BrowserNavigation.normalize("open minis").startsWith(BrowserNavigation.SEARCH_ENDPOINT))
    }

    @Test
    fun bareDomainsGetHttps() {
        assertEquals("https://github.com", BrowserNavigation.normalize("github.com"))
        assertEquals("https://www.bing.com/search?q=x", BrowserNavigation.normalize("https://www.bing.com/search?q=x"))
    }

    @Test
    fun workspacePathsBecomeFileUrls() {
        assertEquals(
            "file:///data/local/tmp/eta/index.html",
            BrowserNavigation.normalize("/workspace/index.html") { "/data/local/tmp/eta/index.html" },
        )
        assertEquals(
            "file:///data/local/tmp/eta/index.html",
            BrowserNavigation.normalize("file:///workspace/index.html") { "/data/local/tmp/eta/index.html" },
        )
    }

    @Test
    fun minisPathsBecomeFileUrls() {
        assertEquals(
            "file:///data/local/tmp/eta/index.html",
            BrowserNavigation.normalize("minis://workspace/index.html") { "/data/local/tmp/eta/index.html" },
        )
        assertEquals(
            "file:///data/local/tmp/eta/offloads/env.sh",
            BrowserNavigation.normalize("/var/minis/offloads/env.sh") { "/data/local/tmp/eta/offloads/env.sh" },
        )
    }

    @Test
    fun looksLikeUrlRejectsSearchPhrases() {
        assertTrue(BrowserNavigation.looksLikeUrl("github.com/OpenMinis"))
        assertTrue(BrowserNavigation.looksLikeUrl("127.0.0.1:8080"))
        assertFalse(BrowserNavigation.looksLikeUrl("Today's News"))
        assertFalse(BrowserNavigation.looksLikeUrl("hello world"))
    }
}
