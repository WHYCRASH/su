package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.skill.SkillRuntime
import java.io.File

internal enum class LinuxExecutionBackend(val wireName: String) {
    CHROOT("chroot"),
    PROOT("proot"),
}

/** Terminal preconditions for the app process; reading capabilities never triggers an su authorization. */
internal object TerminalRuntime {
    @Volatile private var appContext: Context? = null
    fun acquireUserTask(id: String, onStop: () -> Unit): Boolean =
        appContext?.let { AgentExecutionService.acquire(it, id, onStop = onStop) } ?: true

    fun releaseUserTask(id: String) {
        if (appContext != null) AgentExecutionService.release(id)
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        File(userWorkspacePath).mkdirs()
    }

    val rootAvailable: Boolean get() = RootAccess.isGranted
    val publicStorageGranted: Boolean get() = appContext != null && android.os.Environment.isExternalStorageManager()
    val nativeLibraryDir: File? get() = appContext?.applicationInfo?.nativeLibraryDir?.let(::File)
    val userWorkspacePath: String get() = appContext?.let {
        TerminalPrivateStorage.workspace(it.filesDir).absolutePath
    } ?: File(System.getProperty("java.io.tmpdir"), "eta-terminal-workspace").absolutePath
    val temporaryDirectory: File get() = appContext?.let { File(it.cacheDir, "terminal/proot") }
        ?: File(System.getProperty("java.io.tmpdir"), "eta-proot")

    fun defaultIdentity(environment: TerminalEnvironment, rootfsPath: String? = null): String = when {
        environment.isLinux -> if (LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT) "user" else "root"
        rootAvailable -> "root"
        else -> "user"
    }

    fun workspace(identity: String): String =
        if (identity == "root") "/data/local/tmp/eta" else userWorkspacePath

    fun skillsDirectory(): File? = appContext?.let { File(it.filesDir, "skills") }

    fun visibleSkillsDirectory(): File? {
        val context = appContext ?: return null
        return SkillRuntime.visibleSkillsDirectory(context).apply { mkdirs() }
    }

    fun minisOffloadsDirectory(): File? = appContext?.let { minisSubdirectory(it.filesDir, "offloads") }

    fun minisBrowserDirectory(): File? = appContext?.let { minisSubdirectory(it.filesDir, "browser") }

    fun ensureLinuxWorkspaceLayout(hostWorkspace: String) {
        File(hostWorkspace).mkdirs()
        File(hostWorkspace, "offloads").mkdirs()
        File(hostWorkspace, "browser").mkdirs()
        minisOffloadsDirectory()
        minisBrowserDirectory()
    }

    private fun minisSubdirectory(filesDir: File, name: String): File =
        File(filesDir, "minis/$name").apply { mkdirs() }

    fun nativeExecutable(name: String): File? = nativeLibraryDir?.let { File(it, name) }
        ?.takeIf { it.isFile && it.canExecute() }
}
