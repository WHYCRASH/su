package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File

/** Disk layout and readiness checks shared by the two Linux rootfs images. */
internal object LinuxEnvironmentPaths {
    const val READY_MARKER = ".eta-environment-ready"

    fun environmentDir(context: Context, distribution: LinuxDistribution): File =
        environmentDir(context, distribution, LinuxEnvironmentSettingsRepository.backend(context, distribution))

    fun environmentDir(context: Context, distribution: LinuxDistribution, backend: LinuxExecutionBackend): File =
        if (backend == LinuxExecutionBackend.CHROOT) {
            File(context.filesDir, "terminal/${distribution.wireName}")
        } else {
            TerminalPrivateStorage.prootEnvironment(context.filesDir, distribution)
        }

    fun rootfsDir(context: Context, distribution: LinuxDistribution): File =
        File(environmentDir(context, distribution), "rootfs")

    fun rootfsDir(context: Context, distribution: LinuxDistribution, backend: LinuxExecutionBackend): File =
        File(environmentDir(context, distribution, backend), "rootfs")

    fun legacyRootfsDir(context: Context, distribution: LinuxDistribution): File =
        rootfsDir(context, distribution, LinuxExecutionBackend.CHROOT)

    fun backendOf(rootfsPath: String?): LinuxExecutionBackend =
        if (TerminalPrivateStorage.isProotPath(rootfsPath)) LinuxExecutionBackend.PROOT else LinuxExecutionBackend.CHROOT

    fun rootfsReady(rootfsPath: String?): Boolean {
        if (rootfsPath.isNullOrBlank()) return false
        return File(rootfsPath, READY_MARKER).isFile
    }

    /** The presence of the marker means it's considered installed; verify the revision when the contents can be read. After Root finishes unpacking, App may not be able to read the body for a while. */
    fun markerSatisfied(marker: File, expectedLine: String): Boolean {
        if (!marker.isFile) return false
        val lines = runCatching { marker.readLines() }.getOrNull() ?: return true
        return lines.any { it.trim() == expectedLine }
    }
}
