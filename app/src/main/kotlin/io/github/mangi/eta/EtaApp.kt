package io.github.mangi.eta

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Application for the module UI process.
 *
 * Registers the [XposedServiceHelper] listener at process start; the framework pushes the binder through XposedProvider,
 * after which the UI can use [XposedService] to write RemotePreferences, syncing across processes to each hook process.
 *
 * The UI side writes RemotePreferences through [XposedService].
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        SettingsDataStore.init(this)
        AppFileLogger.install(this)
        applicationScope.launch {
            runCatching { SettingsDataStore.incrementLaunchCount() }
            runCatching {
                SettingsDataStore.fileLoggingEnabledFlow().collect { enabled ->
                    AppFileLogger.setEnabled(enabled)
                }
            }
        }
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        AssistantRepository.init(this)
        McpServerRepository.init(this)
        runBlocking(Dispatchers.IO) {
            runCatching {
                io.github.mangi.eta.data.repository.EtaBackupRepository.recoverInterruptedImport(this@EtaApp)
            }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Interrupted backup recovery failed: type=${throwable.safeLogType()}"
                )
            }
        }
        // Complete legacy accounting migration before any UI/runtime can issue a new request.
        runBlocking(Dispatchers.IO) {
            io.github.mangi.eta.data.repository.UsageStatsRepository.initializeConversationUsage(this@EtaApp)
        }
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // Only clear and dispatch null when the currently held service dies;
        // in multi-framework setups the dead instance may be an already-replaced old one that should not affect the UI.
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
