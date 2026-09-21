package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Text input, wait, and system-action tool schemas. */
internal object AgentTextSystemToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "input_text",
                    description = "Type up to 1000 characters into the input field that truly holds input focus. Default mode=append inserts at the cursor or replaces the selection; password and other unreadable fields refuse reconstruction, so use replace_text with the full value instead.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 1_000)
                                        .put("description", "Text to type, up to 1000 characters; requires the accessibility service to confirm genuine input focus.")
                                )
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("append").put("replace").put("paste"))
                                        .put("description", "append types at the cursor or replaces the selection, replace replaces text, paste uses the paste path; all three modes of this tool are limited to 1000 characters. Default append.")
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Optional editable node index when mode=replace; must be passed together with the observation_id from the same observe_screen call.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required when mode=replace with an index; must come from the same recent observe_screen call as the index.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "replace_text",
                    description = "Replace the text of the currently focused input field or the given editable node. When an index is given, the index and observation_id must come from the same recent observe_screen call; re-observe first if the observation has expired. Requires the accessibility service to be enabled.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 4_000),
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Optional editable node index from the latest observe_screen call; when given, the observation_id from the same observation must also be passed, otherwise the currently focused input field is used.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required when an index is given, and must come from the same recent observe_screen call as the index; omit when no index is given.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "clear_text",
                    description = "Clear the currently focused input field or the given editable node. When an index is given, the index and observation_id must come from the same recent observe_screen call; re-observe first if the observation has expired. Requires the accessibility service to be enabled.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Optional editable node index from the latest observe_screen call; when given, the observation_id from the same observation must also be passed, otherwise the currently focused input field is used.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required when an index is given, and must come from the same recent observe_screen call as the index; omit when no index is given.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "set_clipboard",
                    description = "Write text to the system clipboard. Useful for staging long text, CJK characters, emoji, or special characters.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "get_clipboard",
                    description = "Read text from the system clipboard. The Android version or background restrictions may cause reads to fail.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "paste_text",
                    description = "Type long text into the current selection after confirming genuine input focus; falls back to system clipboard paste only when the target does not support direct setting. Never overwrites the clipboard when there is no focus; for password and other unreadable fields use replace_text with the full value instead.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "press_key",
                    description = "Press a system key or perform a global action. BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS prefer the accessibility global action; ENTER prefers the input-method enter key.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "button",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("BACK")
                                                .put("HOME")
                                                .put("ENTER")
                                                .put("RECENTS")
                                                .put("PASTE")
                                                .put("NOTIFICATIONS")
                                                .put("QUICK_SETTINGS")
                                        )
                                )
                        )
                        .put("required", JSONArray().put("button"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait",
                    description = "Wait for a while so animations, network loads, or page transitions can finish. Do not use it as a substitute for the verifiable waits of wait_for_text/wait_for_package.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Wait duration, 100 to 30000, default 1000.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_text",
                    description = "Wait for the given text or description to appear on the current screen; useful after a tap to confirm the page arrived, a list finished loading, or a dialog appeared.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Maximum wait time, 500 to 60000, default 10000.")
                                )
                                .put(
                                    "include_desc",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether to match content-desc, default true.")
                                )
                                .put(
                                    "match",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("contains").put("exact").put("prefix").put("regex"))
                                        .put("description", "Match mode, default contains.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_package",
                    description = "Wait for the given Android package to come to the foreground; useful after launch_app/open_uri to confirm the target app opened.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("package_name", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Maximum wait time, 500 to 60000, default 10000.")
                                )
                        )
                        .put("required", JSONArray().put("package_name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_system_panel",
                    description = "Open the notification shade or quick-settings panel.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "panel",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("notifications").put("quick_settings"))
                                )
                        )
                        .put("required", JSONArray().put("panel"))
                )
            )
    }
}
