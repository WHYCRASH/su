package io.github.mangi.eta

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.hook.google.GoogleAppHooks
import io.github.mangi.eta.hook.google.GoogleEligibilityHooks
import io.github.mangi.eta.hook.system.SystemServerHooks
import io.github.mangi.eta.hook.system.SystemUiHooks

class ModuleMain : XposedModule() {

    private val logger = ModuleLogger(this)
    private var currentProcessName: String? = null
    // Hot reload is not enabled yet; the handles are retained for a future explicit
    // unhook/replace rather than to keep these hooks active.
    private val hookHandles = mutableListOf<HookHandle>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        currentProcessName = param.processName
        if (!shouldKeepLifecycleCallbacks(param)) {
            detach()
            return
        }
        // Cache the read-only remote preferences the framework hands out so every hook callback
        // can read them immediately. getRemotePreferences belongs to XposedInterface, and
        // XposedModule extends its wrapper, so it can be called directly. When the call fails we
        // keep the historical default behavior, but we must leave a diagnosable log entry instead
        // of pretending configuration sync is healthy.
        val remotePreferences = try {
            getRemotePreferences(Prefs.GROUP)
        } catch (exception: Exception) {
            logger.warn("RemotePreferences unavailable, falling back to compatible defaults: ${exception.safeLogType()}")
            null
        }
        Prefs.attachRemote(remotePreferences)
        logger.debug {
            "module loaded process=${param.processName}, framework=$frameworkName($frameworkVersionCode), api=$apiVersion"
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        recordInstallation(SystemServerHooks.install(this, logger, param.classLoader))
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            ModuleConfig.SYSTEM_UI_PACKAGE -> {
                if (currentProcessName == ModuleConfig.SYSTEM_UI_PACKAGE) {
                    recordInstallation(SystemUiHooks.install(this, logger, param.classLoader))
                }
            }

            ModuleConfig.GOOGLE_PACKAGE -> {
                if (isCurrentPackageProcess(ModuleConfig.GOOGLE_PACKAGE)) {
                    recordInstallation(
                        HookInstallation.combine(
                            group = "Google",
                            installations = listOf(
                                GoogleEligibilityHooks.install(this, logger, param.classLoader),
                                GoogleAppHooks.install(this, logger, param.classLoader)
                            )
                        )
                    )
                }
            }
        }
    }

    private fun recordInstallation(installation: HookInstallation) {
        hookHandles += installation.handles
        logger.scoped(installation.report.group).info(installation.report.summary())
    }

    private fun isCurrentPackageProcess(packageName: String): Boolean {
        val processName = currentProcessName ?: return false
        return isPackageProcess(processName, packageName)
    }

    private fun shouldKeepLifecycleCallbacks(param: ModuleLoadedParam): Boolean {
        if (param.isSystemServer) return true
        val processName = param.processName
        return processName == ModuleConfig.SYSTEM_UI_PACKAGE ||
            isPackageProcess(processName, ModuleConfig.GOOGLE_PACKAGE)
    }

    private fun isPackageProcess(processName: String, packageName: String): Boolean =
        processName == packageName || processName.startsWith("$packageName:")
}
