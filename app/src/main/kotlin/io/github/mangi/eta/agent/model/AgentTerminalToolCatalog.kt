package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentTerminalToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "terminal",
                    description = "Manage terminal sessions on the current device. environment=android runs Android system commands and root operations; environment=linux runs the Alpine or Debian environment selected by the user. Apktool build is unavailable until an ARM64 AAPT2 runtime is installed. Use open_and_exec for one-shot commands. Use open to create a persistent shell session and exec with session_id for multi-step work. Use async=true without session_id for long-running independent commands, then read_async_result with job_id to stream output chunks. Use daemon_start for services that must keep running after the Agent run (listening ports, web panels, watchers): the process detaches from any command shell, logs to a file, and survives until daemon_stop or device reboot. Manage daemons with daemon_list, daemon_logs and daemon_stop by task_id. Use close to stop jobs or close sessions.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("open")
                                                .put("exec")
                                                .put("open_and_exec")
                                                .put("read_async_result")
                                                .put("close")
                                                .put("daemon_start")
                                                .put("daemon_list")
                                                .put("daemon_logs")
                                                .put("daemon_stop")
                                        )
                                        .put("description", "open creates a session. exec runs command in a session or cwd. open_and_exec runs a one-shot command. read_async_result reads async output by job_id. close closes a session_id or job_id. daemon_start launches a detached long-lived service and returns task_id. daemon_list lists daemon tasks with liveness. daemon_logs tails a task log. daemon_stop terminates and removes a task.")
                                )
                                .put(
                                    "identity",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("user").put("root"))
                                        .put("description", "Host execution identity. Android defaults to the currently available identity; Linux uses user or root depending on the selected backend. Emulated root inside PRoot grants no Android privileges.")
                                )
                                .put(
                                    "environment",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("android").put("linux"))
                                        .put("description", "android uses the native Android shell with BusyBox applets when available. linux uses the Alpine or Debian environment selected in Eta settings. Default android.")
                                )
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Android shell command to execute. Required for exec/open_and_exec.")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Working directory. Defaults to /data/local/tmp/eta for android and /workspace for linux. Relative paths use the environment default. ~/ means /storage/emulated/0.")
                                )
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Command timeout in milliseconds. Default 30000, max 180000.")
                                )
                                .put(
                                    "merge_stderr",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether stderr should be appended to stdout in command responses.")
                                )
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Session id returned by action=open. Use with exec or close.")
                                )
                                .put(
                                    "job_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Async job id returned when async=true. Use with read_async_result or close.")
                                )
                                .put(
                                    "task_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Daemon task id returned by daemon_start. Use with daemon_logs or daemon_stop.")
                                )
                                .put(
                                    "async",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Start command in a separate background shell and return immediately with job_id. Do not combine with session_id. Use read_async_result to stream output.")
                                )
                                .put(
                                    "offset_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For read_async_result, read stdout from this character offset. Default 0.")
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For read_async_result, maximum stdout characters to return. Default 8000, max 16000.")
                                )
                                .put(
                                    "close_if_done",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "For read_async_result, remove the async job when it has completed.")
                                )
                        )
                        .put("required", JSONArray().put("action"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "run_command",
                    description = "Run commands on the Android device with a non-interactive root shell. Good for system info, package management, file inspection, and Linux command pipelines. Each call starts a fresh shell; do not run interactive or long-lived commands.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Shell command to execute; pipes and redirection may be used.")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Working directory, default /data/local/tmp/eta. Relative paths also resolve against it; user storage is available as ~/ for /storage/emulated/0.")
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Timeout in seconds, 1 to 180, default 30.")
                                )
                        )
                        .put("required", JSONArray().put("command"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "read_file",
                    description = "Read Android file contents; supports the selected Linux environment's /workspace, /var/minis, and minis:// paths, auto-mapped to host files. Good for configs, logs, and small text files; read large files in chunks with offset_bytes/max_bytes.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put(
                                    "offset_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Byte offset to start from, default 0.")
                                )
                                .put(
                                    "max_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Maximum bytes to read, 1 to 262144, default 65536.")
                                )
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "write_file",
                    description = "Write an Android file. Can overwrite or append; parent directories are created automatically. Use for tasks that clearly require modifying files.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("content", JSONObject().put("type", "string"))
                                .put(
                                    "append",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "true appends, false overwrites, default false.")
                                )
                        )
                        .put("required", JSONArray().put("path").put("content"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "list_directory",
                    description = "List directory contents; supports the selected Linux environment's /workspace, /var/minis, and minis:// paths, auto-mapped to host directories. Defaults to /data/local/tmp/eta with ls -l style output.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("show_hidden", JSONObject().put("type", "boolean"))
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Return at most 1 to 200 lines, default 80.")
                                )
                        )
                )
            )
    }

}
