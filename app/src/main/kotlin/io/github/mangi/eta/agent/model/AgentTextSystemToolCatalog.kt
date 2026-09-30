package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文本输入、等待与系统操作工具 schema。 */
internal object AgentTextSystemToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "input_text",
                    description = "向真正获得输入焦点的输入框键入不超过 1000 字符的文本。默认 mode=append，会在当前光标插入或替换选区；密码等不可读输入框会拒绝重建，请用 replace_text 提供完整值。",
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
                                        .put("description", "Text to input, up to 1000 characters; requires accessibility service to confirm input focus.")
                                )
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("append").put("replace").put("paste"))
                                        .put("description", "append types at cursor or replaces selection, replace replaces text, paste uses clipboard path; all modes limited to 1000 chars. Default append.")
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Optional editable node index when mode=replace; must pass observation_id from same observe_screen.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required when mode=replace with index; must match observation_id from same observe_screen.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "replace_text",
                    description = "把当前聚焦输入框或指定 editable 节点的文本替换为给定内容。指定 index 时，index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。需要启用无障碍服务。",
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
                                        .put("description", "可选，最近一次 observe_screen 的 editable 节点 index；传入时必须同时传入同一次观察的 observation_id，不传则使用当前聚焦输入框。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required when index is specified, matching observation_id from same observe_screen.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "clear_text",
                    description = "清空当前聚焦输入框或指定 editable 节点。指定 index 时，index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。需要启用无障碍服务。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "可选，最近一次 observe_screen 的 editable 节点 index；传入时必须同时传入同一次观察的 observation_id，不传则使用当前聚焦输入框。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required when index is specified, matching observation_id from same observe_screen.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "set_clipboard",
                    description = "Writes text to system clipboard. Suitable for long text, special characters, or emoji.",
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
                    description = "Reads system clipboard text. Android version or background restrictions may cause failures.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "paste_text",
                    description = "Inputs long text into focused field after confirming focus; falls back to clipboard paste if target does not support direct set. Does not overwrite clipboard if no focus.",
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
                    description = "Triggers system key or global action. BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS use accessibility global actions; ENTER uses IME enter.",
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
                    description = "Waits for a duration to allow animations or network loading to finish. Do not use in place of wait_for_text/wait_for_package.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Wait duration in ms, 100 to 30000, default 1000.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_text",
                    description = "Waits for specified text or description to appear on screen, suitable for verifying page arrival or list loading.",
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
                                        .put("description", "Max wait time in ms, 500 to 60000, default 10000.")
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
                                        .put("description", "Matching method, default contains.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_package",
                    description = "Waits for specified Android package to come to foreground, suitable for confirming app opening.",
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
                                        .put("description", "Max wait time in ms, 500 to 60000, default 10000.")
                                )
                        )
                        .put("required", JSONArray().put("package_name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_system_panel",
                    description = "Opens notification shade or quick settings panel.",
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
