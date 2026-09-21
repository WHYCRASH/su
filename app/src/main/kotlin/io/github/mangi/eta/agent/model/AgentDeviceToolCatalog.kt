package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Structured schema for common device capabilities; whether to expose to the model is determined by risk group. */
internal object AgentDeviceToolCatalog {
    fun appendTo(
        tools: JSONArray,
        directTools: Boolean,
        sensitiveReadTools: Boolean,
        sensitiveActionTools: Boolean,
    ) {
        if (directTools) appendDirectTools(tools)
        if (sensitiveReadTools) appendSensitiveReadTools(tools)
        if (sensitiveActionTools) appendSensitiveActionTools(tools)
    }

    private fun appendDirectTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "set_alarm",
                    "Create a system alarm directly; do not use the GUI. For relative dates, first convert using get_current_context; hour/minute use device local time. When the system does not accept direct operations, it may only open the clock screen.",
                    properties(
                        "hour" to integer("0 to 23", 0, 23),
                        "minute" to integer("0 to 59", 0, 59),
                        "label" to string("Alarm label, up to 100 characters", 100),
                        "repeat_days" to stringArray(
                            "Repeat days of week; if omitted, only the next occurrence",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                        "vibrate" to boolean("Whether to vibrate, default true"),
                    ),
                    "hour", "minute",
                ),
            )
            .put(
                function(
                    "set_timer",
                    "Create a system timer directly; do not use the GUI. duration_seconds must be 1 to 86400 seconds.",
                    properties(
                        "duration_seconds" to integer("Timer duration in seconds", 1, 86_400),
                        "label" to string("Timer label, up to 100 characters", 100),
                    ),
                    "duration_seconds",
                ),
            )
            .put(emptyFunction("device_status", "Read battery, memory, storage, system version, and uptime."))
            .put(emptyFunction("network_info", "Read the current network type, network validation status, and basic information about the current Wi‑Fi; does not return saved passwords."))
            .put(limitFunction("top_memory_apps", "List the processes with the highest memory usage by current RSS."))
            .put(limitFunction("top_storage_apps", "List the apps with the highest storage usage by combined app, data, and cache size."))
            .put(
                function(
                    "media_control",
                    "Control the current media session directly; do not operate the player GUI.",
                    properties(
                        "action" to enumString(
                            "Media action",
                            "play", "pause", "play_pause", "next", "previous", "stop",
                        ),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "set_volume",
                    "Set system volume directly; do not operate the volume GUI.",
                    properties(
                        "stream" to enumString("Volume stream", "media", "alarm", "ring", "notification"),
                        "percent" to integer("Volume percentage from 0 to 100", 0, 100),
                    ),
                    "stream", "percent",
                ),
            )
    }

    private fun appendSensitiveReadTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "get_setting",
                    "Read an Android Settings value. The result may contain sensitive information such as device identifiers; the raw result is not persisted.",
                    properties(
                        "namespace" to enumString("Settings namespace", "system", "secure", "global"),
                        "key" to string("Exact settings key", 200),
                    ),
                    "namespace", "key",
                ),
            )
            .put(
                function(
                    "wifi_credentials",
                    "Read the Wi‑Fi names and passwords saved on the phone. The raw result is not persisted.",
                    properties(
                        "ssid" to string("Optional exact Wi‑Fi name", 128),
                        "limit" to integer("Maximum number to return, default 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_notifications",
                    "Read the titles and bodies of notifications in the current notification shade. The result is not written to the persistent session.",
                    properties(
                        "package_name" to string("Optional exact app package name filter", 255),
                        "limit" to integer("Maximum number to return, default 10", 1, 20),
                    ),
                ),
            )
            .put(
                function(
                    "search_notification_history",
                    "Retrieve notifications from the past 7 days recorded by Eta after the user grants notification access. The raw result is not written to the persistent session.",
                    properties(
                        "query" to string("Optional keyword in title or body", 200),
                        "package_name" to string("Optional exact package name", 255),
                        "max_age_hours" to integer("Lookback hours, default 24", 1, 168),
                        "limit" to integer("Maximum number to return, default 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_app_activity",
                    "Read the chronological order of recently opened apps; requires system usage access.",
                    properties(
                        "package_name" to string("Optional exact package name", 255),
                        "max_age_hours" to integer("Lookback hours, default 24", 1, 168),
                        "limit" to integer("Maximum number to return, default 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "app_usage_summary",
                    "Summarize recent app usage by foreground time; requires system usage access.",
                    properties(
                        "max_age_hours" to integer("Aggregation hours, default 24", 1, 168),
                        "limit" to integer("Maximum number to return, default 20", 1, 50),
                    ),
                ),
            )
            .put(emptyFunction("get_current_location", "Read the system's existing recent location without continuously listening or actively waking GPS."))
            .put(emptyFunction("get_device_environment", "Read lock screen, Do Not Disturb, ringer, audio output, and external display status."))
            .put(
                function(
                    "get_health_summary",
                    "Summarize steps, sleep, exercise, heart rate, weight, and blood oxygen from system health data; does not return raw measurement series.",
                    properties("days" to integer("Number of recent days to summarize, default 7", 1, 30)),
                ),
            )
            .put(
                function(
                    "read_sms_code",
                    "Extract only 4- to 8-digit verification codes, sender, and time from recent SMS messages; does not return full message content.",
                    properties(
                        "max_age_minutes" to integer("Only check messages within this many minutes, default 10", 1, 1_440),
                    ),
                ),
            )
            .put(
                function(
                    "get_logcat",
                    "Read recent system logs. query only performs text filtering on logs already read and never enters Shell.",
                    properties(
                        "query" to string("Optional filter text", 200),
                        "max_lines" to integer("Maximum number of log lines, default 200", 20, 500),
                    ),
                ),
            )
            .put(searchFunction("search_media", "Search images in the local photo gallery, filterable by file name or album path. Returns metadata and openable content URIs without reading image contents."))
            .put(searchFunction("search_audio", "Search local music and audio files, filterable by title or file name."))
            .put(searchFunction("search_recordings", "Search local recording files. Results come from the system media library and do not read recording transcripts or audio content."))
            .put(searchFunction("search_files", "Search documents and downloads in shared storage, filterable by file name. Does not traverse other apps' private directories."))
            .put(searchFunction("search_calendar_events", "Search system calendar events, filterable by title, location, or description."))
            .put(searchFunction("search_contacts", "Search system contacts and return names and lookup URIs."))
            .put(searchFunction("search_call_history", "Search call logs, filterable by number or cached contact name."))
            .put(searchFunction("search_messages", "Search SMS messages, filterable by sender or message text keyword. Results are sensitive personal content."))
            .put(searchFunction("search_downloads", "Search system download records, filterable by file name or description."))
            .put(searchFunction("search_personal_orders", "Search food delivery, shopping, package delivery, ticket, and travel orders recognized in the saved notification history."))
    }

    private fun appendSensitiveActionTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "set_setting",
                    "Modify an Android Settings value.",
                    properties(
                        "namespace" to enumString("Settings namespace", "system", "secure", "global"),
                        "key" to string("Exact settings key", 200),
                        "value" to string("New value", 2_000),
                    ),
                    "namespace", "key", "value",
                ),
            )
            .put(
                function(
                    "set_device_state",
                    "Directly enable or disable Wi‑Fi/Bluetooth; do not operate the settings GUI.",
                    properties(
                        "target" to enumString("Device capability", "wifi", "bluetooth"),
                        "enabled" to boolean("true to enable, false to disable"),
                    ),
                    "target", "enabled",
                ),
            )
            .put(
                function(
                    "app_state_control",
                    "Stop, freeze, or unfreeze an exact package name, including system apps.",
                    properties(
                        "package_name" to string("Exact Android package name", 255),
                        "action" to enumString("Action", "force_stop", "freeze", "unfreeze"),
                    ),
                    "package_name", "action",
                ),
            )
    }

    private fun emptyFunction(name: String, description: String): JSONObject =
        function(name, description, properties())

    private fun limitFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            properties("limit" to integer("Maximum number to return, default 10", 1, 30)),
        )

    private fun searchFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            properties(
                "query" to string("Optional keyword, up to 200 characters"),
                "limit" to integer("Maximum number to return, default 10", 1, 30),
            ),
        )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject =
        AgentToolSchema.function(
            name = name,
            description = description,
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .also { schema ->
                    if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
                },
        )

    private fun properties(vararg entries: Pair<String, JSONObject>): JSONObject =
        JSONObject().also { target -> entries.forEach { (name, schema) -> target.put(name, schema) } }

    private fun string(description: String, maxLength: Int? = null): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .also { schema -> maxLength?.let { schema.put("maxLength", it) } }

    private fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun integer(description: String, minimum: Int, maximum: Int): JSONObject =
        JSONObject()
            .put("type", "integer")
            .put("minimum", minimum)
            .put("maximum", maximum)
            .put("description", description)

    private fun enumString(description: String, vararg values: String): JSONObject =
        string(description).put("enum", JSONArray(values.toList()))

    private fun stringArray(description: String, vararg values: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("items", enumString(description, *values))
            .put("uniqueItems", true)
            .put("maxItems", 7)
            .put("description", description)
}
