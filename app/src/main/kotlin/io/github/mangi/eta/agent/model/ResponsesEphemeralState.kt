package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Carried only within a single agent run; the stable session codec does not recognize it and actively drops it. */
internal object ResponsesEphemeralState {
    private const val OUTPUT_ITEMS_KEY = "_eta_responses_output_items"

    fun outputItems(message: JSONObject): JSONArray? =
        message.optJSONArray(OUTPUT_ITEMS_KEY)

    fun attachOutputItems(message: JSONObject, items: JSONArray) {
        message.put(OUTPUT_ITEMS_KEY, JSONArray(items.toString()))
    }

    fun copyOutputItems(source: JSONObject, target: JSONObject) {
        outputItems(source)?.let { attachOutputItems(target, it) }
    }
}
