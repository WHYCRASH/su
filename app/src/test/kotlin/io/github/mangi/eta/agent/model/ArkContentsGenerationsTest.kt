package io.github.mangi.eta.agent.model

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArkContentsGenerationsTest {
    @Test
    fun codingChatBaseUrlMapsToContentsTasks() {
        val base = "https://ark.cn-beijing.volces.com/api/coding/v3"
        assertTrue(ArkContentsGenerations.matches(base))
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks",
            ArkContentsGenerations.tasksUrl(base),
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks/cgt-1",
            ArkContentsGenerations.taskUrl(base, "cgt-1"),
        )
        assertFalse(ArkContentsGenerations.matches("https://example.com/v1"))
    }

    @Test
    fun createBodyUsesContentArray() {
        val png = byteArrayOf(1, 2, 3, 4)
        val json = JSONObject(
            ArkContentsGenerations.createBody(
                model = "doubao-seedance-2-0-260128",
                prompt = "A cat is running",
                images = listOf(AgentVideoGenerationClient.InputImage(png, "image/png")),
            ),
        )
        assertEquals("doubao-seedance-2-0-260128", json.getString("model"))
        val content = json.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("A cat is running", content.getJSONObject(0).getString("text"))
        val image = content.getJSONObject(1)
        assertEquals("image_url", image.getString("type"))
        val url = image.getJSONObject("image_url").getString("url")
        assertTrue(url.startsWith("data:image/png;base64,"))
        assertEquals(
            png.toList(),
            Base64.getDecoder().decode(url.substringAfter("base64,")).toList(),
        )
        assertFalse(json.has("prompt"))
        assertFalse(json.has("messages"))
    }
}
