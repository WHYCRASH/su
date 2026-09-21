package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Text-only summary projection. Parse before visiting media: JSON may escape every
 * slash in a data URI. Never mutate the source/history or interpret an image as text.
 * Same-model vision replay remains structured and uses AgentRequestMediaPolicy.
 */
internal object AgentSummaryContent {
    private const val OMITTED = "[embedded media; original retained, content not interpreted]"
    private val dataUri = Regex("data:[^;\\s\"]+;base64,[A-Za-z0-9+/=\\s]+")

    fun project(raw: String): String {
        val source = JSONTokener(raw).nextValue()
        require(source is JSONObject || source is JSONArray) { "Invalid structured summary content; original history unchanged" }
        return visit(source).toString()
    }

    private fun visit(value: Any?): Any = when (value) {
        is JSONArray -> JSONArray().also { result ->
            for (i in 0 until value.length()) result.put(visit(value.get(i)))
        }
        is JSONObject -> {
            val type = value.optString("type")
            if (type in setOf("image", "image_url", "input_image", "image_file", "video", "video_url", "input_video", "video_file", "input_audio", "audio")) {
                JSONObject().put("type", "text").put("text", OMITTED)
            } else JSONObject().also { result ->
                value.keys().forEach { key -> result.put(key, visit(value.get(key))) }
            }
        }
        is String -> dataUri.replace(value, OMITTED)
        else -> value ?: JSONObject.NULL
    }
}
