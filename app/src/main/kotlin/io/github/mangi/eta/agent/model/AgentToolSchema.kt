package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentToolSchema {
    fun coordinateSpace(): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("screenshot").put("screen"))
            .put(
                "description",
                "screenshot is pixel coordinates in the latest observe_screen attachment; screen is real device screen coordinates. Default: screenshot.",
            )

    fun function(
        name: String,
        description: String,
        parameters: JSONObject,
    ): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
}
