package io.github.mangi.eta.ui.preview

import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi

internal object FakeAgentUiStates {

    val conversations = ConversationPaneUiState(
        selectedConversationId = "c-001",
        searchQuery = "",
        conversations = listOf(
            ConversationSummaryUi(
                id = "c-001",
                title = "Let Eta operate phone",
                preview = "Preparing screen and tools, waiting for next task",
                timeLabel = "Now",
                mode = ConversationModeUi.PhoneAgent,
                isPinned = true,
                isActiveRun = true,
            ),
            ConversationSummaryUi(
                id = "c-002",
                title = "Today schedules and reminders",
                preview = "Query weather, sync schedule, and set departure reminder",
                timeLabel = "10:41",
                mode = ConversationModeUi.Chat,
                isPinned = true,
            ),
            ConversationSummaryUi(
                id = "c-003",
                title = "Open NetEase Cloud Music and play daily recommendation",
                preview = "Completed 4 tool calls in 8s",
                timeLabel = "10:23",
                mode = ConversationModeUi.PhoneAgent,
            ),
            ConversationSummaryUi(
                id = "c-004",
                title = "Analyze current page screenshot",
                preview = "Summarize page structure and point out button hierarchy issues",
                timeLabel = "Yesterday",
                mode = ConversationModeUi.Chat,
            ),
            ConversationSummaryUi(
                id = "c-005",
                title = "Check Alpine terminal environment",
                preview = "List available commands, Python/Node versions, and background jobs",
                timeLabel = "Friday",
                mode = ConversationModeUi.Terminal,
            ),
            ConversationSummaryUi(
                id = "c-006",
                title = "Organize Downloads folder every night",
                preview = "Periodically scan files and organize images, packages, and documents",
                timeLabel = "5-18",
                mode = ConversationModeUi.Automation,
            ),
        ),
    )

    val chatHome: AgentChatHomeUiState = AgentChatHomeUiState(
        messages = emptyList(),
        input = "",
        isStreaming = false,
        thinkingEnabled = false,
    )

    val chat = AgentChatUiState(
        messages = listOf(
            UserMessageUi(
                id = "m-01",
                content = "Open Battery Optimization in Settings",
            ),
            AgentMessageUi(
                id = "m-02",
                content = "当前手机屏幕显示的是**主界面**，关键信息如下：\n\n| 项目 | 内容 |\n| --- | --- |\n| 时间 | 09:55 |\n| 网络 | 5G + Wi-Fi |\n| 电量 | 68% |",
                usage = TokenUsageUi(
                    contextTokens = 17100,
                    inputTokens = 9000,
                    outputTokens = 111,
                    reasoningTokens = 8200,
                ),
            ),
            ThinkingMessageUi(
                id = "thinking-01",
                content = "用户希望我View当前屏幕，需要先调用 observe_screen 获取屏幕结构，再总结可见信息。",
                isStreaming = false,
                elapsedSeconds = 24,
                collapsed = true,
            ),
            ToolActivityMessageUi(
                id = "tools-01",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{\"include_screenshot\":true}",
                resultSummary = "ok=true, chars=1820, images=1",
                imageCount = 1,
            ),
        ),
        input = "",
        isStreaming = false,
        thinkingEnabled = true,
    )

    val permissionHealth = PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "accessibility",
                title = "Accessibility Service",
                summary = "Enabled, Agent can operate UI",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = "Overlay Permission",
                summary = "Granted, overlay window can be displayed",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "notification",
                title = "Notification Permission",
                summary = "Used for background task completion reminders",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "location",
                title = "Location Permission",
                summary = "Read only when Agent invokes location tools",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "root",
                title = "Root Permission",
                summary = "Not granted, some terminal commands restricted",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "Check",
            ),
            PermissionHealthItemUi(
                id = "shizuku",
                title = "Shizuku",
                summary = "Not configured, ADB-level features unavailable",
                status = PermissionStatusUi.Disabled,
                primaryActionLabel = "Configure",
            ),
            PermissionHealthItemUi(
                id = "xposed",
                title = "Hook / Xposed",
                summary = "Framework inactive, system enhancements unavailable",
                status = PermissionStatusUi.Missing,
                primaryActionLabel = "View",
            ),
            PermissionHealthItemUi(
                id = "background",
                title = "Background Keep-alive",
                summary = "Battery optimization not disabled, long tasks may be interrupted",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "Settings",
            ),
        ),
    )

    val tools = AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = "Screen Operations",
                tools = listOf(
                    ToolItemUi("observe_screen", "Observe Screen", "Read UI nodes, attaching screenshot if needed"),
                    ToolItemUi("tap_element", "Tap", "Tap指定坐标或元素"),
                    ToolItemUi("long_press", "Long Press", "Long Press指定元素"),
                    ToolItemUi("swipe", "Swipe", "Swipe、滚动、返回等手势"),
                ),
            ),
            ToolGroupUi(
                id = "input",
                title = "Input &amp; Keys",
                tools = listOf(
                    ToolItemUi("input_text", "Input Text", "Type text at input focus"),
                    ToolItemUi("paste_text", "Paste Text", "Write text via clipboard"),
                    ToolItemUi("wait_for_text", "Wait for Text", "Wait for specified text on screen"),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = "Web Browsing",
                tools = listOf(
                    ToolItemUi("browser_use", "Agent Browser", "Offscreen browsing with take-over session"),
                    ToolItemUi("browser_read", "Read Web Page", "Extract rendered web page text body"),
                    ToolItemUi("browser_interact", "Web Interaction", "查找、Tap和输入元素"),
                    ToolItemUi("browser_screenshot", "Page Screenshot", "Provide page viewport to vision model"),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = "App &amp; URI",
                tools = listOf(
                    ToolItemUi("get_current_context", "Time &amp; Location", "Read system time and recent location"),
                    ToolItemUi("launch_app", "Launch App", "Launch app by package name"),
                    ToolItemUi("open_uri", "Open with App", "Explicitly pass to external app"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = "Terminal &amp; Files",
                tools = listOf(
                    ToolItemUi("terminal", "Terminal Command", "Execute shell in Android/Alpine/Debian"),
                ),
            ),
        ),
    )
}
