package io.github.mangi.eta.hook.system

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger

import io.github.mangi.eta.config.Prefs
import android.content.Context
import android.os.Binder
import android.os.IBinder
import io.github.libxposed.api.XposedModule

internal object ContextualSearchHooks {

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "ContextualSearch")
        return hooks.install {
            // The service may be disabled by the ROM's resource configuration; try native startup first, then retain the startup tail to fill in the rest.
            hookContextualSearchConfig(hooks, classLoader)
            hookContextualSearchBootstrap(module, hooks, classLoader)
            hookContextualSearchPackage(hooks, classLoader)
            hookContextualSearchPermission(hooks, classLoader)
        }
    }

    private fun hookContextualSearchConfig(hooks: HookRegistrar, classLoader: ClassLoader) {
        val server = HookSupport.findClassOrNull(classLoader, ModuleConfig.SYSTEM_SERVER_CLASS)
        val resources = HookSupport.findClassOrNull(classLoader, "com.android.internal.R\$string")
        val resourceId = resources?.let {
            HookSupport.findField(it, "config_defaultContextualSearchPackageName")?.getInt(null)
        }
        val method = server?.let {
            HookSupport.findMethod(it, "deviceHasConfigString", Context::class.java, Int::class.javaPrimitiveType!!)
        }
        if (resourceId == null || resourceId == 0 || method == null || method.returnType != Boolean::class.javaPrimitiveType) {
            hooks.skipped("system.contextual-search-config", "SystemServer.deviceHasConfigString", "No Contextual Search resource gate provided, keeping the tail-end bootstrap")
            return
        }
        hooks.intercept("system.contextual-search-config", method, "SystemServer.deviceHasConfigString") { chain ->
            if (chain.getArg(1) == resourceId) true else chain.proceed()
        }
    }

    private fun hookContextualSearchBootstrap(
        module: XposedModule,
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        // Restart logs from this device on 2026-03-26 already proved the tail-end bootstrap at the end of startOtherServices is what reliably takes effect.
        // Hold the tail of system-service startup directly instead of treating deviceHasConfigString as the only path that works.
        val systemServerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.SYSTEM_SERVER_CLASS)
        val timingsClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.TIMINGS_TRACE_AND_SLOG_CLASS)
        val startOtherServicesMethod = if (systemServerClass != null && timingsClass != null) {
            HookSupport.findMethod(systemServerClass, "startOtherServices", timingsClass)
        } else {
            null
        }
        if (startOtherServicesMethod == null) {
            hooks.missing(
                id = "system.contextual-search-bootstrap",
                description = "SystemServer.startOtherServices",
                detail = "SystemServer.startOtherServices(TimingsTraceAndSlog) not found"
            )
            return
        }

        HookSupport.deoptimize(
            module,
            logger,
            startOtherServicesMethod,
            "SystemServer.startOtherServices(TimingsTraceAndSlog)"
        )
        hooks.intercept(
            id = "system.contextual-search-bootstrap",
            executable = startOtherServicesMethod,
            description = "SystemServer.startOtherServices"
        ) { chain ->
            val result = chain.proceed()
            ensureContextualSearchService(module, logger, classLoader, chain.getThisObject(), "startOtherServices")
            result
        }
    }

    private fun hookContextualSearchPackage(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        val method = serviceClass?.let { HookSupport.findMethod(it, "getContextualSearchPackageName") }
        if (method == null) {
            hooks.missing(
                id = "system.contextual-search-package",
                description = "ContextualSearchManagerService.getContextualSearchPackageName",
                detail = "ContextualSearchManagerService.getContextualSearchPackageName() not found"
            )
            return
        }

        hooks.intercept(
            id = "system.contextual-search-package",
            executable = method,
            description = "ContextualSearchManagerService.getContextualSearchPackageName"
        ) { ModuleConfig.GOOGLE_PACKAGE }
    }

    private fun hookContextualSearchPermission(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        val method = serviceClass?.let { HookSupport.findMethod(it, "enforcePermission", String::class.java) }
        if (method == null) {
            hooks.missing(
                id = "system.contextual-search-permission",
                description = "ContextualSearchManagerService.enforcePermission",
                detail = "ContextualSearchManagerService.enforcePermission(String) not found"
            )
            return
        }

        hooks.intercept(
            id = "system.contextual-search-permission",
            executable = method,
            description = "ContextualSearchManagerService.enforcePermission"
        ) { chain ->
            val functionName = chain.getArg(0) as? String
            if (functionName == "startContextualSearch" && isAllowedContextualSearchUid(chain.getThisObject())) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun isAllowedContextualSearchUid(serviceInstance: Any): Boolean {
        val context = HookSupport.invokeNoArgs(serviceInstance, "getContext") as? Context
            ?: HookSupport.getFieldValue(serviceInstance, "mContext") as? Context
            ?: return false
        // IContextualSearchManager is oneway AIDL, so the calling PID is fixed and unusable; the Android permission principal itself is the UID.
        // Auth by UID is the only option here, and the getPackagesForUid result represents the whole shared-UID security boundary.
        val callingUid = Binder.getCallingUid()
        val packages = try {
            context.packageManager.getPackagesForUid(callingUid)
        } catch (_: Exception) {
            null
        } ?: return false
        return packages.contains(ModuleConfig.SYSTEM_UI_PACKAGE)
    }

    private fun ensureContextualSearchService(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader,
        systemServerInstance: Any,
        source: String
    ) {
        if (isContextualSearchServiceAlive()) {
            logger.debug { "$source: contextual_search service already exists" }
            return
        }

        val systemServiceManager = HookSupport.getFieldValue(systemServerInstance, "mSystemServiceManager")
        if (systemServiceManager == null) {
            logger.warn("$source: mSystemServiceManager is null, cannot bootstrap contextual_search")
            return
        }

        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        if (serviceClass == null) {
            logger.warn("$source: ContextualSearchManagerService class not found, cannot bootstrap")
            return
        }

        val startServiceMethod = HookSupport.findMethod(
            systemServiceManager.javaClass,
            "startService",
            Class::class.java
        )
        if (startServiceMethod == null) {
            logger.warn("$source: SystemServiceManager.startService(Class) not found")
            return
        }

        try {
            module.getInvoker(startServiceMethod).invoke(systemServiceManager, serviceClass)
            if (isContextualSearchServiceAlive()) {
                logger.debug { "$source: bootstrapped ContextualSearchManagerService" }
            } else {
                logger.warn("$source: startService(Class) was called, but contextual_search is still unavailable")
            }
        } catch (exception: Exception) {
            // XposedFrameworkError is an Error and must keep propagating to the framework.
            logger.error("$source: failed to bootstrap ContextualSearchManagerService", exception)
        }
    }

    private fun isContextualSearchServiceAlive(): Boolean =
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, ModuleConfig.CONTEXTUAL_SEARCH_SERVICE) as? IBinder
            binder?.isBinderAlive == true
        }.getOrDefault(false)
}
