package io.github.mangi.eta

import android.app.Application
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionRuntime
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Application for the app process.
 *
 * Owns process-wide initialization only: configuration, the terminal runtime, root state, storage and the
 * repositories. There is no framework-side component and no cross-process configuration, so every feature
 * runs in this app's own processes.
 */
class EtaApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
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
        AccessibilityProtectionRuntime.start(this)
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
}
