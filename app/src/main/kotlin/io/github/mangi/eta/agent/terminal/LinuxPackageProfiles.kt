package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class PackageProfileInstallStage {
    CHECKING,
    DOWNLOADING,
    INSTALLING,
    COMPLETE,
}

internal data class PackageProfileInstallProgress(
    val stage: PackageProfileInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface PackageProfileInstallResult {
    data object AlreadyReady : PackageProfileInstallResult
    data object EnvironmentNotReady : PackageProfileInstallResult

    /** The dependency profile is not installed yet; install it first following the dependency chain. */
    data object Installed : PackageProfileInstallResult
    data class Failed(val stage: PackageProfileInstallStage) : PackageProfileInstallResult
}

internal data class LinuxPackageSpec(
    val packages: List<String> = emptyList(),
    val managedTool: ManagedLinuxTool? = null,
    val setupScript: String? = null,
)

internal data class LinuxPackageProfile(
    val id: String,
    val markerName: String,
    val revision: Int,
    val specs: Map<LinuxDistribution, LinuxPackageSpec>,
    /** Prerequisite profile that must be ready before installation. */
) {
    fun spec(distribution: LinuxDistribution): LinuxPackageSpec = requireNotNull(specs[distribution])
}

internal object LinuxPackageProfiles {
    val PYTHON = LinuxPackageProfile(
        id = "python",
        markerName = AlpineEnvironmentPaths.PYTHON_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.PYTHON_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = """
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python UV_PYTHON_BIN_DIR=/usr/local/bin UV_PYTHON_INSTALL_BIN=1 uv python install --default --force
                """.trimIndent(),
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = """
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python UV_PYTHON_BIN_DIR=/usr/local/bin UV_PYTHON_INSTALL_BIN=1 uv python install --default --force
                """.trimIndent(),
            ),
        ),
    )
    val NODE = LinuxPackageProfile(
        id = "node",
        markerName = AlpineEnvironmentPaths.NODE_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.NODE_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                packages = listOf("nodejs-current", "npm"),
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                // The official Node arm64 binaries link against libatomic.so.1, which the archived install does not include, so install it as well.
                packages = listOf("libatomic1"),
                managedTool = ManagedLinuxTool.NODE,
            ),
        ),
    )
    val SSH = LinuxPackageProfile(
        id = "ssh",
        markerName = AlpineEnvironmentPaths.SSH_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.SSH_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                packages = listOf("openssh"),
                setupScript = "ssh-keygen -A >/dev/null 2>&1 || true",
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                packages = listOf("openssh-client", "openssh-server"),
                setupScript = "ssh-keygen -A >/dev/null 2>&1 || true",
            ),
        ),
    )
    val ALL = listOf(PYTHON, NODE, SSH)
}

internal fun linuxPackageProfileReady(rootfs: File, profile: LinuxPackageProfile): Boolean =
    LinuxEnvironmentPaths.markerSatisfied(
        File(rootfs, profile.markerName),
        "profile=${profile.revision}",
    )

/** Install a single tool profile on demand for the selected distribution; only writes the matching completion marker on success. */
internal class LinuxPackageProfileInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    private val profile: LinuxPackageProfile,
) {
    private val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
    private val managedToolInstaller = PinnedLinuxToolInstaller(context)

    fun isReady(): Boolean = linuxPackageProfileReady(rootfs, profile)

    suspend fun install(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit = {},
    ): PackageProfileInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit,
    ): PackageProfileInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext PackageProfileInstallResult.AlreadyReady
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.CHECKING))
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) ||
            !File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).isFile
        ) {
            return@withContext PackageProfileInstallResult.EnvironmentNotReady
        }
        val spec = profile.spec(distribution)
        spec.managedTool?.let { tool ->
            val installed = managedToolInstaller.install(
                tool = tool,
                distribution = distribution,
                rootfs = rootfs,
            ) { downloadedBytes, totalBytes ->
                onProgress(
                    PackageProfileInstallProgress(
                        stage = PackageProfileInstallStage.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            if (!installed) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.DOWNLOADING,
                )
            }
        }

        val packageHelper = when (distribution) {
            LinuxDistribution.ALPINE -> "/usr/local/bin/eta-apk"
            LinuxDistribution.DEBIAN -> "/usr/local/bin/eta-apt"
        }
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.INSTALLING))
        if (spec.packages.isNotEmpty()) {
            val installResult = InstallerShellRunner.run(
                command = "$packageHelper install ${spec.packages.joinToString(" ")}",
                timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
                environment = distribution.terminalEnvironment,
                linuxRootfsPath = rootfs.absolutePath,
            )
            if (installResult.exitCode != 0) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.INSTALLING,
                )
            }
        }

        val activateCommand = buildString {
            append("set -e\n")
            spec.setupScript?.let { script -> append(script).append('\n') }
            append("cat > /").append(profile.markerName).append(" <<'ETA_PROFILE_EOF'\n")
            append("profile=").append(profile.revision).append('\n')
            append("ETA_PROFILE_EOF\n")
            append("chmod 0644 /").append(profile.markerName).append(" || exit 71")
        }
        val result = InstallerShellRunner.run(
            command = activateCommand,
            timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Package profile action=activate distribution=${distribution.wireName} profile=${profile.id} " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        if (result.exitCode != 0) {
            return@withContext PackageProfileInstallResult.Failed(PackageProfileInstallStage.INSTALLING)
        }

        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.COMPLETE))
        PackageProfileInstallResult.Installed
    }

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 600L
        private val installMutex = Mutex()
    }
}
