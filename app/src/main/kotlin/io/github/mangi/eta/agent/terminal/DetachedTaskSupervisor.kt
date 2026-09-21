package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.device.RootSu
import io.github.mangi.eta.core.AgentLogger

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** A daemon task record. pid + token support reclaiming after an app restart and guard against PID reuse before stopping. */
internal data class DetachedTask(
    val id: String,
    val pid: Long,
    val token: String,
    val command: String,
    val cwd: String,
    val identity: String,
    val environment: TerminalEnvironment,
    val logPath: String,
    val startedAt: Long,
    val backend: LinuxExecutionBackend = LinuxExecutionBackend.CHROOT,
    val hostWorkspace: String? = null,
)

internal data class DetachedTaskStatus(
    val task: DetachedTask,
    val running: Boolean,
)

internal sealed interface DaemonStartResult {
    data class Started(val task: DetachedTask) : DaemonStartResult
    data class Failed(val code: String, val message: String) : DaemonStartResult
}

internal data class DaemonLogsResult(
    val ok: Boolean,
    val text: String = "",
    val truncated: Boolean = false,
    val code: String = "",
    val message: String = "",
)

/**
 * Detached-task host: starts long-lived processes outside any command-session process group and manages their lifecycle.
 *
 * The difference from ordinary terminal commands is the reclamation semantics: the managed shell cleans up
 * same-group processes before exiting, while daemon tasks break out of that net via setsid, redirect output
 * to a workspace log file instead of memory, persist the task record to disk, and reclaim still-live
 * processes by pid + ownership token after an app restart.
 *
 * All tasks are invalid after a phone reboot; when the app is force-stopped, user-identity tasks are
 * terminated along with it, while root-identity tasks are unaffected.
 */
internal class DetachedTaskSupervisor(
    private val logger: AgentLogger,
    private val recordsFile: File,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val daemonDir: String = DEFAULT_DAEMON_DIR,
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
    private val acquireUserLease: (String, () -> Unit) -> Boolean = TerminalRuntime::acquireUserTask,
    private val releaseUserLease: (String) -> Unit = TerminalRuntime::releaseUserTask,
    private val skillsDirectoryProvider: () -> File? = TerminalRuntime::visibleSkillsDirectory,
) {
    companion object {
        const val DEFAULT_DAEMON_DIR = "/data/local/tmp/eta/daemon"
        const val LINUX_DAEMON_DIR = "/workspace/daemon"
        const val MAX_TASKS = 8
        const val MAX_RETAINED_RECORDS = 32
        const val MAX_LOG_READ_BYTES = 64 * 1024

        // Only one instance each is allowed on the AI side and the UI side in-process; record file read-modify-write must be serialized on one lock.
        private val RECORDS_LOCK = Any()
        private val USER_WAITERS = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** The AI tool side and the UI side share one record file; its path may only be defined once here. */
        fun defaultRecordsFile(context: Context): File =
            File(context.filesDir, "terminal-daemons.json")
    }

    private val oneShotSupervisor = ShellProcessSupervisor(rootAvailable = rootAvailable, skillsDirectoryProvider = skillsDirectoryProvider)

    /** command/cwd/identity/environment must already be normalized by the caller. */
    fun start(
        command: String,
        cwd: String,
        identity: String,
        environment: TerminalEnvironment,
    ): DaemonStartResult {
        if (identity != "root" && identity != "user") return DaemonStartResult.Failed("INVALID_ARGUMENT", "Invalid execution identity")
        if (identity == "root" && !rootAvailable()) return DaemonStartResult.Failed("ROOT_REQUIRED", "Root access is unavailable")
        if (identity == "user" && environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath(environment)) != LinuxExecutionBackend.PROOT) {
            return DaemonStartResult.Failed("LINUX_ENVIRONMENT_REQUIRES_ROOT", "The selected Linux environment requires root")
        }
        if (identity == "root" && environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath(environment)) == LinuxExecutionBackend.PROOT) {
            return DaemonStartResult.Failed("INVALID_IDENTITY", "Rootless Linux uses the regular app identity")
        }
        if (list().count { it.running } >= MAX_TASKS) {
            return DaemonStartResult.Failed("MAX_TASKS_REACHED", "Daemon task limit reached ($MAX_TASKS); stop an unused task first")
        }
        val prepared = try { LongShellCommand.prepare(command, environment, rootfsPath(environment)) }
        catch (_: Exception) { return DaemonStartResult.Failed("SCRIPT_WRITE_FAILED", "Could not prepare the command file") }
        val result = try { startPrepared(command, requireNotNull(prepared.command), cwd, identity, environment) }
        catch (e: Exception) { prepared.file?.delete(); throw e }
        if (result is DaemonStartResult.Failed) prepared.file?.delete()
        return result
    }

    private fun startPrepared(originalCommand: String, command: String, cwd: String, identity: String, environment: TerminalEnvironment): DaemonStartResult {
        val id = "dm_" + UUID.randomUUID().toString().take(8)
        val token = UUID.randomUUID().toString().replace("-", "")
        if (identity == "user") return startUserDaemon(id, token, command, cwd, environment, originalCommand)
        val wireDir = wireDaemonDir(environment)
        val wirePidFile = "$wireDir/$id.pid"
        val wireLogFile = "$wireDir/$id.log"
        // The inner sh writes the pidfile before execing the target command, so the PID is unchanged across exec.
        val innerScript = "echo \$\$ > ${shellQuote(wirePidFile)}; " +
            "export $ETA_PROCESS_OWNER_ENV=${shellQuote(token)}; " +
            "exec sh -c ${shellQuote(command)} >> ${shellQuote(wireLogFile)} 2>&1"
        val launcherScript = buildString {
            appendLine("mkdir -p ${shellQuote(wireDir)} || exit 1")
            appendLine("rm -f ${shellQuote(wirePidFile)}")
            appendLine("cd ${shellQuote(cwd)} || exit 1")
            // Both branches must background: setsid only detaches the session, while the caller still waits in the foreground for the long-lived command to finish.
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid sh -c ${shellQuote(innerScript)} < /dev/null &")
            appendLine("else")
            appendLine("  sh -c ${shellQuote(innerScript)} < /dev/null &")
            appendLine("fi")
            appendLine("i=0")
            appendLine("while [ \$i -lt 30 ]; do")
            appendLine("  [ -s ${shellQuote(wirePidFile)} ] && break")
            appendLine("  i=\$((i+1))")
            appendLine("  sleep 0.1")
            appendLine("done")
            append("cat ${shellQuote(wirePidFile)} 2>/dev/null")
        }
        // The launcher must run bare: the managed shell's wait would block on already-backgrounded children, and its pre-exit same-group cleanup would kill them.
        val result = launchRaw(identity, environment, launcherScript, timeoutSeconds = 10)
        val outputText = result.output.decodeToString().trim()
        val pid = outputText.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.toLongOrNull()
        if (result.exitCode != 0 || pid == null || pid <= 1) {
            val message = outputText.ifBlank { "exit=${result.exitCode}" }
            logger.warn(
                "Agent terminal daemon action=start outcome=failed environment=${environment.wireName} " +
                    "exitCode=${result.exitCode} errorChars=${message.length}"
            )
            return DaemonStartResult.Failed("DAEMON_START_FAILED", message)
        }
        val task = DetachedTask(
            id = id,
            pid = pid,
            token = token,
            command = originalCommand,
            cwd = cwd,
            identity = identity,
            environment = environment,
            logPath = "$wireDir/$id.log",
            startedAt = System.currentTimeMillis(),
        )
        if (!synchronized(RECORDS_LOCK) { saveTasksLocked(loadTasksLocked() + task) }) {
            stopTaskProcess(task)
            return DaemonStartResult.Failed("RECORDS_WRITE_FAILED", "Could not save the background task; check internal storage")
        }
        logger.info(
            "Agent terminal daemon action=start outcome=started taskId=$id " +
                "environment=${environment.wireName} identity=$identity commandChars=${command.length}"
        )
        return DaemonStartResult.Started(task)
    }

    /** Reconciles every record: live ones are reclaimed, dead ones are kept for log viewing, and excess exited records are pruned. */
    fun list(): List<DetachedTaskStatus> {
        val tasks = synchronized(RECORDS_LOCK) { loadTasksLocked() }
        if (tasks.isEmpty()) return emptyList()
        // Probe per identity group: /proc/<pid>/environ is only readable by the owning process and root, so probing with each task's own identity is most reliable.
        val aliveById = mutableMapOf<String, Boolean>()
        tasks.groupBy { it.identity }.forEach { (identity, group) ->
            if (identity == "root" && !rootAvailable()) return@forEach
            val probeScript = aliveCheckFunction() + "\n" + group.joinToString("\n") { task ->
                "if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; " +
                    "then echo '${task.id} 1'; else echo '${task.id} 0'; fi"
            }
            val result = runOneShotShell(
                processSupervisor = oneShotSupervisor,
                identity = identity,
                command = probeScript,
                timeoutSeconds = 15,
            )
            if (result.exitCode == 0) {
                result.output.decodeToString().lineSequence().forEach { line ->
                    val parts = line.trim().split(" ")
                    if (parts.size == 2) aliveById[parts[0]] = parts[1] == "1"
                }
            } else {
                logger.warn(
                    "Agent terminal daemon action=list outcome=probe_failed identity=$identity exitCode=${result.exitCode}"
                )
            }
        }
        // A task whose probe failed is conservatively treated as still running to avoid false death reports; only confirmed-dead tasks may be pruned.
        val statuses = tasks.map { task ->
            var running = aliveById[task.id] ?: true
            if (task.identity == "user" && aliveById[task.id] == true && !adoptUserTask(task)) running = false
            DetachedTaskStatus(task, running)
        }
        if (aliveById.isNotEmpty()) {
            pruneExitedRecords(statuses)
        }
        return statuses
    }

    fun readLogs(id: String, maxBytes: Int = MAX_LOG_READ_BYTES): DaemonLogsResult {
        val task = synchronized(RECORDS_LOCK) { loadTasksLocked() }.firstOrNull { it.id == id }
            ?: return DaemonLogsResult(ok = false, code = "TASK_NOT_FOUND", message = "Daemon task not found: $id")
        if (task.identity == "root" && !rootAvailable()) return DaemonLogsResult(ok = false, code = "ROOT_REQUIRED", message = "Root access is unavailable")
        val limit = maxBytes.coerceIn(1_024, MAX_LOG_READ_BYTES)
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = "tail -c $limit ${shellQuote(hostDaemonPath(task, task.logPath))} 2>/dev/null",
            timeoutSeconds = 15,
        )
        if (result.exitCode != 0) {
            return DaemonLogsResult(ok = false, code = "LOGS_UNAVAILABLE", message = "Logs unavailable: exit=${result.exitCode}")
        }
        return DaemonLogsResult(
            ok = true,
            text = result.output.decodeToString(),
            truncated = result.output.size >= limit,
        )
    }

    fun findTask(id: String): DetachedTask? = synchronized(RECORDS_LOCK) { loadTasksLocked().firstOrNull { it.id == id } }

    /** Stops a task and deletes its record and logs; a no-op cleanup when the process already exited. */
    fun stop(id: String): Boolean {
        val tasks = synchronized(RECORDS_LOCK) { loadTasksLocked() }
        val task = tasks.firstOrNull { it.id == id } ?: return false
        if (task.identity == "root" && !rootAvailable()) return false
        if (!stopTaskProcess(task)) return false
        synchronized(RECORDS_LOCK) {
            saveTasksLocked(loadTasksLocked().filterNot { it.id == id })
        }
        logger.info(
            "Agent terminal daemon action=stop outcome=stopped taskId=$id " +
                "environment=${task.environment.wireName}"
        )
        if (task.identity == "user") releaseUserLease("daemon:$id")
        return true
    }

    private fun stopTaskProcess(task: DetachedTask): Boolean {
        val stopScript = buildString {
            appendLine(aliveCheckFunction())
            appendLine("if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; then")
            // Under the setsid path pgid==pid, so one group kill reaps the whole tree; in degraded environments without setsid only the main process is killed.
            appendLine("  if [ -d /proc ]; then kill -TERM -${task.pid} 2>/dev/null; else kill -TERM ${task.pid} 2>/dev/null; fi")
            appendLine("  sleep 1")
            appendLine("  if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; then")
            appendLine("    if [ -d /proc ]; then kill -9 -${task.pid} 2>/dev/null; else kill -9 ${task.pid} 2>/dev/null; fi")
            appendLine("  fi")
            appendLine("  sleep 0.1")
            appendLine("  eta_alive ${task.pid} ${shellQuote(ownerProof(task))} && exit 81")
            appendLine("fi")
            append("rm -f ${shellQuote(hostDaemonPath(task, task.logPath))} ${shellQuote(hostDaemonPath(task, task.logPath.removeSuffix(".log") + ".pid"))}")
        }
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = stopScript,
            timeoutSeconds = 10,
        )
        return result.exitCode == 0
    }

    /** Liveness checks verify the ownership token: a PID recycled by the system is never mistaken for this task. Environments without /proc degrade to an existence probe. */
    private fun aliveCheckFunction(): String =
        "eta_alive() { " +
            "if [ -d /proc ]; then " +
                "[ -d /proc/\$1 ] && " +
                "tr '\\000' '\\n' < /proc/\$1/environ 2>/dev/null | grep -Fqx \"\$2\"; " +
            "else kill -0 \"\$1\" 2>/dev/null; fi; " +
            "}"

    private fun ownerProof(task: DetachedTask): String = "$ETA_PROCESS_OWNER_ENV=${task.token}"

    /** Ordinary daemon tasks keep the full host shell and tracer; a guest must not detach from the PRoot lifecycle again. */
    private fun startUserDaemon(id: String, token: String, command: String, cwd: String, environment: TerminalEnvironment, originalCommand: String): DaemonStartResult {
        val workspace = if (environment.isLinux || daemonDir == DEFAULT_DAEMON_DIR) TerminalRuntime.userWorkspacePath else File(daemonDir).parent!!
        val hostDir = if (environment.isLinux || daemonDir == DEFAULT_DAEMON_DIR) File(workspace, "daemon") else File(daemonDir)
        if (!hostDir.mkdirs() && !hostDir.isDirectory) return DaemonStartResult.Failed("WORKSPACE_UNAVAILABLE", "Workspace is not accessible")
        val pidFile = File(hostDir, "$id.pid")
        val logFile = File(hostDir, "$id.log")
        val payload = if (environment.isLinux) {
            val rootfs = rootfsPath(environment)
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs)) return DaemonStartResult.Failed("LINUX_ENVIRONMENT_NOT_READY", "Linux environment is not installed yet")
            if (!ProotCommandBuilder.available()) return DaemonStartResult.Failed("PROOT_UNAVAILABLE", "No rootless Linux runtime component is available on this device")
            ProotCommandBuilder.payload(requireNotNull(rootfs), "cd ${shellQuote(cwd)} && exec sh -c ${shellQuote(command)}", linuxSharedMountsProvider(), workspace = workspace, skillsDirectory = skillsDirectoryProvider())
        } else "cd ${shellQuote(cwd)} && exec sh -c ${shellQuote(command)}"
        val lease = "daemon:$id"
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val launchLock = Any()
        // Stop records its intent first, then waits for the start critical section to finish; a stop issued before the record exists is never dropped.
        return synchronized(launchLock) {
            if (!acquireUserLease(lease) {
                    stopped.set(true)
                    synchronized(launchLock) { stop(id) }
            }) return@synchronized DaemonStartResult.Failed("BACKGROUND_START_NOT_ALLOWED", "Return to su and restart the background task")
            if (stopped.get()) {
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("TASK_CANCELLED", "Background task was cancelled")
            }
            val script = "printf '%s\\n' \"${'$'}${'$'}\" > ${shellQuote(pidFile.absolutePath)}; $payload"
            val launcher = "if command -v setsid >/dev/null 2>&1; then exec setsid -w sh -c ${shellQuote(script)}; else exec sh -c ${shellQuote(script)}; fi"
            val process = try {
                ProcessBuilder("sh", "-c", launcher).apply {
                    environment()[ETA_PROCESS_OWNER_ENV] = token
                    if (environment == TerminalEnvironment.ANDROID) environment()["HOME"] = workspace
                    redirectInput(File("/dev/null"))
                    redirectOutput(logFile)
                    redirectErrorStream(true)
                }.start()
            } catch (_: java.io.IOException) {
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("PROCESS_START_FAILED", "Could not start the background task")
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (pidFile.length() == 0L && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            val pid = if (pidFile.isFile) pidFile.readText().trim().toLongOrNull() else null
            if (pid == null || pid <= 1) {
                process.destroyForcibly()
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("PROCESS_START_FAILED", "Background task did not finish starting")
            }
            val task = DetachedTask(id, pid, token, originalCommand, cwd, "user", environment,
                if (environment.isLinux) "$LINUX_DAEMON_DIR/$id.log" else logFile.absolutePath,
                System.currentTimeMillis(), if (environment.isLinux) LinuxExecutionBackend.PROOT else LinuxExecutionBackend.CHROOT, workspace)
            USER_WAITERS.add(id)
            if (!synchronized(RECORDS_LOCK) { saveTasksLocked(loadTasksLocked() + task) }) {
                USER_WAITERS.remove(id)
                stopTaskProcess(task)
                process.destroyForcibly()
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("RECORDS_WRITE_FAILED", "Could not save the background task; check internal storage")
            }
            thread(name = "eta-daemon-wait", isDaemon = true) {
                try { process.waitFor() } finally {
                    USER_WAITERS.remove(id)
                    releaseUserLease(lease)
                }
            }
            if (stopped.get()) {
                stop(id)
                DaemonStartResult.Failed("TASK_CANCELLED", "Background task was cancelled")
            } else DaemonStartResult.Started(task)
        }
    }

    /** After the app process is recreated, only adopt confirmed-owned ordinary tasks so background tracers never lose their notification and stop entry points. */
    private fun adoptUserTask(task: DetachedTask): Boolean {
        if (!USER_WAITERS.add(task.id)) return true
        val lease = "daemon:${task.id}"
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        if (!acquireUserLease(lease) { stopped.set(true); stop(task.id) }) {
            USER_WAITERS.remove(task.id)
            return !stop(task.id)
        }
        thread(name = "eta-daemon-adopt", isDaemon = true) {
            try {
                while (!stopped.get()) {
                    val result = runOneShotShell(
                        processSupervisor = oneShotSupervisor, identity = "user",
                        command = aliveCheckFunction() + "\nif eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; then echo alive; else echo exited; fi",
                        timeoutSeconds = 5,
                    )
                    if (result.exitCode == 0 && result.output.decodeToString().trim() == "exited") break
                    Thread.sleep(2_000)
                }
            } finally {
                USER_WAITERS.remove(task.id)
                releaseUserLease(lease)
            }
        }
        return !stopped.get()
    }

    /**
     * Bare launcher: bypasses the [ShellProcessSupervisor] managed shell (whose wait and exit cleanup are exactly the
     * reclamation semantics daemon tasks must escape) and only handles startup, output collection, and timeout defense.
     * Linux environments still reuse the same unshare + chroot wrapper.
     */
    private fun launchRaw(
        identity: String,
        environment: TerminalEnvironment,
        script: String,
        timeoutSeconds: Long,
    ): OneShotShellResult {
        val payload = when (environment) {
            TerminalEnvironment.ANDROID -> oneShotSupervisor.buildAndroidPayload(identity, script)
            else -> {
                val rootfs = rootfsPath(environment)
                    ?: return OneShotShellResult(-1, ByteArray(0), "Linux rootfs is not configured".toByteArray())
                oneShotSupervisor.buildLinuxPayload(rootfs, script, linuxSharedMountsProvider())
            }
        }
        val process = runCatching {
            val builder = if (identity == "root") {
                RootSu.process(payload)
            } else {
                ProcessBuilder("sh", "-c", payload)
            }
            builder.redirectErrorStream(true).start()
        }.getOrElse {
            return OneShotShellResult(-1, ByteArray(0), (it.message ?: "Could not start the process").toByteArray())
        }
        val output = ByteArrayOutputCollector()
        val reader = thread(name = "agent-daemon-launch-reader", isDaemon = true) {
            process.inputStream.use { input -> output.readFrom(input) }
        }
        val finished = runCatching { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { reader.join(500) }
            return OneShotShellResult(-2, output.bytes(), "Command timed out".toByteArray())
        }
        runCatching { reader.join(500) }
        return OneShotShellResult(process.exitValue(), output.bytes(), ByteArray(0))
    }

    private fun wireDaemonDir(environment: TerminalEnvironment): String =
        if (environment.isLinux) LINUX_DAEMON_DIR else daemonDir

    /**
     * A Linux task's /workspace is a bind view of the host work directory; log and pid files still physically live on the host.
     * Always read and write via the host path instead of entering chroot just to read logs.
     */
    internal fun hostDaemonPath(task: DetachedTask, wirePath: String): String =
        if (task.environment.isLinux && wirePath.startsWith(LINUX_DAEMON_DIR)) {
            (task.hostWorkspace?.let { "$it/daemon" } ?: DEFAULT_DAEMON_DIR) + wirePath.removePrefix(LINUX_DAEMON_DIR)
        } else {
            wirePath
        }

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private fun pruneExitedRecords(statuses: List<DetachedTaskStatus>) {
        val excess = statuses.size - MAX_RETAINED_RECORDS
        if (excess <= 0) return
        val victims = statuses
            .filter { !it.running }
            .sortedBy { it.task.startedAt }
            .take(excess)
        if (victims.isEmpty()) return
        victims.groupBy { it.task.identity }.forEach { (identity, group) ->
            val removeScript = group.joinToString("\n") {
                "rm -f ${shellQuote(hostDaemonPath(it.task, it.task.logPath))} " +
                    shellQuote(hostDaemonPath(it.task, it.task.logPath.removeSuffix(".log") + ".pid"))
            }
            runOneShotShell(
                processSupervisor = oneShotSupervisor,
                identity = identity,
                command = removeScript,
                timeoutSeconds = 10,
            )
        }
        val victimIds = victims.mapTo(mutableSetOf()) { it.task.id }
        synchronized(RECORDS_LOCK) {
            saveTasksLocked(loadTasksLocked().filterNot { it.id in victimIds })
        }
    }

    private fun loadTasksLocked(): MutableList<DetachedTask> {
        if (!recordsFile.exists()) return mutableListOf()
        return runCatching {
            val array = JSONArray(recordsFile.readText())
            (0 until array.length()).mapTo(mutableListOf()) { index ->
                array.getJSONObject(index).toTask()
            }
        }.getOrElse {
            logger.warn("Agent terminal daemon records corrupted, resetting")
            mutableListOf()
        }
    }

    private fun saveTasksLocked(tasks: List<DetachedTask>): Boolean =
        runCatching {
            recordsFile.parentFile?.mkdirs()
            val array = JSONArray()
            tasks.forEach { array.put(it.toJson()) }
            val tmp = File(recordsFile.parentFile, recordsFile.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(recordsFile)) {
                recordsFile.writeText(array.toString())
            }
            true
        }.getOrElse {
            logger.warn("Agent terminal daemon records save failed")
            false
        }

    private fun DetachedTask.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("pid", pid)
        .put("token", token)
        .put("command", command)
        .put("cwd", cwd)
        .put("identity", identity)
        .put("environment", environment.wireName)
        .put("log_path", logPath)
        .put("started_at", startedAt)
        .put("backend", backend.wireName)
        .put("host_workspace", hostWorkspace ?: JSONObject.NULL)

    private fun JSONObject.toTask(): DetachedTask = DetachedTask(
        id = getString("id"),
        pid = getLong("pid"),
        token = getString("token"),
        command = getString("command"),
        cwd = getString("cwd"),
        identity = getString("identity"),
        environment = when (optString("environment")) {
            TerminalEnvironment.ALPINE.wireName -> TerminalEnvironment.ALPINE
            TerminalEnvironment.DEBIAN.wireName -> TerminalEnvironment.DEBIAN
            else -> TerminalEnvironment.ANDROID
        },
        logPath = getString("log_path"),
        startedAt = getLong("started_at"),
        backend = LinuxExecutionBackend.entries.firstOrNull { it.wireName == optString("backend") } ?: LinuxExecutionBackend.CHROOT,
        hostWorkspace = optString("host_workspace").takeIf { it.isNotBlank() && it != "null" },
    )
}
