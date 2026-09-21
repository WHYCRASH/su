package io.github.mangi.eta.core

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

internal enum class HookInstallStatus {
    INSTALLED,
    MISSING,
    FAILED,
    SKIPPED
}

internal data class HookInstallEntry(
    val group: String,
    val id: String,
    val description: String,
    val status: HookInstallStatus,
    val detail: String? = null
)

internal data class HookInstallReport(
    val group: String,
    val entries: List<HookInstallEntry>
) {
    val installedCount: Int = entries.count { it.status == HookInstallStatus.INSTALLED }
    val missingCount: Int = entries.count { it.status == HookInstallStatus.MISSING }
    val failedCount: Int = entries.count { it.status == HookInstallStatus.FAILED }
    val skippedCount: Int = entries.count { it.status == HookInstallStatus.SKIPPED }

    fun summary(): String =
        "Hook installation finished: installed=$installedCount, missing=$missingCount, " +
            "failed=$failedCount, skipped=$skippedCount"

    companion object {
        fun combine(group: String, reports: Iterable<HookInstallReport>): HookInstallReport {
            val reportList = reports.toList()
            return HookInstallReport(
                group = group,
                entries = reportList.flatMap { it.entries }
            )
        }
    }
}

internal data class HookInstallation(
    val report: HookInstallReport,
    val handles: List<XposedInterface.HookHandle>
) {
    companion object {
        fun combine(group: String, installations: Iterable<HookInstallation>): HookInstallation {
            val installationList = installations.toList()
            return HookInstallation(
                report = HookInstallReport.combine(group, installationList.map { it.report }),
                handles = installationList.flatMap { it.handles }
            )
        }
    }
}

/** Pure-state ledger for the install report; holds no framework objects. */
internal class HookInstallJournal(private val group: String) {
    private val entries = mutableListOf<HookInstallEntry>()

    fun capture(block: () -> Unit, onFailure: (Exception) -> Unit) {
        try {
            block()
        } catch (exception: Exception) {
            failed(
                id = "install.failed",
                description = "$group Hook group installation",
                detail = exception.javaClass.simpleName
            )
            onFailure(exception)
        }
    }

    fun installed(id: String, description: String) {
        record(id, description, HookInstallStatus.INSTALLED)
    }

    fun missing(id: String, description: String, detail: String) {
        record(id, description, HookInstallStatus.MISSING, detail)
    }

    fun failed(id: String, description: String, detail: String) {
        record(id, description, HookInstallStatus.FAILED, detail)
    }

    fun skipped(id: String, description: String, detail: String) {
        record(id, description, HookInstallStatus.SKIPPED, detail)
    }

    fun report(): HookInstallReport = HookInstallReport(group, entries.toList())

    private fun record(
        id: String,
        description: String,
        status: HookInstallStatus,
        detail: String? = null
    ) {
        entries += HookInstallEntry(group, id, description, status, detail)
    }
}

/**
 * Hook registrar for a single feature area. Only handles API registration and install diagnostics here; target lookup stays with the feature area.
 */
internal class HookRegistrar(
    private val module: XposedModule,
    logger: ModuleLogger,
    private val group: String
) {
    val logger: ModuleLogger = logger.scoped(group)

    private val journal = HookInstallJournal(group)
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val registrationKeys = mutableSetOf<RegistrationKey>()

    /**
     * Completes installation within the feature-group boundary. Hooks registered before a failure stay in the report and handle set.
     */
    fun install(block: HookRegistrar.() -> Unit): HookInstallation {
        journal.capture(block = { block() }) { exception ->
            // XposedFrameworkError is an Error and is not swallowed by the feature-group isolation layer.
            logger.error("Hook group installation failed", exception)
        }
        return finish()
    }

    fun intercept(
        id: String,
        executable: Executable,
        description: String,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        hooker: (XposedInterface.Chain) -> Any?
    ): XposedInterface.HookHandle? {
        require(STABLE_ID.matches(id)) { "Invalid Hook id format: $id" }
        val fullId = "eta.$id"
        val registrationKey = RegistrationKey(executable, fullId)
        if (!registrationKeys.add(registrationKey)) {
            val detail = "Duplicate Hook registration: $description ($fullId)"
            journal.failed(id, description, detail)
            logger.error(detail)
            return null
        }
        return try {
            val handle = module.hook(executable)
                .setPriority(priority)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(fullId)
                .intercept { chain -> hooker(chain) }
            handles += handle
            journal.installed(id, description)
            logger.debug { "Installed Hook: $description" }
            handle
        } catch (exception: Exception) {
            // HookFailedError is an Error and never lands here; it must keep propagating to the framework.
            registrationKeys.remove(registrationKey)
            journal.failed(id, description, exception.javaClass.simpleName)
            logger.error("Failed to install Hook: $description", exception)
            null
        }
    }

    fun missing(id: String, description: String, detail: String) {
        require(STABLE_ID.matches(id)) { "Invalid Hook id format: $id" }
        journal.missing(id, description, detail)
        logger.warn(detail)
    }

    fun skipped(id: String, description: String, detail: String) {
        require(STABLE_ID.matches(id)) { "Invalid Hook id format: $id" }
        journal.skipped(id, description, detail)
        logger.debug { detail }
    }

    private fun finish(): HookInstallation = HookInstallation(
        report = journal.report(),
        handles = handles.toList()
    )

    private companion object {
        val STABLE_ID = Regex("[a-z0-9][a-z0-9._-]*")
    }

    private data class RegistrationKey(
        val executable: Executable,
        val fullId: String
    )
}
