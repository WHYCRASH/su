package io.github.mangi.eta.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object HookSupport {

    fun findClassOrNull(classLoader: ClassLoader, className: String): Class<*>? =
        try {
            Class.forName(className, false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }

    fun findMethod(
        clazz: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                // Keep checking the superclass.
            } catch (_: SecurityException) {
                // When the current class is restricted, the superclass may still expose a compatible entry.
            } catch (_: LinkageError) {
                // Treat a ROM signature referencing a missing type as "target absent"; never crash system_server.
            }
            current = current.superclass
        }
        return null
    }

    /**
     * Filters public methods by structure at install time. Treats a ROM signature referencing a missing type as "target absent".
     */
    fun findPublicMethod(
        clazz: Class<*>,
        predicate: (Method) -> Boolean
    ): Method? = try {
        clazz.methods.firstOrNull(predicate)
    } catch (_: SecurityException) {
        null
    } catch (_: LinkageError) {
        null
    }

    /**
     * Filters declared methods by structure at install time. Skips a single inaccessible method without affecting other candidates.
     */
    fun findDeclaredMethods(
        clazz: Class<*>,
        makeAccessible: Boolean = false,
        predicate: (Method) -> Boolean
    ): List<Method> = try {
        clazz.declaredMethods
            .filter(predicate)
            .filter { method ->
                !makeAccessible || try {
                    method.isAccessible = true
                    true
                } catch (_: SecurityException) {
                    false
                } catch (_: LinkageError) {
                    false
                }
            }
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: LinkageError) {
        emptyList()
    }

    fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                // Keep checking the superclass.
            } catch (_: SecurityException) {
                // When the current class is restricted, the superclass may still expose a compatible field.
            } catch (_: LinkageError) {
                // Treat a ROM signature referencing a missing type as "target absent"; never crash system_server.
            }
            current = current.superclass
        }
        return null
    }

    fun getFieldValue(target: Any, name: String): Any? {
        val field = findField(target.javaClass, name) ?: return null
        return try {
            field.get(target)
        } catch (_: IllegalAccessException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun invokeNoArgs(target: Any, name: String): Any? {
        val method = findMethod(target.javaClass, name) ?: return null
        return try {
            method.invoke(target)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun deoptimize(
        module: XposedModule,
        logger: ModuleLogger,
        executable: Executable,
        description: String
    ) {
        try {
            val deoptimized = module.deoptimize(executable)
            logger.debug { "Deopt $description = $deoptimized" }
        } catch (exception: Exception) {
            logger.warn("Deopt failed: $description, type=${exception.safeLogType()}")
        }
    }

    fun extractPackageName(componentOrPackage: String?): String? {
        if (componentOrPackage.isNullOrBlank()) return null
        return ComponentName.unflattenFromString(componentOrPackage)?.packageName
            ?: componentOrPackage.substringBefore('/', componentOrPackage)
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun resolvesActivity(context: Context, intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent, 0) != null
}
