package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 上下文、应用入口与屏幕观察工具 schema。 */
internal object AgentContextAppToolCatalog {
    fun appendTo(tools: JSONArray) {
        for ((name,description) in listOf(
            "start_virtual_session" to "启动本次后台副屏会话，重复调用不重复创建；失败不会回退主屏。",
            "finish_virtual_session" to "移交已标记的交付任务到主屏后台。未标记交付任务时会清理本次中间任务并关闭副屏、不交付（handedOff=false, released=true）。必须检查 handedOff 和 released；失败时不杀进程，不声称交付成功。"
        )) tools.put(AgentToolSchema.function(name=name,description=description,parameters=JSONObject().put("type","object").put("properties",JSONObject())))
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "Retrieves current phone time, timezone, and recent location; call when query involves now, today, tomorrow, or position.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "Searches installed Android apps on phone, returning app name and package name. Call when package name is uncertain before opening.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "App name or package substring, e.g. QQ or com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether to include system apps, default false")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Max results to return (1 to 20), default 10")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "启动一个已安装 Android 应用。优先提供 package_name；只有应用名时允许模糊匹配，匹配多个会返回候选而不会启动。本次副屏已经打开的应用会切回原任务（reused=true）。",
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
                    name = "inspect_virtual_backend",
                    description = "用当前应用内的探针进行 Root 只读后端检查，不启动副屏。查询成功不等于授权；mutations_enabled 与 session_authenticated 恒为 false，不迁移、恢复或关闭应用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "keep_virtual_result",
                    description = "Marks tasks to handoff for secondary session. Only marks tasks; finish_virtual_session must be called to verify handoff.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("task_ids", JSONObject().put("type","array").put("items",JSONObject().put("type","integer")).put("description","本次启动工具返回的精确任务编号"))
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Exact package name of target app")
                                )
                                .put(
                                    "packages",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("items", JSONObject().put("type", "string"))
                                        .put("description", "Multiple package names of target apps")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "Explicitly hands a valid URI to an external Android app (e.g. https, tel, geo, deep link). Not for reading web pages.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Valid URI that can be handled by system")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "observe_screen",
                    description = "观察当前手机屏幕，默认只返回前台应用、屏幕尺寸、observation_id 与可见 UI 节点，不附截图。节点为空、目标无法唯一识别、界面以 Canvas/地图/图片/二维码等视觉内容为主，或任务依赖颜色、图像、空间布局时，显式设置 include_screenshot=true；补截图时保持 include_ui_tree=true，以同一次新观察刷新节点和 observation_id，禁止把新截图与旧节点混用。节点动作必须原样携带同一次观察的 observation_id；树被截断但节点语义仍有效时，优先把 max_nodes 提高到 120 后重试，不要仅因截断请求截图。",
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
                                        .put("description", "Whether to attach current screen screenshot to model, default false.")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_UI_TREE)
                                        .put("description", "Whether to return UI node list, default true")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentScreenObservationContract.MIN_MAX_NODES)
                                        .put("maximum", AgentScreenObservationContract.MAX_MAX_NODES)
                                        .put("default", AgentScreenObservationContract.DEFAULT_MAX_NODES)
                                        .put("description", "Max UI nodes to return (1 to 120), default 60")
                                )
                        )
                )
            )
    }
}
