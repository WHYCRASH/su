package io.github.mangi.eta.agent.media

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHistoryImageHydratorTest {
    @Test
    fun listsMissingVideoPathForNonVideoModels() {
        val path = "/cache/missing-clip.mp4"
        val message = AgentModelClient.ConversationMessage(
            role = "user",
            content = "hello",
            contentJson = JSONArray().put(
                JSONObject()
                    .put("type", AgentHistoryImageHydrator.TYPE_VIDEO_FILE)
                    .put("path", path)
                    .put("mime", "video/mp4"),
            ).toString(),
        )
        val hydrated = AgentHistoryImageHydrator.hydrate(
            message = message,
            supportsVision = true,
            supportsVideo = false,
        )
        assertTrue(hydrated.content.contains("[User video] $path"))
        assertFalse(hydrated.contentJson.contains("video_url"))
    }
}
