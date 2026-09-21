package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Context, app-entry, and screen-observation tool schemas. */
internal object AgentContextAppToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "Get the phone's current time, time zone, and recent system location; call it when now, today, tomorrow, or the user's whereabouts matter.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "Search installed Android apps on the phone; returns app names and package names. When unsure of a package name before opening an app, call this tool first.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "App-name or package-name fragment, e.g. QQ, WeChat, com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether to include system apps; default false")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "At most 1 to 20 results; default 10")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "Launch an installed Android app. Prefer package_name; with only an app name, fuzzy matching is allowed, and multiple matches return candidates instead of launching.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Exact Android package name, e.g. com.tencent.mobileqq")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "App display name, e.g. QQ")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "Hand a known-valid URI explicitly to an external Android app, e.g. https, tel, geo, or an app deep link. It is not for reading pages or web interaction. Never invent URIs.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "A known-valid URI the system can handle")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "observe_screen",
                    description = "Observe the phone's current screen. By default returns only the foreground app, screen size, observation_id, and visible UI nodes, with no screenshot. Set include_screenshot=true explicitly when nodes are empty, the target cannot be uniquely identified, the screen is mostly visual content such as Canvas, maps, images, or QR codes, or the task depends on color, imagery, or spatial layout. When adding a screenshot, keep include_ui_tree=true so nodes and observation_id refresh in the same new observation; never mix a new screenshot with stale nodes. Node actions must carry the observation_id from that same observation unchanged. When the tree is truncated but node semantics still hold, prefer raising max_nodes to 120 and retrying instead of requesting a screenshot for truncation alone.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "include_screenshot",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_SCREENSHOT)
                                        .put("description", "Whether to attach the raw current screenshot for the model; default false. Enable explicitly only when UI nodes cannot complete the task.")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_UI_TREE)
                                        .put("description", "Whether to return the UI node list; default true")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentScreenObservationContract.MIN_MAX_NODES)
                                        .put("maximum", AgentScreenObservationContract.MAX_MAX_NODES)
                                        .put("default", AgentScreenObservationContract.DEFAULT_MAX_NODES)
                                        .put("description", "At most 1 to 120 UI nodes; default 60")
                                )
                        )
                )
            )
    }
}
