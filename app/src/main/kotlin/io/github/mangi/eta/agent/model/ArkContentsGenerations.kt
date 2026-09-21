package io.github.mangi.eta.agent.model

import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Volcengine Ark Seedance: content generation task, not OpenAI /videos. */
internal object ArkContentsGenerations {
    fun matches(baseUrl: String): Boolean {
        val host = baseUrl.trim().toHttpUrlOrNull()?.host.orEmpty().lowercase()
        return host.endsWith(".volces.com") ||
            host == "volces.com" ||
            host.endsWith(".volcengine.com") ||
            host == "volcengine.com" ||
            host.startsWith("ark.")
    }

    fun apiV3Root(baseUrl: String): String? {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
        if (!matches(baseUrl)) return null
        return "${url.scheme}://${url.host}/api/v3"
    }

    fun tasksUrl(baseUrl: String): String? =
        apiV3Root(baseUrl)?.let { "$it/contents/generations/tasks" }

    fun taskUrl(baseUrl: String, taskId: String): String? {
        val id = taskId.trim().trim('/')
        if (id.isEmpty()) return null
        return tasksUrl(baseUrl)?.let { "$it/$id" }
    }

    fun createBody(
        model: String,
        prompt: String,
        images: List<AgentVideoGenerationClient.InputImage>,
    ): String {
        val content = JSONArray().put(
            JSONObject().put("type", "text").put("text", prompt),
        )
        images.filter { it.bytes.isNotEmpty() }.forEach { image ->
            val mime = image.mimeType.ifBlank { "image/png" }
            val dataUrl = "data:$mime;base64," +
                Base64.getEncoder().encodeToString(image.bytes)
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl)),
            )
        }
        return JSONObject()
            .put("model", model)
            .put("content", content)
            .toString()
    }
}
