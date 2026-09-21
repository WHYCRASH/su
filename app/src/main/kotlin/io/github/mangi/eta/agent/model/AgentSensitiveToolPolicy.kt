package io.github.mangi.eta.agent.model

/** Marks tools whose raw arguments or results must never enter the persistent session. */
internal object AgentSensitiveToolPolicy {
    fun isSensitive(toolName: String): Boolean =
        toolName.startsWith("mcp_") || toolName in sensitiveTools

    private val sensitiveTools = setOf(
        "delegate_task",
        "manage_agent_workspace",
        "workspace_file",
        "get_task_result",
        "cancel_task",
        "get_setting",
        "wifi_credentials",
        "recent_notifications",
        "search_notification_history",
        "recent_app_activity",
        "app_usage_summary",
        "get_current_location",
        "get_device_environment",
        "get_health_summary",
        "read_sms_code",
        "get_logcat",
        "search_media",
        "search_audio",
        "search_recordings",
        "search_files",
        "search_calendar_events",
        "search_contacts",
        "search_call_history",
        "search_messages",
        "search_downloads",
        "search_personal_orders",
        "read_image",
        "set_setting",
        "memory_get",
        "memory_write",
    )
}
