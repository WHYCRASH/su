package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Map /workspace, /var/minis, and minis:// paths inside the Linux environment back to Android host paths.
 * Under chroot, /workspace and /var/minis/workspace bind-mount the same host workspace.
 */
internal object LinuxGuestPathResolver {
    const val MINIS_ROOT = "/var/minis"
    const val MINIS_SCHEME = "minis://"

    fun workspaceHost(
        filesDir: File,
        linuxReady: Boolean,
        backend: LinuxExecutionBackend,
    ): File = if (linuxReady && backend == LinuxExecutionBackend.CHROOT) {
        File(TerminalRuntime.workspace("root"))
    } else {
        TerminalPrivateStorage.workspace(filesDir)
    }

    fun workspaceHostForApp(context: Context): File {
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        val backend = LinuxEnvironmentSettingsRepository.backend(context, distribution)
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution, backend)
        return workspaceHost(
            filesDir = context.filesDir,
            linuxReady = LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath),
            backend = backend,
        )
    }

    fun resolveForApp(context: Context, path: String): String {
        return resolveAndroidPath(
            path = path,
            workspaceHost = workspaceHostForApp(context).path,
            skillsHost = TerminalRuntime.visibleSkillsDirectory()?.path
                ?: File(context.filesDir, "skills").path,
            offloadsHost = File(context.filesDir, "minis/offloads").path,
            browserHost = File(context.filesDir, "minis/browser").path,
            sharedMounts = SharedFolderMounts.current(),
        )
    }

    fun resolveAndroidPath(
        path: String,
        workspaceHost: String,
        sharedMounts: List<SharedFolderMount> = emptyList(),
        skillsHost: String? = null,
        offloadsHost: String? = null,
        browserHost: String? = null,
    ): String {
        val guest = guestPath(path) ?: return path
        minisHostPath(guest, workspaceHost, skillsHost, offloadsHost, browserHost)?.let { return it }
        if (guest != "/workspace" && !guest.startsWith("/workspace/")) return path
        val relative = guest.removePrefix("/workspace").trim('/')
        sharedMountAndroidPath(relative, sharedMounts)?.let { return it }
        return joinHost(workspaceHost, relative)
    }

    fun guestPath(path: String): String? {
        val trimmed = path.trim().removePrefix("file://")
        if (trimmed.startsWith(MINIS_SCHEME)) {
            val rest = trimmed.removePrefix(MINIS_SCHEME).trim('/')
            val decoded = runCatching {
                URLDecoder.decode(rest, StandardCharsets.UTF_8.name())
            }.getOrDefault(rest)
            return LinuxFileExplorer.normalizeLinuxPath("$MINIS_ROOT/$decoded")
        }
        return LinuxFileExplorer.normalizeLinuxPath(trimmed)
    }

    private fun minisHostPath(
        guest: String,
        workspaceHost: String,
        skillsHost: String?,
        offloadsHost: String?,
        browserHost: String?,
    ): String? {
        if (guest != MINIS_ROOT && !guest.startsWith("$MINIS_ROOT/")) return null
        val relative = guest.removePrefix(MINIS_ROOT).trim('/')
        if (relative.isEmpty()) return workspaceHost
        val namespace = relative.substringBefore('/')
        val child = relative.substringAfter('/', missingDelimiterValue = "")
        return when (namespace) {
            "workspace" -> joinHost(workspaceHost, child)
            "offloads" -> joinHost(offloadsHost ?: "$workspaceHost/offloads", child)
            "browser" -> joinHost(browserHost ?: "$workspaceHost/browser", child)
            "skills" -> skillsHost?.let { joinHost(it, child) } ?: joinHost(workspaceHost, "skills/$child".trimEnd('/'))
            "attachments", "shared", "memory", "mounts" -> joinHost(workspaceHost, relative)
            else -> joinHost(workspaceHost, relative)
        }
    }

    private fun sharedMountAndroidPath(
        workspaceRelative: String,
        sharedMounts: List<SharedFolderMount>,
    ): String? {
        if (workspaceRelative != "mounts" && !workspaceRelative.startsWith("mounts/")) return null
        val rest = workspaceRelative.removePrefix("mounts").trim('/')
        if (rest.isEmpty()) return null
        val name = rest.substringBefore('/')
        val child = rest.substringAfter('/', missingDelimiterValue = "")
        val mount = sharedMounts.firstOrNull { it.name == name } ?: return null
        return joinHost(mount.sourcePath, child)
    }

    private fun joinHost(root: String, relative: String): String {
        val base = root.replace('\\', '/').trimEnd('/')
        if (relative.isEmpty()) return base.ifEmpty { "/" }
        return "$base/${relative.trim('/')}"
    }
}
