package io.github.mangi.eta.ui.preview

import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
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
                title = "Have su drive the phone",
                preview = "Ready to take over the screen and tools, waiting for the next task",
                timeLabel = "Now",
                mode = ConversationModeUi.PhoneAgent,
                isPinned = true,
                isActiveRun = true,
            ),
            ConversationSummaryUi(
                id = "c-002",
                title = "Today's plans and reminders",
                preview = "Check the weather, sync the calendar, and set a going-out reminder",
                timeLabel = "10:41",
                mode = ConversationModeUi.Chat,
                isPinned = true,
            ),
            ConversationSummaryUi(
                id = "c-003",
                title = "Open NetEase Cloud Music and play Daily Recommendations",
                preview = "Finished 4 tool calls in 8 seconds",
                timeLabel = "10:23",
                mode = ConversationModeUi.PhoneAgent,
            ),
            ConversationSummaryUi(
                id = "c-004",
                title = "Analyze the current page screenshot",
                preview = "Summarize the page structure and flag button-hierarchy issues",
                timeLabel = "Yesterday",
                mode = ConversationModeUi.Chat,
            ),
            ConversationSummaryUi(
                id = "c-005",
                title = "Inspect the Alpine terminal environment",
                preview = "List available commands, Python/Node versions, and background jobs",
                timeLabel = "Friday",
                mode = ConversationModeUi.Terminal,
            ),
            ConversationSummaryUi(
                id = "c-006",
                title = "Tidy the Downloads folder every night",
                preview = "Scan on a schedule and sort images, installer packages, and documents",
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
                content = "Open battery optimization in Settings",
            ),
            AgentMessageUi(
                id = "m-02",
                content = "The phone screen is showing the **Home screen**. Key details:\n\n| Item | Value |\n| --- | --- |\n| Time | 09:55 |\n| Network | 5G + Wi-Fi |\n| Battery | 68% |",
                usage = TokenUsageUi(
                    contextTokens = 17100,
                    inputTokens = 9000,
                    outputTokens = 111,
                    reasoningTokens = 8200,
                ),
            ),
            ThinkingMessageUi(
                id = "thinking-01",
                content = "The user wants me to look at the current screen. I need to call observe_screen first to get the screen structure, then summarize what is visible.",
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

    val tools = AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = "Screen actions",
                tools = listOf(
                    ToolItemUi("observe_screen", "Observe screen", "Read screen nodes, attaching the raw image when needed"),
                    ToolItemUi("tap_element", "Tap", "Tap given coordinates or an element"),
                    ToolItemUi("long_press", "Long press", "Long-press the given element"),
                    ToolItemUi("swipe", "Swipe", "Swipe, scroll, back, and other gestures"),
                ),
            ),
            ToolGroupUi(
                id = "input",
                title = "Input and keys",
                tools = listOf(
                    ToolItemUi("input_text", "Type text", "Type text at the focused field"),
                    ToolItemUi("paste_text", "Paste text", "Write text via the clipboard"),
                    ToolItemUi("wait_for_text", "Wait for text", "Wait until the given text appears on screen"),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = "Web browsing",
                tools = listOf(
                    ToolItemUi("browser_use", "Agent browser", "Browse off-screen while keeping the session take-over-able"),
                    ToolItemUi("browser_read", "Read page", "Extract the rendered page body text"),
                    ToolItemUi("browser_interact", "Page interaction", "Find, click, and type into elements"),
                    ToolItemUi("browser_screenshot", "Page screenshot", "Hand the page viewport to the vision model"),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = "Apps and URIs",
                tools = listOf(
                    ToolItemUi("get_current_context", "Time and location", "Read the system time and recent location"),
                    ToolItemUi("launch_app", "Open app", "Launch an app by package name"),
                    ToolItemUi("open_uri", "Open with app", "Hand off explicitly to an external app"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = "Terminal and files",
                tools = listOf(
                    ToolItemUi("terminal", "Terminal command", "Run shell in Android/Alpine/Debian environments"),
                ),
            ),
        ),
    )
}
