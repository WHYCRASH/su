package io.github.mangi.eta.agent.media

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Restore persisted image_file / video_file to visual input before sending to the model, or keep only the file path; a text-only model cannot gain vision capability via read_image. */
internal object AgentHistoryImageHydrator {
    const val TYPE_IMAGE_FILE = AgentConversationCodec.IMAGE_FILE_TYPE
    const val TYPE_VIDEO_FILE = AgentConversationCodec.VIDEO_FILE_TYPE

    fun hydrateAll(
        history: List<AgentModelClient.ConversationMessage>,
        supportsVision: Boolean,
        supportsVideo: Boolean = false,
    ): List<AgentModelClient.ConversationMessage> =
        history.map { message -> hydrate(message, supportsVision, supportsVideo) }

    fun hydrate(
        message: AgentModelClient.ConversationMessage,
        supportsVision: Boolean,
        supportsVideo: Boolean = false,
    ): AgentModelClient.ConversationMessage {
        if (message.contentJson.isBlank()) return message
        val content = runCatching { JSONTokener(message.contentJson).nextValue() }.getOrNull()
            as? JSONArray ?: return message
        var changed = false
        val next = JSONArray()
        val restoredImagePaths = mutableListOf<String>()
        val restoredVideoPaths = mutableListOf<String>()
        val restoredVideoDurationsMs = HashMap<String, Long>()
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                TYPE_IMAGE_FILE -> {
                    changed = true
                    val path = item.optString("path")
                    val mime = item.optString("mime").ifBlank { "image/jpeg" }
                    restoredImagePaths += path
                    if (!supportsVision) continue
                    val file = File(path)
                    if (!file.isFile || file.length() !in 1..MAX_AGENT_IMAGE_BYTES.toLong()) continue
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) continue
                    val image = runCatching {
                        AgentImageCodec.fromBytes(bytes, source = "history_attach", mimeHint = mime)
                    }.getOrNull() ?: continue
                    next.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", image.reference)),
                    )
                }
                TYPE_VIDEO_FILE -> {
                    changed = true
                    val path = item.optString("path")
                    val mime = item.optString("mime").ifBlank { "video/mp4" }
                    val file = File(path)
                    val preview = file.takeIf { supportsVision && !supportsVideo && it.isFile }
                        ?.let { AgentVideoCodec.previewFromFile(it, path) }
                    restoredVideoPaths += path
                    preview?.durationMs?.takeIf { it > 0L }?.let { restoredVideoDurationsMs[path] = it }
                    if (supportsVideo) {
                        if (!file.isFile || file.length() !in 1..MAX_AGENT_VIDEO_BYTES.toLong()) continue
                        val bytes = runCatching { file.readBytes() }.getOrNull()
                        if (bytes == null || bytes.isEmpty()) continue
                        val video = runCatching {
                            AgentVideoCodec.fromVideoBytes(bytes, mime, source = "history_attach")
                        }.getOrNull() ?: continue
                        next.put(
                            JSONObject()
                                .put("type", "video_url")
                                .put("video_url", JSONObject().put("url", video.reference)),
                        )
                    } else if (supportsVision && preview != null) {
                        next.put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", preview.thumbnail.reference)),
                        )
                    }
                }
                "image_url", "video_url" -> {
                    if (item.optString("type") == "video_url") {
                        if (supportsVideo) next.put(item) else changed = true
                    } else if (supportsVision) {
                        next.put(item)
                    } else {
                        changed = true
                    }
                }
                else -> next.put(item)
            }
        }
        if (!changed) return message
        val listings = buildList {
            if (!supportsVision && restoredImagePaths.isNotEmpty()) {
                addAll(restoredImagePaths.map { path -> "[User image] $path" })
            }
            if (!supportsVideo && restoredVideoPaths.isNotEmpty()) {
                addAll(restoredVideoPaths.map { path ->
                    val duration = restoredVideoDurationsMs[path]
                        ?.let { "\nDuration ${AgentVideoCodec.formatDuration(it)}" }
                        .orEmpty()
                    "[User video] $path$duration"
                })
            }
        }
        if (listings.isNotEmpty()) {
            val listing = listings.joinToString("\n")
            if (next.length() == 0) {
                next.put(JSONObject().put("type", "text").put("text", listing))
            } else {
                val first = next.optJSONObject(0)
                if (first != null && first.optString("type") == "text") {
                    val text = first.optString("text")
                    first.put("text", if (text.isBlank()) listing else "$text\n\n$listing")
                    next.put(0, first)
                } else {
                    val prefixed = JSONArray().put(JSONObject().put("type", "text").put("text", listing))
                    for (index in 0 until next.length()) prefixed.put(next.get(index))
                    return message.copy(
                        content = listing,
                        contentJson = prefixed.toString(),
                    )
                }
            }
        }
        val text = (0 until next.length())
            .mapNotNull { index ->
                next.optJSONObject(index)
                    ?.takeIf { it.optString("type") == "text" }
                    ?.optString("text")
            }
            .joinToString("\n")
        return message.copy(
            content = text.ifBlank { message.content },
            contentJson = next.toString(),
        )
    }
}
