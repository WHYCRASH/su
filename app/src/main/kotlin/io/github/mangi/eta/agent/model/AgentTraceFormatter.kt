package io.github.mangi.eta.agent.model

import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** Tool summaries are user-facing and exclude sensitive parameters; terminal commands get a separate field for the user to verify. */
internal class AgentTraceFormatter(
    private val linuxEnvironmentLabelProvider: () -> String = { "Linux" },
    private val terminalSessionEnvironmentProvider: (String) -> String? = { null },
    private val terminalSessionIdentityProvider: (String) -> String? = { null },
) {
    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String =
        when (toolCall.name) {
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(toolCall.argumentsJson)
            "open_uri" -> summarizeOpenUriArguments(toolCall.argumentsJson)
            "terminal" -> summarizeTerminalArguments(toolCall.argumentsJson)
            "run_command" -> summarizeRunCommandArguments(toolCall.argumentsJson)
            "write_file" -> summarizeTextLength("Write file", toolCall.argumentsJson, "content")
            "read_file" -> "Read file"
            "list_directory" -> "List directory"
            "input_text" -> summarizeTextLength("Type text", toolCall.argumentsJson, "text")
            "replace_text" -> summarizeTextLength("Replace text", toolCall.argumentsJson, "text")
            "paste_text", "set_clipboard" ->
                summarizeTextLength("Paste text", toolCall.argumentsJson, "text")
            "clear_text" -> "Clear text"
            "get_clipboard" -> "Read clipboard"
            "search_apps" -> summarizeQueryArguments("Search apps", toolCall.argumentsJson)
            "launch_app" -> "Open app"
            "get_current_context" -> "Read current context"
            "observe_screen" -> summarizeObservationArguments(toolCall.argumentsJson)
            "tap" -> summarizePointArguments("Tap screen", toolCall.argumentsJson)
            "long_press" -> summarizePointArguments("Long-press screen", toolCall.argumentsJson)
            "tap_area" -> "Tap area"
            "tap_element" -> summarizeElementArguments("Tap element", toolCall.argumentsJson)
            "long_press_element" -> summarizeElementArguments("Long-press element", toolCall.argumentsJson)
            "swipe" -> "Swipe screen"
            "scroll" -> summarizeScrollArguments("Scroll screen", toolCall.argumentsJson)
            "scroll_element" ->
                summarizeScrollArguments("Scroll element", toolCall.argumentsJson, withIndex = true)
            "press_key" -> summarizePressKeyArguments(toolCall.argumentsJson)
            "wait" -> summarizeWaitArguments(toolCall.argumentsJson)
            "wait_for_text" -> "Wait for text"
            "wait_for_package" -> "Wait for app"
            "open_system_panel" -> "Open system panel"
            "read_image" -> "View image or video"
            "delegate_task" -> "Delegate subtask"
            "manage_agent_workspace" -> "Manage task workspace"
            "get_task_result" -> "Check subtask result"
            "cancel_task" -> "Cancel subtask"
            "memory_get" -> summarizeMemoryGetArguments(toolCall.argumentsJson)
            "memory_write" -> summarizeMemoryWriteArguments(toolCall.argumentsJson)
            "skills_list" -> "View skill list"
            "skills_read" -> "Read skill"
            "skills_read_resource" -> "Read skill resource"
            "skills_list_curated" -> "Browse featured skills"
            "skills_inspect_github" -> "View skill details"
            "skills_install_from_github" -> "Install skill"
            else -> {
                val label = DEVICE_ACTION_LABELS[toolCall.name]
                when {
                    label == null -> "Preparing"
                    toolCall.name.startsWith("search_") ->
                        summarizeQueryArguments(label, toolCall.argumentsJson)
                    else -> label
                }
            }
        }

    /** Commands enter the run trace as a redacted, user-visible projection; logs still record only the length. */
    fun displayCommand(toolCall: AgentModelClient.ToolCall): String? =
        if (toolCall.name == "terminal" || toolCall.name == "run_command") {
            runCatching {
                JSONObject(toolCall.argumentsJson)
                    .optString("command")
                    .trim()
                    .takeIf { it.isNotBlank() && it.length <= MAX_DISPLAY_COMMAND_CHARS }
                    ?.redactDisplaySecrets()
            }.getOrNull()
        } else {
            null
        }

    private fun String.redactDisplaySecrets(): String =
        replace(SENSITIVE_ASSIGNMENT) { match ->
            "${match.groupValues[1]}=<hidden>"
        }
            .replace(SENSITIVE_FLAG) { match ->
                "${match.groupValues[1]}<hidden>"
            }
            .replace(SENSITIVE_HEADER) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}<hidden>"
            }

    /** External URI summaries do not record path, query, fragment, or user info. */
    fun summarizeOpenUriArguments(argumentsJson: String): String =
        runCatching {
            val raw = JSONObject(argumentsJson).optString("uri").trim()
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()?.take(24)
            val host = uri.host?.lowercase()?.take(160)
            listOfNotNull("Hand off to external app", scheme, host).joinToString(" · ")
        }.getOrDefault("Hand off to external app")

    /** browser_use summaries expose only the action and the safely extracted host. */
    fun summarizeBrowserArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").browserActionLabel()
            val host = safeHttpHost(arguments.optString("url"))
            listOfNotNull(action, host).joinToString(" · ")
        }.getOrElse { "Browser action" }

    private fun summarizeRunCommandArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val resolved = resolveTerminalRuntime(arguments)
            buildList {
                add("Run command")
                add(resolved.environment)
                resolved.identity?.let(::add)
            }.joinToString(" · ")
        }.getOrDefault("Run command")

    private fun summarizeTerminalArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").terminalActionLabel()
            val resolved = resolveTerminalRuntime(arguments)
            buildList {
                add("Terminal")
                add(action)
                add(resolved.environment)
                resolved.identity?.let(::add)
                if (arguments.optBoolean("async", false)) add("Background")
            }.joinToString(" · ")
        }.getOrDefault("Terminal")

    private fun resolveTerminalRuntime(arguments: JSONObject): TerminalRuntimeSummary {
        val sessionId = arguments.optString("session_id").takeIf { it.isNotBlank() }
        val environment = arguments.optString("environment")
            .ifBlank { sessionId?.let(terminalSessionEnvironmentProvider).orEmpty() }
            .ifBlank { "android" }
            .terminalEnvironmentLabel()
        val identity = arguments.optString("identity")
            .ifBlank { sessionId?.let(terminalSessionIdentityProvider).orEmpty() }
            .ifBlank { "root" }
            .takeIf { it == "root" || it == "user" }
        return TerminalRuntimeSummary(environment = environment, identity = identity)
    }

    private fun summarizeTextLength(
        label: String,
        argumentsJson: String,
        key: String,
    ): String =
        runCatching {
            val chars = JSONObject(argumentsJson).optString(key).length
            "$label · $chars characters"
        }.getOrDefault(label)

    /** Search keywords are user-initiated queries, so display them directly; still normalize to one line and truncate by length. */
    private fun summarizeQueryArguments(label: String, argumentsJson: String): String =
        runCatching {
            val query = sanitizeSummaryValue(
                JSONObject(argumentsJson).optString("query"),
                MAX_QUERY_SUMMARY_CHARS,
            )
            if (query.isNotBlank()) "$label · $query" else label
        }.getOrDefault(label)

    private fun summarizePointArguments(label: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            "$label · (${arguments.optInt("x")}, ${arguments.optInt("y")})"
        }.getOrDefault(label)

    private fun summarizeElementArguments(label: String, argumentsJson: String): String =
        runCatching {
            val index = JSONObject(argumentsJson).optInt("index", -1)
            if (index >= 0) "$label · #$index" else label
        }.getOrDefault(label)

    private fun summarizeScrollArguments(
        label: String,
        argumentsJson: String,
        withIndex: Boolean = false,
    ): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            buildList {
                add(label)
                if (withIndex) {
                    arguments.optInt("index", -1).takeIf { it >= 0 }?.let { add("#$it") }
                }
                arguments.optString("direction").scrollDirectionLabel()?.let(::add)
            }.joinToString(" · ")
        }.getOrDefault(label)

    private fun summarizePressKeyArguments(argumentsJson: String): String =
        runCatching {
            val button = JSONObject(argumentsJson).optString("button").pressKeyLabel()
            listOfNotNull("Key press", button).joinToString(" · ")
        }.getOrDefault("Key press")

    private fun summarizeWaitArguments(argumentsJson: String): String =
        runCatching {
            val durationMs = JSONObject(argumentsJson).optInt("duration_ms", 1_000)
                .coerceAtLeast(0)
            val duration = if (durationMs >= 1_000) {
                String.format(Locale.US, "%.1f", durationMs / 1_000f)
                    .trimEnd('0').trimEnd('.') + " seconds"
            } else {
                "$durationMs milliseconds"
            }
            "Waiting · $duration"
        }.getOrDefault("Waiting")

    private fun summarizeObservationArguments(argumentsJson: String): String =
        runCatching {
            val options = AgentScreenObservationContract.resolve(JSONObject(argumentsJson))
            buildList {
                add("Observe screen")
                if (options.includeScreenshot) add("Includes screenshot")
                if (options.includeUiTree) add("Includes UI tree")
            }.joinToString(" · ")
        }.getOrDefault("Observe screen")

    private fun summarizeMemoryGetArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            if (arguments.optString("query").isNotBlank()) "Search memory" else "Read memory"
        }.getOrDefault("Read memory")

    private fun summarizeMemoryWriteArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val mode = when (arguments.optString("mode")) {
                "replace_range" -> "Replace snippet"
                "append" -> "Append"
                "clear" -> "Clear"
                else -> null
            }
            val content = arguments.optString("content")
            val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
            buildList {
                add("Update memory")
                mode?.let(::add)
                add("$lines lines")
                add("${content.toByteArray(Charsets.UTF_8).size} bytes")
            }.joinToString(" · ")
        }.getOrDefault("Update memory")

    /** Result success/failure is used by events and UI state and no longer relies on markers in the summary text. */
    fun isSuccessResult(result: AgentModelClient.ToolResult): Boolean =
        parseResultJson(result)?.optBoolean("ok", true) ?: true

    fun summarizeResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String {
        val json = parseResultJson(result)
        // When terminal exit_code != 0, ok=false but there is no code field, so a dedicated branch must be used to preserve the exit code and output
        if (toolName == "terminal" || toolName == "run_command") {
            return summarizeTerminalResult(json)
        }
        if (!isSuccessResult(result)) return summarizeFailure(json)
        return when (toolName) {
            BROWSER_TOOL_NAME -> json?.let(::summarizeBrowserResult) ?: "Browser action completed"
            "memory_get", "memory_write" ->
                json?.let { summarizeMemoryResult(toolName, it) } ?: "Completed"
            "delegate_task", "get_task_result", "cancel_task" -> when (json?.optString("status")) {
                "running" -> "Sub-agent running"
                "completed" -> "Sub-agent returned · Waiting for main agent review"
                "cancelled" -> "Sub-agent canceled"
                "timed_out" -> "Sub-agent timed out · Main agent taking over"
                "failed" -> "Sub-agent failed · Main agent taking over"
                else -> "Sub-agent status unknown"
            }
            "search_apps" -> json?.let(::summarizeSearchAppsResult) ?: "Done"
            "launch_app" -> json?.let(::summarizeLaunchAppResult) ?: "Opened"
            else -> json?.let { summarizeGenericResult(it, result) } ?: "Done"
        }
    }

    private fun parseResultJson(result: AgentModelClient.ToolResult): JSONObject? =
        runCatching { JSONObject(result.content) }.getOrNull()

    /** Failure summary preserves the code= marker for run logs to extract a stable error code; message is the English reason provided by the tool side. */
    private fun summarizeFailure(json: JSONObject?): String {
        val code = json?.optString("code")?.takeIf { it.isNotBlank() }
        val reason = json?.optString("message")
            ?.let(::sanitizeSummaryValue)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add("Failed")
            reason?.let(::add)
            code?.let { add("code=$it") }
        }.joinToString(" · ")
    }

    private fun summarizeMemoryResult(toolName: String, json: JSONObject): String =
        buildList {
            add(if (toolName == "memory_get") "Memory read" else "Memory updated")
            if (json.has("line_count")) add("${json.optInt("line_count")} lines")
            if (json.has("bytes")) add("${json.optInt("bytes")} bytes")
        }.joinToString(" · ")

    private fun summarizeGenericResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String =
        buildList {
            add("Done")
            json.optJSONArray("apps")?.let { add("Found ${it.length()} apps") }
            json.optJSONArray("candidates")?.let { add("${it.length()} candidates") }
            if (result.images.isNotEmpty()) add("${result.images.size} images")
        }.joinToString(" · ")

    private fun summarizeSearchAppsResult(json: JSONObject): String {
        val apps = json.optJSONArray("apps") ?: return "No matching app found"
        val total = apps.length()
        if (total == 0) return "No matching app found"
        val names = (0 until total).mapNotNull { index ->
            apps.optJSONObject(index)?.optString("app_name")
                ?.let(::sanitizeSummaryValue)
                ?.takeIf { it.isNotBlank() }
        }
        return buildString {
            append("Found $total apps")
            val shown = names.take(MAX_LISTED_APP_NAMES)
            if (shown.isNotEmpty()) {
                append(" · ").append(shown.joinToString(", "))
                if (total > shown.size) append(" etc.")
            }
        }
    }

    private fun summarizeLaunchAppResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        return if (appName.isNotBlank()) "Opened · $appName" else "Opened"
    }

    /**
     * Terminal results display the exit status and an output preview to the user; output may be very long,
     * so only the first few lines are kept, and an ellipsis marker is appended when truncated.
     */
    private fun summarizeTerminalResult(json: JSONObject?): String {
        if (json == null) return "Terminal"
        if (json.optString("code").isNotBlank()) return summarizeFailure(json)
        if (!json.has("exit_code") || json.isNull("exit_code")) {
            val action = json.optString("action").terminalActionLabel()
            return if (json.optBoolean("ok", true)) "Terminal · $action" else "Failed · $action"
        }
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val status = when {
            timedOut -> "Failed · Execution timed out"
            exitCode == 0 -> "Execution complete"
            else -> "Failed · Exit code $exitCode"
        }
        val output = if (exitCode == 0) {
            json.optString("stdout")
        } else {
            json.optString("stderr").ifBlank { json.optString("stdout") }
        }
        val truncated = json.optBoolean("stdout_truncated", false) ||
            json.optBoolean("stderr_truncated", false)
        val preview = terminalOutputPreview(output, truncated) ?: return status
        return "$status\n$preview"
    }

    private fun terminalOutputPreview(output: String, truncated: Boolean): String? {
        val normalized = output.trim()
        if (normalized.isEmpty()) return null
        val allLines = normalized.lines()
        var preview = allLines.take(MAX_TERMINAL_PREVIEW_LINES).joinToString("\n")
        var capped = allLines.size > MAX_TERMINAL_PREVIEW_LINES || truncated
        if (preview.length > MAX_TERMINAL_PREVIEW_CHARS) {
            preview = preview.take(MAX_TERMINAL_PREVIEW_CHARS)
            capped = true
        }
        return if (capped) "$preview\n…" else preview
    }

    private fun summarizeBrowserResult(json: JSONObject): String {
        val page = json.optJSONObject("page")
            ?: json.optJSONObject("page_info")
            ?: json.optJSONObject("pageInfo")
        val action = json.optString("action")
            .takeIf { it in BROWSER_ACTIONS }
            ?: "unknown"
        val host = sequenceOf(json, page)
            .filterNotNull()
            .flatMap { source ->
                sequenceOf("url", "current_url", "currentUrl", "final_url", "finalUrl")
                    .map(source::optString)
            }
            .mapNotNull(::safeHttpHost)
            .firstOrNull()
        val title = sequenceOf(json, page)
            .filterNotNull()
            .map { it.opt("title") }
            .filterIsInstance<String>()
            .map(::sanitizeSummaryValue)
            .firstOrNull { it.isNotBlank() }
        val textChars = sequenceOf(json, page)
            .filterNotNull()
            .mapNotNull { source ->
                source.firstNonNegativeInt("text_length", "textLength", "text_chars", "textChars")
            }
            .firstOrNull()
            ?: sequenceOf(json, page)
                .filterNotNull()
                .flatMap { source -> sequenceOf("text", "readable", "content").map(source::opt) }
                .filterIsInstance<String>()
                .map(String::length)
                .firstOrNull()
        val elementCount = json.firstNonNegativeInt("element_count", "elementCount", "elements_count")
            ?: json.optJSONArray("elements")?.length()

        return buildList {
            add(action.browserSuccessLabel())
            host?.let(::add)
            title?.let { add("\"$it\"") }
            if (action in BROWSER_TEXT_ACTIONS) {
                textChars?.let { add("About ${formatCharCount(it)}") }
            }
            elementCount?.let { add("$it elements") }
            if (json.optBoolean("truncated", false)) add("Truncated")
        }.joinToString(" · ")
    }

    private fun formatCharCount(chars: Int): String =
        if (chars >= 10_000) {
            String.format(Locale.US, "%.1f", chars / 10_000f).trimEnd('0').trimEnd('.') + "0k characters"
        } else {
            "$chars characters"
        }

    private fun JSONObject.firstNonNegativeInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key)) return@firstNotNullOfOrNull null
            optInt(key, -1).takeIf { it >= 0 }
        }

    private fun sanitizeSummaryValue(value: String, maxChars: Int = 80): String =
        value.replace(Regex("\\s+"), " ")
            .trim()
            .replace(',', '，')
            .replace('=', '＝')
            .let { if (it.length <= maxChars) it else it.take(maxChars) + "..." }

    private fun safeHttpHost(rawUrl: String): String? =
        rawUrl.trim()
            .takeIf(String::isNotEmpty)
            ?.let { value ->
                runCatching {
                    val uri = URI(value)
                    uri.host
                        ?.takeIf {
                            uri.scheme.equals("http", ignoreCase = true) ||
                                uri.scheme.equals("https", ignoreCase = true)
                        }
                        ?.lowercase()
                        ?.take(160)
                }.getOrNull()
            }

    private fun String.browserActionLabel(): String = when (this) {
        "navigate" -> "Open webpage"
        "get_readable" -> "Extract content"
        "get_text" -> "Read text"
        "find_elements" -> "Find element"
        "click" -> "Click page"
        "type" -> "Enter content"
        "scroll" -> "Scroll page"
        "screenshot" -> "Page screenshot"
        "get_page_info" -> "View page info"
        "go_back" -> "Page back"
        "go_forward" -> "Page forward"
        "reload" -> "Refresh page"
        "wait_for_selector" -> "Wait for page element"
        else -> "Browser action"
    }

    private fun String.browserSuccessLabel(): String = when (this) {
        "navigate" -> "Opened"
        "get_readable" -> "Content extracted"
        "get_text" -> "Text read"
        "find_elements" -> "Element found"
        "click" -> "Page clicked"
        "type" -> "Content entered"
        "scroll" -> "Page scrolled"
        "screenshot" -> "Screenshot taken"
        "get_page_info" -> "Page info read"
        "go_back" -> "Went back"
        "go_forward" -> "Went forward"
        "reload" -> "Refreshed"
        "wait_for_selector" -> "Target element appeared"
        else -> "Browser action completed"
    }

    private fun String.scrollDirectionLabel(): String? = when (lowercase(Locale.US)) {
        "up" -> "Up"
        "down" -> "Down"
        "left" -> "Left"
        "right" -> "Right"
        else -> null
    }

    private fun String.pressKeyLabel(): String? = when (lowercase(Locale.US)) {
        "back" -> "Back"
        "home" -> "Home"
        "recents", "recent" -> "Recent tasks"
        "notifications" -> "Notification shade"
        "quick_settings" -> "Control Center"
        "power" -> "Power"
        "volume_up" -> "Volume up"
        "volume_down" -> "Volume down"
        "mute" -> "Mute"
        else -> null
    }

    private fun String.terminalActionLabel(): String = when (this) {
        "open" -> "Create session"
        "exec" -> "Run command"
        "open_and_exec" -> "Run once"
        "read_async_result" -> "Read background output"
        "close" -> "Close terminal"
        "daemon_start" -> "Start daemon task"
        "daemon_list" -> "Daemon task list"
        "daemon_logs" -> "View daemon logs"
        "daemon_stop" -> "Stop daemon task"
        else -> "Terminal operations"
    }

    private fun String.terminalEnvironmentLabel(): String = when (this) {
        "alpine" -> "Alpine"
        "debian" -> "Debian"
        "linux" -> linuxEnvironmentLabelProvider()
        else -> "Android"
    }

    private data class TerminalRuntimeSummary(
        val environment: String,
        val identity: String?,
    )

    private companion object {
        const val BROWSER_TOOL_NAME = "browser_use"
        const val MAX_DISPLAY_COMMAND_CHARS = 4_000
        const val MAX_QUERY_SUMMARY_CHARS = 30
        const val MAX_LISTED_APP_NAMES = 3
        const val MAX_TERMINAL_PREVIEW_LINES = 3
        const val MAX_TERMINAL_PREVIEW_CHARS = 240
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)\b([A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?TOKEN|AUTH[_-]?TOKEN|TOKEN|PASSWORD|PASSWD|SECRET)[A-Z0-9_]*)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_FLAG = Regex(
            """(?i)(--?(?:password|passwd|token|api[-_]?key|secret)(?:\s*=\s*|\s+))(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_HEADER = Regex(
            """(?i)\b(Authorization|Proxy-Authorization|X-Api-Key)(\s*:\s*)[^'"\r\n;&|]+"""
        )
        val BROWSER_ACTIONS = setOf(
            "navigate",
            "get_readable",
            "get_text",
            "find_elements",
            "click",
            "type",
            "scroll",
            "screenshot",
            "get_page_info",
            "go_back",
            "go_forward",
            "reload",
            "wait_for_selector",
        )
        val BROWSER_TEXT_ACTIONS = setOf("get_readable", "get_text")

        /** Structured device tools only display action labels and expose no parameters. */
        val DEVICE_ACTION_LABELS = mapOf(
            "set_alarm" to "Set alarm",
            "set_timer" to "Set timer",
            "device_status" to "View device status",
            "network_info" to "View network info",
            "top_memory_apps" to "View memory usage ranking",
            "top_storage_apps" to "View storage usage ranking",
            "media_control" to "Control media playback",
            "set_volume" to "Adjust volume",
            "get_setting" to "Read system settings",
            "wifi_credentials" to "Read Wi-Fi password",
            "recent_notifications" to "Read recent notifications",
            "search_notification_history" to "Search notification history",
            "recent_app_activity" to "View app activity",
            "app_usage_summary" to "View app usage statistics",
            "get_current_location" to "Get current location",
            "get_device_environment" to "View device environment",
            "get_health_summary" to "View health summary",
            "read_sms_code" to "Read SMS verification code",
            "get_logcat" to "Read system logs",
            "search_media" to "Search media files",
            "search_audio" to "Search audio",
            "search_recordings" to "Search recordings",
            "search_files" to "Search files",
            "search_calendar_events" to "Search calendar events",
            "search_contacts" to "Search contacts",
            "search_call_history" to "Search call history",
            "search_messages" to "Search text messages",
            "search_downloads" to "Search downloads",
            "search_personal_orders" to "Search personal orders",
            "set_setting" to "Modify system settings",
            "set_device_state" to "Modify device status",
            "app_state_control" to "Manage app state",
        )
    }
}
