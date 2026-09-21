package io.github.mangi.eta.hook.system

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger

import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.lang.reflect.Method

internal object CircleToSearchInvoker {

    @Volatile
    private var getServiceMethod: Method? = null

    @Volatile
    private var asInterfaceMethod: Method? = null

    @Volatile
    private var startContextualSearchMethod: StartContextualSearchMethod? = null

    fun isAvailable(
        context: Context,
        logger: ModuleLogger,
        source: String,
        fallbackMessage: String
    ): Boolean {
        if (!HookSupport.isPackageInstalled(context, ModuleConfig.GOOGLE_PACKAGE)) {
            logger.warnThrottled("${source}_cts_google_missing") {
                "$source: Google App not installed, $fallbackMessage"
            }
            return false
        }

        val intent = Intent(ModuleConfig.CONTEXTUAL_SEARCH_ACTION).setPackage(ModuleConfig.GOOGLE_PACKAGE)
        if (!HookSupport.resolvesActivity(context, intent)) {
            logger.warnThrottled("${source}_cts_entry_missing") {
                "$source: Google App does not expose a Contextual Search entry point, $fallbackMessage"
            }
            return false
        }

        val binder = getContextualSearchBinder() ?: run {
            logger.warnThrottled("${source}_cts_service_missing") {
                "$source: contextual_search service unavailable, $fallbackMessage"
            }
            return false
        }

        return binder.isBinderAlive
    }

    fun trigger(
        logger: ModuleLogger,
        source: String,
        entryPoint: Int = ModuleConfig.CIRCLE_TO_SEARCH_ENTRYPOINT,
    ): Boolean {
        val binder = getContextualSearchBinder() ?: return false
        return runCatching {
            // Call the system binder directly to avoid going through the OEM OCR/screen recognition dispatch chain again.
            val asInterface = resolveAsInterfaceMethod() ?: return@runCatching false
            val startContextualSearch = resolveStartContextualSearchMethod() ?: return@runCatching false
            val service = asInterface.invoke(null, binder) ?: return@runCatching false
            if (startContextualSearch.hasConfigParameter) {
                startContextualSearch.method.invoke(
                    service,
                    entryPoint,
                    null
                )
            } else {
                startContextualSearch.method.invoke(service, entryPoint)
            }
            logger.debug { "$source: triggered Circle to Search" }
            true
        }.getOrElse { throwable ->
            logger.errorThrottled(
                key = "${source}_cts_trigger_failed",
                throwable = throwable
            ) { "$source: failed to trigger Circle to Search" }
            false
        }
    }

    private fun getContextualSearchBinder(): IBinder? =
        runCatching {
            resolveGetServiceMethod()?.invoke(null, ModuleConfig.CONTEXTUAL_SEARCH_SERVICE) as? IBinder
        }.getOrNull()

    private fun resolveGetServiceMethod(): Method? {
        getServiceMethod?.let { return it }
        return runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
        }.getOrNull()?.also { getServiceMethod = it }
    }

    private fun resolveAsInterfaceMethod(): Method? {
        asInterfaceMethod?.let { return it }
        return runCatching {
            Class.forName("android.app.contextualsearch.IContextualSearchManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
        }.getOrNull()?.also { asInterfaceMethod = it }
    }

    private fun resolveStartContextualSearchMethod(): StartContextualSearchMethod? {
        startContextualSearchMethod?.let { return it }
        val serviceClass = runCatching {
            Class.forName("android.app.contextualsearch.IContextualSearchManager")
        }.getOrNull() ?: return null

        val withConfig = runCatching {
            val configClass = Class.forName("android.app.contextualsearch.ContextualSearchConfig")
            serviceClass.getDeclaredMethod(
                "startContextualSearch",
                Int::class.javaPrimitiveType!!,
                configClass
            ).apply { isAccessible = true }
        }.getOrNull()
        if (withConfig != null) {
            return StartContextualSearchMethod(withConfig, hasConfigParameter = true)
                .also { startContextualSearchMethod = it }
        }

        return runCatching {
            serviceClass.getDeclaredMethod(
                "startContextualSearch",
                Int::class.javaPrimitiveType!!
            ).apply { isAccessible = true }
        }.getOrNull()?.let { method ->
            StartContextualSearchMethod(method, hasConfigParameter = false)
        }?.also { startContextualSearchMethod = it }
    }

    private data class StartContextualSearchMethod(
        val method: Method,
        val hasConfigParameter: Boolean
    )
}
