package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Path-only attachment envelope, shared by live steering, handoff and replay. */
internal object AgentSupplementMedia {
    const val MAX_JSON_CHARS = 64_000
    const val MAX_ATTACHMENTS = 8

    fun persistedImages(raw: String): List<AgentConversationCodec.PersistedImage> {
        require(raw.length <= MAX_JSON_CHARS) { "Attachment description is too large" }
        val array = JSONArray(raw)
        require(array.length() <= MAX_ATTACHMENTS) { "Too many attachments" }
        return (0 until array.length()).map { index ->
            val obj = array.optJSONObject(index)
            val source = if (obj == null) array.getString(index) else
                obj.optString("source").ifBlank { obj.optString("preview") }
            require(source.startsWith('/') && source.length <= 4096 && !source.contains('\u0000')) { "Attachments must be saved local files" }
            val video = obj?.optString("kind") == "video"
            AgentConversationCodec.PersistedImage(source,
                if (video) "video/mp4" else "image/jpeg", source.substringAfterLast('/'))
        }
    }

    fun userMessage(text: String, imagesJson: String): JSONObject =
        AgentConversationCodec.userPersistedImageMessage(text, persistedImages(imagesJson))
}
