package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentSummaryContentTest {
    @Test fun escapedLargeJpegIsNotCountedAsTextAndSourceIsUnchanged() {
        val image = "data:image/jpeg;base64,/9" + "a/b+".repeat(160_000)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "keep checking the screenshot"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image)))
        val source = content.toString().replace("\\/", "/").replace("/", "\\/")
        val projected = AgentSummaryContent.project(source)
        assertTrue(projected.contains("keep checking the screenshot"))
        assertTrue(projected.contains("original retained"))
        assertTrue(projected.length < 300)
        assertFalse(projected.contains("base64"))
        assertEquals(image, JSONArray(source).getJSONObject(1).getJSONObject("image_url").getString("url"))
        val plain = content.toString().replace("\\/", "/")
        assertEquals(projected, AgentSummaryContent.project(plain))
    }

    @Test fun nestedToolMediaIsProjectedButTextAndIdentitySurvive() {
        val source = """[{"type":"tool-result","id":"call-a","content":[{"type":"text","text":"completed the first step"},{"type":"image","source":{"type":"base64","data":"large-private-image"}},{"type":"input_audio","data":"audio"}]}]"""
        val output = JSONArray(AgentSummaryContent.project(source)).getJSONObject(0)
        assertEquals("call-a", output.getString("id"))
        assertEquals("completed the first step", output.getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(output.toString().contains("large-private-image"))
        assertTrue(source.contains("large-private-image"))
    }

    @Test fun unknownFieldsArePreservedButDecodedInlineDataUrisAreNotCopied() {
        val source = JSONObject().put("type", "custom").put("caption", "evidence")
            .put("payload", "data:image/png;base64,a/b+").toString()
        val output = JSONObject(AgentSummaryContent.project(source))
        assertEquals("evidence", output.getString("caption"))
        assertFalse(output.toString().contains("base64"))
    }
}
