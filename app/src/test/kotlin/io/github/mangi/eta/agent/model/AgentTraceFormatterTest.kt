package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceFormatterTest {
    private val formatter = AgentTraceFormatter()

    @Test
    fun sensitiveToolArgumentsAreSummarizedWithoutRawValues() {
        val cases = listOf(
            RedactionCase(
                toolName = "terminal",
                argumentsJson =
                    """{"action":"open_and_exec","identity":"root","command":"echo bearer-secret"}""",
                expectedParts = listOf("Terminal", "Run once", "Android", "root"),
                sensitiveParts = listOf("echo bearer-secret", "bearer-secret"),
            ),
            RedactionCase(
                toolName = "run_command",
                argumentsJson = """{"command":"cat /data/local/tmp/private-token"}""",
                expectedParts = listOf("Run command", "Android", "root"),
                sensitiveParts = listOf("cat ", "/data/local/tmp/private-token", "private-token"),
            ),
            RedactionCase(
                toolName = "write_file",
                argumentsJson =
                    """{"path":"/data/local/tmp/secret.txt","content":"api-key-value"}""",
                expectedParts = listOf("Write file", "characters"),
                sensitiveParts = listOf("/data/local/tmp/secret.txt", "api-key-value"),
            ),
            RedactionCase(
                toolName = "input_text",
                argumentsJson = """{"text":"one-time-password-123456"}""",
                expectedParts = listOf("Type text", "characters"),
                sensitiveParts = listOf("one-time-password-123456", "123456"),
            ),
            RedactionCase(
                toolName = "read_file",
                argumentsJson = """{"path":"/data/user/0/example/private.xml"}""",
                expectedParts = listOf("Read file"),
                sensitiveParts = listOf("/data/user/0/example/private.xml", "private.xml"),
            ),
            RedactionCase(
                toolName = "list_directory",
                argumentsJson = """{"path":"/storage/emulated/0/Private"}""",
                expectedParts = listOf("List directory"),
                sensitiveParts = listOf("/storage/emulated/0/Private"),
            ),
            RedactionCase(
                toolName = "memory_get",
                argumentsJson = """{"query":"private relationship"}""",
                expectedParts = listOf("Search memory"),
                sensitiveParts = listOf("private relationship"),
            ),
            RedactionCase(
                toolName = "memory_write",
                argumentsJson = """{"mode":"append","revision":"secret-revision","content":"private memory"}""",
                expectedParts = listOf("Update memory", "Append", "lines", "bytes"),
                sensitiveParts = listOf("secret-revision", "private memory"),
            ),
        )

        cases.forEach { case ->
            val summary = formatter.summarizeArguments(
                AgentModelClient.ToolCall(
                    id = "call-test",
                    name = case.toolName,
                    argumentsJson = case.argumentsJson,
                )
            )

            case.expectedParts.forEach { expected ->
                assertTrue(
                    "${case.toolName} summary must contain '$expected': $summary",
                    summary.contains(expected),
                )
            }
            case.sensitiveParts.forEach { sensitive ->
                assertFalse(
                    "${case.toolName} summary leaked '$sensitive': $summary",
                    summary.contains(sensitive),
                )
            }
        }
    }

    @Test
    fun runCommandSummaryReflectsRealEnvironment() {
        val debian = AgentModelClient.ToolCall(
            id = "rc-d",
            name = "run_command",
            argumentsJson = """{"command":"gh --version","environment":"debian","identity":"root"}""",
        )
        val android = AgentModelClient.ToolCall(
            id = "rc-a",
            name = "run_command",
            argumentsJson = """{"command":"pm list packages","identity":"root"}""",
        )
        assertTrue(formatter.summarizeArguments(debian).contains("Debian"))
        assertTrue(formatter.summarizeArguments(debian).contains("root"))
        assertFalse(formatter.summarizeArguments(debian).contains("Android"))
        assertTrue(formatter.summarizeArguments(android).contains("Android"))
        assertFalse(formatter.summarizeArguments(android).contains("gh --version"))
    }

    @Test
    fun terminalExecSummaryUsesSessionEnvironmentWhenOmitted() {
        val sessionFormatter = AgentTraceFormatter(
            terminalSessionEnvironmentProvider = { id ->
                if (id == "term_linux") "debian" else null
            },
            terminalSessionIdentityProvider = { id ->
                if (id == "term_linux") "root" else null
            },
        )
        val exec = AgentModelClient.ToolCall(
            id = "term-exec",
            name = "terminal",
            argumentsJson = """{"action":"exec","session_id":"term_linux","command":"gh run list"}""",
        )
        val summary = sessionFormatter.summarizeArguments(exec)
        assertTrue(summary.contains("Debian"))
        assertTrue(summary.contains("root"))
        assertFalse(summary.contains("Android"))
        assertFalse(summary.contains("gh run list"))
        assertTrue(
            formatter.summarizeArguments(exec).contains("Android"),
        )
    }

    @Test
    fun terminalCommandsAreExposedOnlyThroughDisplayField() {
        val terminal = AgentModelClient.ToolCall(
            id = "terminal-call",
            name = "terminal",
            argumentsJson =
                """{"action":"open_and_exec","environment":"linux","command":"git status --short"}""",
        )
        val runCommand = AgentModelClient.ToolCall(
            id = "run-command-call",
            name = "run_command",
            argumentsJson = """{"command":"pm list packages | head"}""",
        )

        assertEquals("git status --short", formatter.displayCommand(terminal))
        assertEquals("pm list packages | head", formatter.displayCommand(runCommand))
        assertTrue(formatter.summarizeArguments(terminal).contains("Linux"))
        assertFalse(formatter.summarizeArguments(terminal).contains("git status"))
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall("observe", "observe_screen", "{}")
            )
        )
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall(
                    "oversized",
                    "run_command",
                    """{"command":"${"x".repeat(4_001)}"}""",
                )
            )
        )
    }

    @Test
    fun displayedTerminalCommandsRedactCommonCredentialForms() {
        val command = """export API_KEY='sk-secret'; curl -H 'Authorization: Bearer token-value' --password hunter2 https://example.com?access_token=query-secret"""
        val displayed = formatter.displayCommand(
            AgentModelClient.ToolCall(
                id = "secret-command",
                name = "run_command",
                argumentsJson = JSONObject().put("command", command).toString(),
            )
        )!!

        assertTrue(displayed.contains("API_KEY=<hidden>"))
        assertTrue(displayed.contains("Authorization: <hidden>"))
        assertTrue(displayed.contains("--password <hidden>"))
        assertTrue(displayed.contains("access_token=<hidden>"))
        assertFalse(displayed.contains("sk-secret"))
        assertFalse(displayed.contains("token-value"))
        assertFalse(displayed.contains("hunter2"))
        assertFalse(displayed.contains("query-secret"))
    }

    @Test
    fun malformedSensitiveArgumentsUseSafeFallback() {
        val summary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "call-test",
                name = "terminal",
                argumentsJson = "{secret-command",
            )
        )

        assertTrue(summary.contains("Terminal"))
        assertFalse(summary.contains("secret-command"))
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall("call-test", "terminal", "{secret-command")
            )
        )
    }

    @Test
    fun screenObservationSummaryUsesTreeFirstDefaults() {
        val defaultSummary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "observe-default",
                name = "observe_screen",
                argumentsJson = "{}",
            ),
        )
        val screenshotSummary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "observe-image",
                name = "observe_screen",
                argumentsJson = """{"include_screenshot":true}""",
            ),
        )

        assertTrue(defaultSummary.contains("Observe screen"))
        assertTrue(defaultSummary.contains("Includes UI tree"))
        assertFalse(defaultSummary.contains("Includes screenshot"))
        assertTrue(screenshotSummary.contains("Includes screenshot"))
        assertTrue(screenshotSummary.contains("Includes UI tree"))
    }

    @Test
    fun browserResultSummaryIsHumanReadable() {
        val success = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"action":"navigate","page":{"url":"https://example.com/path?token=secret-query","title":"Example Domain"}}""",
            ),
        )

        assertTrue(success.contains("Opened"))
        assertTrue(success.contains("example.com"))
        assertTrue(success.contains("\"Example Domain\""))
        assertFalse(success.contains("ok="))
        assertFalse(success.contains("token=secret-query"))

        val failure = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"USER_CONTROL_ACTIVE","action":"click"}""",
            ),
        )

        assertTrue(failure.startsWith("Failed"))
        assertTrue(failure.contains("code=USER_CONTROL_ACTIVE"))

        val readable = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"action":"get_readable","text_chars":23000,"truncated":true,"page":{"url":"https://example.com"}}""",
            ),
        )

        assertTrue(readable.contains("Content extracted"))
        assertTrue(readable.contains("About 2.30k characters"))
        assertTrue(readable.contains("Truncated"))
    }

    @Test
    fun resultSuccessComesFromOkFlagNotSummaryText() {
        val ok = AgentModelClient.ToolResult(content = """{"ok":true}""")
        val failed = AgentModelClient.ToolResult(content = """{"ok":false,"code":"X"}""")
        val noFlag = AgentModelClient.ToolResult(content = """{"data":1}""")
        val malformed = AgentModelClient.ToolResult(content = "{broken")

        assertTrue(formatter.isSuccessResult(ok))
        assertFalse(formatter.isSuccessResult(failed))
        assertTrue(formatter.isSuccessResult(noFlag))
        assertTrue(formatter.isSuccessResult(malformed))
        assertEquals("Done", formatter.summarizeResult("tap", ok))
        assertEquals("Failed · code=X", formatter.summarizeResult("tap", failed))
    }

    @Test
    fun memoryResultSummaryContainsOnlyStatusLineAndByteMetadata() {
        val summary = formatter.summarizeResult(
            "memory_get",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"bytes":321,"line_count":9,"content":"private memory"}""",
                sensitive = true,
            ),
        )

        assertTrue(summary.contains("Memory read"))
        assertTrue(summary.contains("9 lines"))
        assertTrue(summary.contains("321 bytes"))
        assertFalse(summary.contains("ok="))
        assertFalse(summary.contains("private memory"))
    }

    @Test
    fun searchQueryIsShownInSummary() {
        val searchApps = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-apps",
                name = "search_apps",
                argumentsJson = """{"query":"TikTok"}""",
            ),
        )
        assertEquals("Search apps · TikTok", searchApps)

        val searchFiles = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-files",
                name = "search_files",
                argumentsJson = """{"query":"weekly report"}""",
            ),
        )
        assertEquals("Search files · weekly report", searchFiles)

        // Single-line and truncate keywords so they don't blow up the collapsed row title
        val longQuery = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-long",
                name = "search_apps",
                argumentsJson = """{"query":"${"very-long keyword".repeat(10)}\nsecond line"}""",
            ),
        )
        assertFalse(longQuery.contains("\n"))
        assertTrue(longQuery.length <= "Search apps · ".length + 30 + 3)

        // Non-search device tools don't append the query
        val deviceStatus = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "device-status",
                name = "device_status",
                argumentsJson = """{"query":"must not appear"}""",
            ),
        )
        assertEquals("View device status", deviceStatus)
    }

    @Test
    fun searchAppsResultListsAppNames() {
        val summary = formatter.summarizeResult(
            "search_apps",
            AgentModelClient.ToolResult(
                content = """
                    {"ok":true,"tool":"search_apps","query":"TikTok","apps":[
                      {"app_name":"TikTok","package_name":"com.ss.android.ugc.aweme","is_system_app":false},
                      {"app_name":"TikTok Lite","package_name":"com.ss.android.ugc.aweme.lite","is_system_app":false},
                      {"app_name":"TikTok Shop","package_name":"com.ss.android.ugc.livelite","is_system_app":false},
                      {"app_name":"TikTok Live","package_name":"com.ss.android.ugc.live","is_system_app":false}]}
                """.trimIndent(),
            ),
        )

        assertTrue(summary.contains("Found 4 apps"))
        assertTrue(summary.contains("TikTok, TikTok Lite, TikTok Shop"))
        assertTrue(summary.contains("etc."))
        assertFalse(summary.contains("com.ss.android"))

        val empty = formatter.summarizeResult(
            "search_apps",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"search_apps","query":"a nonexistent app","apps":[]}""",
            ),
        )
        assertEquals("No matching app found", empty)
    }

    @Test
    fun launchAppResultShowsAppName() {
        val summary = formatter.summarizeResult(
            "launch_app",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"launch_app","app_name":"TikTok","package_name":"com.ss.android.ugc.aweme"}""",
            ),
        )

        assertEquals("Opened · TikTok", summary)
    }

    @Test
    fun terminalResultShowsExitCodeAndOutputPreview() {
        val success = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"run_command","exit_code":0,"stdout":"hello\nworld","stderr":"","timed_out":false}""",
            ),
        )
        assertTrue(success.startsWith("Execution complete"))
        assertTrue(success.contains("hello\nworld"))

        val failure = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"tool":"terminal","action":"exec","exit_code":1,"stdout":"","stderr":"Permission denied"}""",
            ),
        )
        assertTrue(failure.startsWith("Failed · Exit code 1"))
        assertTrue(failure.contains("Permission denied"))

        val timedOut = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"tool":"run_command","exit_code":-2,"timed_out":true,"stdout":""}""",
            ),
        )
        assertEquals("Failed · Execution timed out", timedOut)

        // Protocol errors still keep the code= marker
        val coded = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"JOB_NOT_FOUND"}""",
            ),
        )
        assertEquals("Failed · code=JOB_NOT_FOUND", coded)

        // Errors with a localized reason: the reason faces users, code= stays for log extraction
        val codedWithMessage = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"TERMINAL_TOOLS_DISABLED","message":"Enable terminal/file tools first"}""",
            ),
        )
        assertEquals("Failed · Enable terminal/file tools first · code=TERMINAL_TOOLS_DISABLED", codedWithMessage)

        // Session action with no output
        val closed = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"terminal","action":"close","closed_session":true}""",
            ),
        )
        assertEquals("Terminal · Close terminal", closed)
    }

    @Test
    fun failureSummaryShowsHumanReasonAndKeepsCodeMarker() {
        val summary = formatter.summarizeResult(
            "wait_for_text",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"TIMEOUT","message":"Timed out waiting for text: TikTok"}""",
            ),
        )
        assertEquals("Failed · Timed out waiting for text: TikTok · code=TIMEOUT", summary)

        val codeOnly = formatter.summarizeResult(
            "tap_element",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"INVALID_NODE_INDEX"}""",
            ),
        )
        assertEquals("Failed · code=INVALID_NODE_INDEX", codeOnly)
    }

    @Test
    fun terminalResultPreviewIsBounded() {
        val longOutput = (1..10).joinToString("\n") { "line-$it-${"x".repeat(100)}" }
        val summary = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", "run_command")
                    .put("exit_code", 0)
                    .put("stdout", longOutput)
                    .put("stdout_truncated", true)
                    .toString(),
            ),
        )

        val previewLines = summary.lines()
        // Status line + up to 3 preview lines + ellipsis marker
        assertTrue(previewLines.size <= 5)
        assertTrue(summary.endsWith("…"))
        assertTrue(summary.length < longOutput.length)
    }

    private data class RedactionCase(
        val toolName: String,
        val argumentsJson: String,
        val expectedParts: List<String>,
        val sensitiveParts: List<String>,
    )
}
