package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject

internal enum class RootRequirement { NONE, PARTIAL, REQUIRED }
internal enum class LsposedRequirement { NONE, OPTIONAL, REQUIRED }

internal enum class ToolSystemAccess { NONE, NOTIFICATIONS, USAGE, LOCATION }

internal data class LocalToolRequirement(
    val rootRequirement: RootRequirement,
    val lsposedRequirement: LsposedRequirement = LsposedRequirement.NONE,
    val accessibility: Boolean = false,
    val systemAccess: ToolSystemAccess = ToolSystemAccess.NONE,
)

/** Capability contract for local tools shared by the UI, model catalog, and execution boundary. Unregistered tools are never published. */
internal object AgentToolRequirements {
    private val definitions = buildMap {
        fun register(root: RootRequirement, vararg names: String) {
            names.forEach { name ->
                check(put(name, LocalToolRequirement(root)) == null) { "Duplicate tool: $name" }
            }
        }
        register(
            RootRequirement.NONE,
            "get_current_context", "search_apps", "launch_app", "open_uri", "browser_use", "text_to_speech",
            "observe_screen", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "set_clipboard", "get_clipboard", "paste_text",
            "wait", "wait_for_text", "wait_for_package", "open_system_panel",
            "set_alarm", "set_timer", "device_status", "media_control", "set_volume",
            "search_notification_history", "recent_app_activity", "app_usage_summary",
            "get_current_location", "get_device_environment", "memory_get", "memory_write",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "skills_inspect_github", "skills_install_from_github",
        )
        register(
            RootRequirement.PARTIAL,
            "press_key", "network_info", "get_setting", "recent_notifications",
            "search_personal_orders", "terminal", "run_command", "read_file",
            "write_file", "list_directory", "read_image",
        )
        register(
            RootRequirement.REQUIRED,
            "top_memory_apps", "top_storage_apps", "wifi_credentials", "read_sms_code",
            "get_logcat", "set_setting", "set_device_state", "app_state_control",
            "get_health_summary",
            "search_media", "search_audio", "search_recordings", "search_files",
            "search_calendar_events", "search_contacts", "search_call_history", "search_messages",
            "search_downloads",
        )
        listOf(
            "observe_screen", "tap", "tap_area", "tap_element", "long_press",
            "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "paste_text", "press_key", "open_system_panel",
            "wait_for_text", "wait_for_package",
        ).forEach { name -> put(name, getValue(name).copy(accessibility = true)) }
        mapOf(
            "recent_notifications" to ToolSystemAccess.NOTIFICATIONS,
            "search_notification_history" to ToolSystemAccess.NOTIFICATIONS,
            "search_personal_orders" to ToolSystemAccess.NOTIFICATIONS,
            "recent_app_activity" to ToolSystemAccess.USAGE,
            "app_usage_summary" to ToolSystemAccess.USAGE,
            "get_current_location" to ToolSystemAccess.LOCATION,
        ).forEach { (name, access) -> put(name, getValue(name).copy(systemAccess = access)) }
    }

    val toolNames: Set<String> get() = definitions.keys

    fun find(name: String): LocalToolRequirement? = definitions[name]

    fun rootRequirement(name: String): RootRequirement =
        requireNotNull(find(name)) { "Missing tool requirements: $name" }.rootRequirement

    fun requiresAccessibility(name: String): Boolean = find(name)?.accessibility == true

    fun rootDenied(name: String, arguments: JSONObject, rootAvailable: Boolean): Boolean {
        if (rootAvailable) return false
        if (rootRequirement(name) == RootRequirement.REQUIRED) return true
        return when (name) {
            "terminal" -> arguments.optString("identity").equals("root", ignoreCase = true)
            "press_key" -> arguments.optString("button").equals("PASTE", ignoreCase = true)
            else -> false
        }
    }

    /** Narrow on a copy; never mutate the original schema shared by the next round or another run. */
    fun project(tools: JSONArray, rootAvailable: Boolean): JSONArray = JSONArray().also { result ->
        for (index in 0 until tools.length()) {
            val original = tools.getJSONObject(index)
            val name = original.getJSONObject("function").getString("name")
            val requirement = rootRequirement(name)
            if (!rootAvailable && requirement == RootRequirement.REQUIRED) continue
            val tool = JSONObject(original.toString())
            if (!rootAvailable) projectUnprivileged(tool.getJSONObject("function"))
            result.put(tool)
        }
    }

    private fun projectUnprivileged(function: JSONObject) {
        val properties = function.getJSONObject("parameters").optJSONObject("properties")
        when (function.getString("name")) {
            "terminal" -> {
                function.put("description", "Manage a plain Android shell or the user-selected Linux environment on this device." +
                    "Runs as the app UID with sessions, async tasks, and background services; simulated identities inside Linux grant no Android privileges." +
                    "Use open_and_exec for one-shot commands, open/exec to reuse sessions, and daemon_start/list/logs/stop for background services.")
                properties?.getJSONObject("identity")
                    ?.put("enum", JSONArray().put("user"))
                    ?.put("description", "Host execution identity; only user is supported for now, default user.")
                properties?.getJSONObject("environment")?.put("description",
                    "android uses a plain Android shell; linux uses the user-selected distro with a rootless backend. Default android.")
                properties?.getJSONObject("cwd")?.put("description",
                    "Working directory. Android defaults to the su private workspace, Linux defaults to /workspace.")
            }
            "run_command" -> {
                function.put("description",
                    "Run a single non-interactive command via a plain Android shell as the app UID; only resources the app may access are reachable.")
                properties?.getJSONObject("cwd")?.put("description", "Working directory, defaulting to the su private workspace.")
            }
            "list_directory" -> {
                function.put("description", "List directories the current app may access, defaulting to the su private workspace.")
                properties?.optJSONObject("path")?.apply {
                    put("description", "Directory path; defaults to the su private workspace when omitted.")
                    remove("default")
                }
            }
            "read_image" -> properties?.getJSONObject("path")?.put("description",
                "Absolute image or video path, file URI, authorized content URI, or chat-attachment alias /home/workdir/attachments/image.jpg; a cover frame is extracted for videos.")
            "press_key" -> properties?.getJSONObject("button")?.let { button ->
                val values = button.getJSONArray("enum")
                button.put("enum", JSONArray().also { allowed ->
                    for (i in 0 until values.length()) {
                        val value = values.getString(i)
                        if (!value.equals("PASTE", ignoreCase = true)) allowed.put(value)
                    }
                })
                button.put("description", "System keys supported by accessibility; use paste_text to paste text.")
            }
        }
    }
}
