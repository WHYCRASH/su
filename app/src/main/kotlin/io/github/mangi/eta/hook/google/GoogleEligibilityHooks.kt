package io.github.mangi.eta.hook.google

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger

import io.github.libxposed.api.XposedModule

internal object GoogleEligibilityHooks {
    private const val PROP_OPA_ELIGIBLE_DEVICE = "ro.opa.eligible_device"

    private val googleFeatures = setOf(
        "com.google.android.feature.GOOGLE_BUILD",
        "com.google.android.feature.GOOGLE_EXPERIENCE"
    )

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "GoogleEligibility")
        return hooks.install {
            // Eligibility backfill and device-spoofing are one job — convincing the Google app the
            // device is eligible — and always run as the Circle to Search foundation.
            hookSystemProperties(hooks)
            hookPackageManagerFeatures(hooks, classLoader)
        }
    }

    private fun hookSystemProperties(hooks: HookRegistrar) {
        val systemPropertiesClass = try {
            Class.forName("android.os.SystemProperties")
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: LinkageError) {
            null
        }
        if (systemPropertiesClass == null) {
            skipSystemPropertyHooks(hooks)
            return
        }

        HookSupport.findMethod(systemPropertiesClass, "get", String::class.java)?.let { method ->
            hooks.intercept(
                id = "google.system-properties.get",
                executable = method,
                description = "SystemProperties.get(String)"
            ) { chain ->
                val key = chain.getArg(0) as? String
                if (key == PROP_OPA_ELIGIBLE_DEVICE) "true" else chain.proceed()
            }
        } ?: hooks.missing(
            id = "google.system-properties.get",
            description = "SystemProperties.get(String)",
            detail = "SystemProperties.get(String) not found"
        )

        HookSupport.findMethod(
            systemPropertiesClass,
            "get",
            String::class.java,
            String::class.java
        )?.let { method ->
            hooks.intercept(
                id = "google.system-properties.get-default",
                executable = method,
                description = "SystemProperties.get(String,String)"
            ) { chain ->
                val key = chain.getArg(0) as? String
                if (key == PROP_OPA_ELIGIBLE_DEVICE) "true" else chain.proceed()
            }
        } ?: hooks.missing(
            id = "google.system-properties.get-default",
            description = "SystemProperties.get(String,String)",
            detail = "SystemProperties.get(String,String) not found"
        )

        HookSupport.findMethod(
            systemPropertiesClass,
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType!!
        )?.let { method ->
            hooks.intercept(
                id = "google.system-properties.get-boolean",
                executable = method,
                description = "SystemProperties.getBoolean(String,boolean)"
            ) { chain ->
                val key = chain.getArg(0) as? String
                if (key == PROP_OPA_ELIGIBLE_DEVICE) true else chain.proceed()
            }
        } ?: hooks.missing(
            id = "google.system-properties.get-boolean",
            description = "SystemProperties.getBoolean(String,boolean)",
            detail = "SystemProperties.getBoolean(String,boolean) not found"
        )
    }

    private fun skipSystemPropertyHooks(hooks: HookRegistrar) {
        hooks.skipped(
            id = "google.system-properties.get",
            description = "SystemProperties.get(String)",
            detail = "SystemProperties not found, skipping get(String) hook"
        )
        hooks.skipped(
            id = "google.system-properties.get-default",
            description = "SystemProperties.get(String,String)",
            detail = "SystemProperties not found, skipping get(String,String) hook"
        )
        hooks.skipped(
            id = "google.system-properties.get-boolean",
            description = "SystemProperties.getBoolean(String,boolean)",
            detail = "SystemProperties not found, skipping getBoolean(String,boolean) hook"
        )
    }

    private fun hookPackageManagerFeatures(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val packageManagerClass = HookSupport.findClassOrNull(
            classLoader,
            "android.app.ApplicationPackageManager"
        ) ?: run {
            hooks.missing(
                id = "google.package-manager-features",
                description = "ApplicationPackageManager.hasSystemFeature",
                detail = "ApplicationPackageManager not found"
            )
            return
        }

        val methods = HookSupport.findDeclaredMethods(
            clazz = packageManagerClass,
            makeAccessible = true
        ) { method ->
            method.name == "hasSystemFeature" &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.firstOrNull() == String::class.java
        }
        if (methods.isEmpty()) {
            hooks.missing(
                id = "google.package-manager-features",
                description = "ApplicationPackageManager.hasSystemFeature",
                detail = "ApplicationPackageManager.hasSystemFeature(String, ...) not found"
            )
            return
        }
        methods.forEach { method ->
            hooks.intercept(
                id = "google.package-manager-features.${method.parameterTypes.size}",
                executable = method,
                description = "ApplicationPackageManager.${method.name}/${method.parameterTypes.size}"
            ) { chain ->
                val feature = chain.getArg(0) as? String
                if (feature in googleFeatures) true else chain.proceed()
            }
        }
    }
}
