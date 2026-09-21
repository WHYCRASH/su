package io.github.mangi.eta.agent.terminal

import java.io.File

/**
 * User-facing file-browsing backend for the Linux environment.
 *
 * Listing, reading, exporting, and deleting all run inside the Linux session (chroot / PRoot), so /workspace,
 * /storage/emulated/0, and shared directories see the same bind contents as ls in the terminal.
 * Paths get only lexical normalization; escaping the root is rejected.
 */
internal object LinuxFileExplorer {
    const val DEFAULT_MAX_READ_BYTES = 256L * 1024L

    private const val EXIT_NOT_DIRECTORY = 41
    private const val EXIT_UNREADABLE = 42
    private const val EXIT_FAILED = 43

    data class Entry(
        val name: String,
        val isDir: Boolean,
        val sizeBytes: Long,
        val mtimeEpochSeconds: Long,
    )

    sealed interface ListResult {
        data class Success(val entries: List<Entry>) : ListResult
        data object NotInstalled : ListResult
        data object NotDirectory : ListResult
        data object Unreadable : ListResult
        data object CommandFailed : ListResult
    }

    sealed interface ReadResult {
        data class Text(val content: String, val truncated: Boolean) : ReadResult
        data object Binary : ReadResult
        data object NotInstalled : ReadResult
        data object NotFile : ReadResult
        data object Unreadable : ReadResult
        data object CommandFailed : ReadResult
    }

    sealed interface DeleteResult {
        data object Success : DeleteResult
        data object NotInstalled : DeleteResult
        data object NotFound : DeleteResult
        data object Protected : DeleteResult
        data object Failed : DeleteResult
    }

    sealed interface CopyResult {
        data object Success : CopyResult
        data object NotInstalled : CopyResult
        data object NotFile : CopyResult
        data object Unreadable : CopyResult
        data object Failed : CopyResult
    }

    /**
     * Normalize user input into an absolute path inside Linux. Only paths starting with `/` are accepted (blank input normalizes to `/`);
     * `..` pops a segment; popping above the root or a relative path returns null.
     */
    fun normalizeLinuxPath(linuxPath: String): String? {
        val trimmed = linuxPath.trim()
        if (trimmed.isEmpty()) return "/"
        if (!trimmed.startsWith("/") || '\n' in trimmed || '\r' in trimmed) return null
        val segments = mutableListOf<String>()
        trimmed.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    /**
     * Map a guest absolute path to a host path. Segments are already stripped of `..` and joined as relative segments,
     * so `File(parent, "/abs")` cannot drop parent on some JVMs.
     */
    fun resolveHostPath(rootfsDir: File, linuxPath: String): String? {
        val normalized = normalizeLinuxPath(linuxPath) ?: return null
        if (normalized == "/") return rootfsDir.path
        return File(rootfsDir, normalized.removePrefix("/")).path
    }

    /** Blocking call; coroutine switching is the caller's job. No shell runs while the rootfs is not ready. */
    fun list(
        supervisor: ShellProcessSupervisor,
        rootfsDir: File,
        linuxPath: String,
        environment: TerminalEnvironment,
        sharedMounts: List<SharedFolderMount> = emptyList(),
    ): ListResult {
        if (!environment.isLinux) return ListResult.CommandFailed
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)) return ListResult.NotInstalled
        val guestPath = normalizeLinuxPath(linuxPath) ?: return ListResult.NotDirectory
        val quoted = shellQuote(guestPath)
        val script = """
            if [ ! -d $quoted ]; then exit $EXIT_NOT_DIRECTORY; fi
            if [ ! -r $quoted ] || [ ! -x $quoted ]; then exit $EXIT_UNREADABLE; fi
            cd $quoted || exit $EXIT_UNREADABLE
            find . -mindepth 1 -maxdepth 1 2>/dev/null | while IFS= read -r rel; do
              name=${'$'}{rel#./}
              [ -n "${'$'}name" ] || continue
              if [ -d "${'$'}name" ]; then
                kind=directory
              else
                kind="regular file"
              fi
              size=${'$'}(stat -c '%s' "${'$'}name" 2>/dev/null || echo 0)
              mtime=${'$'}(stat -c '%Y' "${'$'}name" 2>/dev/null || echo 0)
              mtime=${'$'}{mtime%%.*}
              printf '%s|%s|%s|%s\n' "${'$'}kind" "${'$'}size" "${'$'}mtime" "${'$'}name"
            done
            exit 0
        """.trimIndent()
        val result = runExplorerShell(
            supervisor = supervisor,
            rootfsDir = rootfsDir,
            environment = environment,
            sharedMounts = sharedMounts,
            command = script,
        )
        return when (result.exitCode) {
            0 -> ListResult.Success(parseStatOutput(result.output.decodeToString()))
            EXIT_NOT_DIRECTORY -> ListResult.NotDirectory
            EXIT_UNREADABLE -> ListResult.Unreadable
            else -> ListResult.CommandFailed
        }
    }

    /** Blocking call; reads up to [maxBytes], plus one extra byte to detect truncation. */
    fun readText(
        supervisor: ShellProcessSupervisor,
        rootfsDir: File,
        linuxPath: String,
        environment: TerminalEnvironment,
        sharedMounts: List<SharedFolderMount> = emptyList(),
        maxBytes: Long = DEFAULT_MAX_READ_BYTES,
    ): ReadResult {
        if (!environment.isLinux) return ReadResult.CommandFailed
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)) return ReadResult.NotInstalled
        val guestPath = normalizeLinuxPath(linuxPath) ?: return ReadResult.NotFile
        val quoted = shellQuote(guestPath)
        val script = """
            if [ ! -f $quoted ]; then exit $EXIT_NOT_DIRECTORY; fi
            if [ ! -r $quoted ]; then exit $EXIT_UNREADABLE; fi
            head -c ${maxBytes + 1} $quoted
            exit 0
        """.trimIndent()
        val result = runExplorerShell(
            supervisor = supervisor,
            rootfsDir = rootfsDir,
            environment = environment,
            sharedMounts = sharedMounts,
            command = script,
        )
        if (result.exitCode != 0) {
            return when (result.exitCode) {
                EXIT_NOT_DIRECTORY -> ReadResult.NotFile
                EXIT_UNREADABLE -> ReadResult.Unreadable
                else -> ReadResult.CommandFailed
            }
        }
        val truncated = result.output.size.toLong() > maxBytes
        val payload = if (truncated) result.output.copyOf(maxBytes.toInt()) else result.output
        if (payload.contains(0.toByte())) return ReadResult.Binary
        return ReadResult.Text(content = payload.decodeToString(), truncated = truncated)
    }

    /**
     * Parse `type|size|mtime|name` output: split on the first 3 separators and treat the rest as the file name,
     * tolerating `|` inside file names and skipping malformed or leftover-glob lines.
     */
    internal fun parseStatOutput(output: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        output.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) return@forEach
            val name = displayName(parts[3])
            if (name.isEmpty() || name == "." || name == ".." || name == "*" || name == ".[!.]*") return@forEach
            val size = parts[1].toLongOrNull() ?: return@forEach
            val mtime = parts[2].substringBefore('.').toLongOrNull() ?: return@forEach
            entries += Entry(
                name = name,
                isDir = parts[0] == "directory" || parts[0] == "d",
                sizeBytes = size,
                mtimeEpochSeconds = mtime,
            )
        }
        return sortEntries(entries)
    }

    internal fun sortEntries(entries: List<Entry>): List<Entry> =
        entries.sortedWith(compareBy<Entry> { !it.isDir }.thenBy { it.name })

    private fun displayName(raw: String): String {
        val trimmed = raw.trim().removePrefix("./")
        if (trimmed.isEmpty()) return ""
        return if ('/' in trimmed) trimmed.substringAfterLast('/') else trimmed
    }

    fun delete(
        supervisor: ShellProcessSupervisor,
        rootfsDir: File,
        linuxPath: String,
        environment: TerminalEnvironment,
        sharedMounts: List<SharedFolderMount> = emptyList(),
    ): DeleteResult {
        if (!environment.isLinux) return DeleteResult.Failed
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)) return DeleteResult.NotInstalled
        val guestPath = normalizeLinuxPath(linuxPath) ?: return DeleteResult.Failed
        if (isProtectedPath(guestPath)) return DeleteResult.Protected
        val quoted = shellQuote(guestPath)
        val script = """
            if [ ! -e $quoted ]; then exit $EXIT_NOT_DIRECTORY; fi
            rm -rf -- $quoted || exit $EXIT_FAILED
            exit 0
        """.trimIndent()
        val result = runExplorerShell(
            supervisor = supervisor,
            rootfsDir = rootfsDir,
            environment = environment,
            sharedMounts = sharedMounts,
            command = script,
            timeoutSeconds = 30,
        )
        return when (result.exitCode) {
            0 -> DeleteResult.Success
            EXIT_NOT_DIRECTORY -> DeleteResult.NotFound
            else -> DeleteResult.Failed
        }
    }

    fun copyToHostFile(
        supervisor: ShellProcessSupervisor,
        rootfsDir: File,
        linuxPath: String,
        environment: TerminalEnvironment,
        destination: File,
        sharedMounts: List<SharedFolderMount> = emptyList(),
    ): CopyResult {
        if (!environment.isLinux) return CopyResult.Failed
        if (!LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)) return CopyResult.NotInstalled
        val guestPath = normalizeLinuxPath(linuxPath) ?: return CopyResult.NotFile
        val direct = hostFileForGuest(rootfsDir, guestPath)
        if (direct != null && direct.isFile && direct.canRead()) {
            return runCatching {
                destination.parentFile?.mkdirs()
                direct.copyTo(destination, overwrite = true)
                if (destination.isFile) CopyResult.Success else CopyResult.Failed
            }.getOrDefault(CopyResult.Failed)
        }
        val stagingName = ".eta-export-" + System.nanoTime()
        val stagingGuest = "/workspace/$stagingName"
        val quotedGuest = shellQuote(guestPath)
        val quotedStaging = shellQuote(stagingGuest)
        val script = """
            if [ ! -f $quotedGuest ]; then exit $EXIT_NOT_DIRECTORY; fi
            if [ ! -r $quotedGuest ]; then exit $EXIT_UNREADABLE; fi
            mkdir -p /workspace || exit $EXIT_FAILED
            cp -- $quotedGuest $quotedStaging || exit $EXIT_FAILED
            chmod 644 $quotedStaging 2>/dev/null || true
            exit 0
        """.trimIndent()
        val result = runExplorerShell(
            supervisor = supervisor,
            rootfsDir = rootfsDir,
            environment = environment,
            sharedMounts = sharedMounts,
            command = script,
            timeoutSeconds = 60,
        )
        if (result.exitCode != 0) {
            return when (result.exitCode) {
                EXIT_NOT_DIRECTORY -> CopyResult.NotFile
                EXIT_UNREADABLE -> CopyResult.Unreadable
                else -> CopyResult.Failed
            }
        }
        val staged = File(workspaceHostDir(rootfsDir), stagingName)
        return try {
            destination.parentFile?.mkdirs()
            when {
                staged.isFile && staged.canRead() -> {
                    staged.copyTo(destination, overwrite = true)
                }
                staged.isFile -> copyHostFileAsRoot(staged, destination)
                else -> return CopyResult.Failed
            }
            if (destination.isFile) CopyResult.Success else CopyResult.Failed
        } catch (_: Exception) {
            CopyResult.Failed
        } finally {
            runCatching { staged.delete() }
            runExplorerShell(
                supervisor = supervisor,
                rootfsDir = rootfsDir,
                environment = environment,
                sharedMounts = sharedMounts,
                command = "rm -f -- " + quotedStaging,
                timeoutSeconds = 15,
            )
        }
    }

    internal fun isProtectedPath(linuxPath: String): Boolean {
        val path = normalizeLinuxPath(linuxPath) ?: return true
        if (path == "/") return true
        return PROTECTED_ROOTS.any { path == it || path.startsWith("$it/") }
    }

    private fun hostFileForGuest(rootfsDir: File, guestPath: String): File? {
        if (guestPath != "/workspace" && !guestPath.startsWith("/workspace/")) return null
        val relative = guestPath.removePrefix("/workspace").trim('/')
        val hostRoot = workspaceHostDir(rootfsDir)
        return if (relative.isEmpty()) hostRoot else File(hostRoot, relative)
    }

    private fun workspaceHostDir(rootfsDir: File): File =
        if (LinuxEnvironmentPaths.backendOf(rootfsDir.absolutePath) == LinuxExecutionBackend.PROOT) {
            File(TerminalRuntime.userWorkspacePath)
        } else {
            File(TerminalRuntime.workspace("root"))
        }

    private fun copyHostFileAsRoot(source: File, destination: File) {
        val supervisor = ShellProcessSupervisor()
        try {
            val parent = destination.parentFile?.absolutePath ?: throw java.io.IOException("EXPORT_UNAVAILABLE")
            val result = runOneShotShell(
                processSupervisor = supervisor,
                identity = "root",
                command = "mkdir -p " + shellQuote(parent) +
                    " && cp -- " + shellQuote(source.absolutePath) + " " + shellQuote(destination.absolutePath) +
                    " && chmod 644 " + shellQuote(destination.absolutePath),
                timeoutSeconds = 60,
                environment = TerminalEnvironment.ANDROID,
            )
            if (result.exitCode != 0) throw java.io.IOException("EXPORT_UNAVAILABLE")
        } finally {
            supervisor.beginClosing()
        }
    }

    private fun runExplorerShell(
        supervisor: ShellProcessSupervisor,
        rootfsDir: File,
        environment: TerminalEnvironment,
        sharedMounts: List<SharedFolderMount>,
        command: String,
        timeoutSeconds: Long = 15,
    ): OneShotShellResult {
        val rootless = LinuxEnvironmentPaths.backendOf(rootfsDir.absolutePath) == LinuxExecutionBackend.PROOT
        return runOneShotShell(
            processSupervisor = supervisor,
            identity = if (rootless) "user" else "root",
            command = command,
            timeoutSeconds = timeoutSeconds,
            environment = environment,
            linuxRootfsPath = rootfsDir.absolutePath,
            linuxSharedMounts = sharedMounts,
        )
    }

    private val PROTECTED_ROOTS = listOf("/proc", "/sys", "/dev")
}

