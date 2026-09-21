package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Screen-gesture and node-interaction tool schemas. */
internal object AgentGestureToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "tap",
                    description = "Tap coordinates. Uses pixel coordinates from the latest observe_screen screenshot by default; if the coordinates come from a ui_nodes center, set coordinate_space=screen.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_area",
                    description = "Tap the center of a rectangular region. Uses pixel coordinates from the latest observe_screen screenshot by default; prefer this tool for large buttons, large list items, and visible text areas.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_element",
                    description = "Tap a UI node in a given observation snapshot. index and observation_id must come from the same latest observe_screen; re-observe first if the observation expired. The runtime confirms the su accessibility service is connected before executing.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "UI node index returned by the same observe_screen.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "observation_id from the same latest observe_screen as index.")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press",
                    description = "Long-press coordinates. Uses pixel coordinates from the latest observe_screen screenshot by default; if the coordinates come from a ui_nodes center, set coordinate_space=screen.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Long-press duration, 300 to 3000; default 800")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press_element",
                    description = "Long-press a UI node in a given observation snapshot. index and observation_id must come from the same latest observe_screen; re-observe first if the observation expired. The runtime confirms the su accessibility service is connected before executing.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "UI node index returned by the same observe_screen.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "observation_id from the same latest observe_screen as index.")
                                )
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Long-press duration, 300 to 3000; default 800")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "swipe",
                    description = "Swipe from one coordinate to another. Uses pixel coordinates from the latest observe_screen screenshot by default. Swiping up scrolls the list down.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Swipe duration, 100 to 2000; default 500")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll",
                    description = "Scroll the current screen by content direction: down reveals content below, up reveals content above, left reveals content on the left, right reveals content on the right.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                )
                        )
                        .put("required", JSONArray().put("direction"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll_element",
                    description = "Scroll a scrollable UI node in a given observation snapshot by content direction: down reveals content below, up reveals content above, left reveals content on the left, right reveals content on the right. index and observation_id must come from the same latest observe_screen; re-observe first if the observation expired. The runtime confirms the su accessibility service is connected before executing.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Scrollable UI node index returned by the same observe_screen.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "observation_id from the same latest observe_screen as index.")
                                )
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                        .put("description", "Content direction; down reveals content below, up reveals content above.")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id").put("direction"))
                )
            )
    }
}
