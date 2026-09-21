package io.github.mangi.eta.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.agent.voice.EtaVoiceInteractionService
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.systemizer.GoogleAppSystemizerInstaller
import io.github.mangi.eta.systemizer.RootManager
import io.github.mangi.eta.systemizer.SystemizerInstallResult
import io.github.mangi.eta.ui.app.EnhancementSettingsHistory
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.navigation.AppRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.SwitchPreference
import io.github.mangi.eta.ui.components.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Module configuration screen.
 *
 * Switch defaults are defined centrally by [Prefs.Keys.BOOLEAN_DEFAULTS]. Switches consumed by the
 * su Runtime are written to the app's local configuration; switches consumed only by hooks are
 * submitted to LSPosed through RemotePreferences.
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    currentProviderId: String? = null,
    currentModelId: String? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val capabilities = rememberDeviceCapabilities()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasConnectedFramework by remember { mutableStateOf(enhancementHistory.hasConnected) }
    var hasUsedSystemizer by remember { mutableStateOf(enhancementHistory.hasUsedSystemizer) }
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }
    val appSettings by SettingsDataStore.settingsFlow().collectAsState(
        initial = io.github.mangi.eta.data.model.Settings(),
    )
    var exportingLogs by remember { mutableStateOf(false) }
    var clearingLogs by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }
    val exportLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            exportingLogs = true
            try {
                val output = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)
                        ?: error(context.getString(R.string.settings_export_logs_open_failed))
                }
                val count = withContext(Dispatchers.IO) {
                    output.use { AppFileLogger.export(it) }
                }
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_export_logs_success, count),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Toast.makeText(
                    context.applicationContext,
                    throwable.message?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.settings_export_logs_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                exportingLogs = false
            }
        }
    }

    var accessibilityProtectionEnabled by remember {
        mutableStateOf(AccessibilityProtectionClient.isEnabled(context))
    }
    var accessibilityProtectionPending by remember { mutableStateOf(false) }
    var etaAssistantActive by remember { mutableStateOf(isEtaAssistantActive(context)) }
    val openAssistantSettings: () -> Unit = {
        val failed = runCatching {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isFailure
        if (failed) {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityProtectionEnabled =
                    AccessibilityProtectionClient.isEnabled(context)
                etaAssistantActive = isEtaAssistantActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Provider / model selection display
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val storedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val storedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val selectedProviderId = currentProviderId?.takeIf { it.isNotBlank() } ?: storedProviderId
    val selectedModelId = currentModelId?.takeIf { it.isNotBlank() } ?: storedModelId
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        "${provider.name} / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
    } ?: stringResource(R.string.settings_not_configured)

    // prefs is bound to XposedService: once the service arrives we switch to RemotePreferences
    // (cross-process commits into the LSPosed database); until then it stays null and the UI
    // forbids edits.
    var prefs by remember { mutableStateOf(Prefs.remotePreferencesForUi(EtaApp.serviceInstance)) }
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    var powerAssistantTarget by remember(prefs) {
        mutableStateOf(prefs?.let(Prefs::powerAssistantTarget) ?: enhancementHistory.powerTarget())
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET ||
                key == Prefs.Keys.POWER_KEY_TAKEOVER
            ) {
                powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(Unit) {
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = Prefs.remotePreferencesForUi(service)
                prefs?.let { connected ->
                    enhancementHistory.captureConnected(connected)
                    hasConnectedFramework = true
                }
                Prefs.reconcileAgentPreferences(service)
                coroutineScope.launch {
                    RuntimeConfigRepository.ensureDefaults(service)
                }
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { EtaApp.removeServiceStateListener(listener) }
    }
    val powerAssistantTargets = PowerAssistantTarget.entries
    val powerAssistantItems = powerAssistantTargets.map { target ->
        DropdownItem(text = target.displayName(context))
    }

    WithoutPressRipple {
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_set_up_7debf9),
        onBack = onBack,
    ) {
            // ── LLM provider ───────────────────────────────────────────
            item(key = "section_agent") {
                SmallTitle(stringResource(R.string.settings_llm_providers))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_model_provider_e8c7f5),
                        summary = providerSummary,
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Memory,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ModelProviders) },
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                        key = Prefs.Keys.AGENT_THINKING_ENABLED,
                        icon = Icons.Rounded.Psychology,
                    )
                }
            }

            item(key = "section_model_features") {
                SmallTitle(stringResource(R.string.settings_model_features))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.route_context_compression),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Compress,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ContextCompression) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.auxiliary_vision_title),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.RemoveRedEye) },
                        onClick = { onNavigate(AppRoute.AuxiliaryVision) },
                    )
                    ArrowPreference(title = "Sub-agents",
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Psychology) },
                        onClick = { onNavigate(AppRoute.SubAgents) })
                    ArrowPreference(
                        title = stringResource(R.string.title_model_title),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.AutoAwesome) },
                        onClick = { onNavigate(AppRoute.TitleModel) },
                    )
                }
            }

            // ── Extensions ─────────────────────────────────────
            item(key = "section_context_extensions") {
                SmallTitle(stringResource(R.string.settings_context_extensions))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_assistants),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.SmartToy,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Assistants()) },
                    )


                    ArrowPreference(
                        title = stringResource(R.string.route_skills),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Extension,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Skills) },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.route_mcp_servers),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.AccountTree,
                            )
                        },
                        onClick = { onNavigate(AppRoute.McpServers) },
                    )
                }
            }

            // ── General ────────────────────────────────────────
            item(key = "section_general") {
                SmallTitle(stringResource(R.string.settings_general))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.voice_mode_title),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.RecordVoiceOver) },
                        onClick = { onNavigate(AppRoute.VoiceModeSettings) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.tts_title),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.VolumeUp) },
                        onClick = { onNavigate(AppRoute.TtsSettings) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.speech_title),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Mic) },
                        onClick = { onNavigate(AppRoute.SpeechSettings) },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.appearance_title),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Palette,
                            )
                        },
                        onClick = { onNavigate(AppRoute.AppearanceSettings) },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.stats_page_title),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.BarChart,
                            )
                        },
                        onClick = { onNavigate(AppRoute.UsageStats) },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.data_backup_title),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Description,
                            )
                        },
                        onClick = { onNavigate(AppRoute.DataBackup) },
                    )
                }
            }

            // ── Tools ──────────────────────────────────────────────────
            item(key = "section_tools") {
                SmallTitle(stringResource(R.string.ui_tool_a72ef1))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_tools_list),
                        startAction = { PreferenceIcon(Icons.Rounded.Dashboard) },
                        onClick = { onNavigate(AppRoute.Tools) },
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_web_browsing_tools_8b6b03),
                        key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                        icon = Icons.Rounded.Language,
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_device_direct_tools_e2d595),
                        key = Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                        icon = Icons.Rounded.Smartphone,
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_reading_of_sensitive_device_information_feaec0),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                        icon = Icons.Rounded.Visibility,
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_sensitive_device_operation_3d42ea),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                        icon = Icons.Rounded.GppMaybe,
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_terminal_file_tools_18bb43),
                        key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                        icon = Icons.Rounded.Terminal,
                    )

                    ArrowPreference(
                        title = stringResource(R.string.ui_linux_tool_environment_314d22),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Inventory2,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.capability_workspace),
                        summary = stringResource(R.string.capability_workspace_summary),
                        startAction = { PreferenceIcon(Icons.Rounded.Folder) },
                        onClick = { onNavigate(AppRoute.Workspace) },
                    )
                }
            }

            item(key = "section_haptics") {
                SmallTitle(stringResource(R.string.haptics_title))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.haptics_list),
                        summary = stringResource(R.string.haptics_entry_summary),
                        startAction = { PreferenceIcon(Icons.Rounded.Vibration) },
                        onClick = { onNavigate(AppRoute.Haptics) },
                    )
                }
            }

            // ── System assistant takeover ──────────────────────
            item(key = "section_assistant_takeover") {
                SmallTitle(stringResource(R.string.ui_system_assistant_takes_over_f46043))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                        summary = stringResource(
                            if (etaAssistantActive) {
                                R.string.settings_default_assistant_active
                            } else {
                                R.string.settings_select_default_assistant
                            },
                        ),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.AutoAwesome,
                            )
                        },
                        onClick = openAssistantSettings,
                    )
                    if (prefs != null || hasConnectedFramework) {
                        WindowSpinnerPreference(
                            title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                            items = powerAssistantItems,
                            selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                            onSelectedIndexChange = { index ->
                                val target = powerAssistantTargets.getOrNull(index)
                                    ?: return@WindowSpinnerPreference
                                val targetPrefs = prefs ?: return@WindowSpinnerPreference
                                if (putStringSync(
                                        prefs = targetPrefs,
                                        key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                                        value = target.persistedValue,
                                    )
                                ) {
                                    powerAssistantTarget = target
                                    enhancementHistory.recordCommittedTarget(target)
                                } else {
                                    Toast.makeText(
                                        context.applicationContext,
                                        context.getString(R.string.settings_write_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            startAction = {
                                PreferenceIcon(
                                    icon = Icons.Rounded.PowerSettingsNew,
                                    enabled = prefs != null,
                                )
                            },
                            enabled = prefs != null,
                        )

                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                            summary = stringResource(R.string.ui_valid_only_for_gemini_and_eta_d5b63d),
                            key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                            icon = Icons.Rounded.Settings,
                        )
                    }
                }
            }


            if (prefs != null || hasConnectedFramework || capabilities.root.isGranted || hasUsedSystemizer) {
                // ── Gemini ─────────────────────────────────────────────────
                item(key = "section_gemini") {
                    SmallTitle("Gemini")
                    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                        if (prefs != null || hasConnectedFramework) {
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_maintain_hey_google_detection_after_screen_rest_9d6877),
                                key = Prefs.Keys.HOTWORD_SELF_HEAL,
                                icon = Icons.Rounded.Hearing,
                            )

                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_lock_screen_evokes_automatic_voice_input_1cde18),
                                key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                                icon = Icons.Rounded.Lock,
                            )

                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_bright_screen_evokes_automatic_voice_input_4358fe),
                                key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                                icon = Icons.Rounded.Mic,
                            )

                        }
                        if (capabilities.root.isGranted || hasUsedSystemizer) {
                            ArrowPreference(
                                title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
                                startAction = {
                                    PreferenceIcon(
                                        icon = Icons.Rounded.Inventory,
                                    )
                                },
                                summary = if (capabilities.root.isGranted) null else stringResource(R.string.capability_root_required),
                                enabled = !installingSystemizer,
                                holdDownState = showSystemizerDialog,
                                onClick = {
                                    if (!capabilities.root.isGranted) {
                                        onNavigate(AppRoute.SystemEnhance)
                                    } else if (!installingSystemizer) {
                                        showSystemizerDialog = true
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (prefs != null || hasConnectedFramework) {
                // ── Circle to Search ───────────────────────────────
                item(key = "section_circle_to_search") {
                    SmallTitle(stringResource(R.string.ui_search_in_one_turn_179584))
                    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_long_press_on_the_gesture_bar_triggers_a_circle_to_s_b80117),
                            key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                            icon = Icons.Rounded.SwipeUp,
                        )
                    }
                }
            }

            item(key = "section_diagnostics") {
                SmallTitle(stringResource(R.string.settings_diagnostics))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_file_logging),
                        summary = stringResource(R.string.settings_file_logging_summary),
                        checked = appSettings.fileLoggingEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                SettingsDataStore.setFileLoggingEnabled(enabled)
                            }
                        },
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.BugReport)
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_export_logs),
                        summary = stringResource(
                            if (exportingLogs) {
                                R.string.settings_export_logs_working
                            } else {
                                R.string.settings_export_logs_summary
                            },
                        ),
                        enabled = !exportingLogs && !clearingLogs,
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Share)
                        },
                        onClick = {
                            if (exportingLogs || clearingLogs) return@ArrowPreference
                            if (!AppFileLogger.hasLogs()) {
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_export_logs_empty),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@ArrowPreference
                            }
                            exportLogsLauncher.launch(defaultDiagnosticLogFileName())
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_clear_logs),
                        summary = stringResource(R.string.settings_clear_logs_summary),
                        enabled = !exportingLogs && !clearingLogs,
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.DeleteSweep)
                        },
                        onClick = {
                            if (exportingLogs || clearingLogs) return@ArrowPreference
                            showClearLogsDialog = true
                        },
                    )
                }
            }

            item(key = "section_permissions") {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.capability_enhancements),
                        summary = stringResource(R.string.capability_enhancements_summary),
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Security)
                        },
                        onClick = { onNavigate(AppRoute.SystemEnhance) },
                    )
                    if (prefs != null || hasConnectedFramework) {
                        SwitchPreference(
                            title = stringResource(R.string.ui_enforce_accessibility_55e838),
                            checked = accessibilityProtectionEnabled,
                            onCheckedChange = { enabled ->
                                if (accessibilityProtectionPending) {
                                    return@SwitchPreference
                                }
                                accessibilityProtectionPending = true
                                AccessibilityProtectionClient.setEnabled(
                                    context = context,
                                    enabled = enabled,
                                ) { result ->
                                    accessibilityProtectionPending = false
                                    val failureMessage = when (result.status) {
                                        AccessibilityProtectionClient.ControlStatus.APPLIED -> null
                                        AccessibilityProtectionClient.ControlStatus.UNAVAILABLE ->
                                            context.getString(R.string.accessibility_protection_unavailable)
                                        AccessibilityProtectionClient.ControlStatus.REJECTED ->
                                            context.getString(R.string.accessibility_protection_rejected)
                                    }
                                    if (failureMessage != null) {
                                        Toast.makeText(
                                            context.applicationContext,
                                            failureMessage,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            startAction = {
                                PreferenceIcon(
                                    icon = Icons.Rounded.VerifiedUser,
                                    enabled = prefs != null && !accessibilityProtectionPending,
                                )
                            },
                            enabled = prefs != null && !accessibilityProtectionPending,
                        )
                    }
                }
            }

            // ── About ───────────────────────────────────────────────────
            item(key = "section_about") {
                SmallTitle(stringResource(R.string.ui_about_bed172))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_source_code_740296),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Code,
                            )
                        },
                        endActions = {
                            Text(
                                text = "GitHub",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/WHYCRASH/su"),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

        if (showClearLogsDialog) {
            WindowDialog(
                show = true,
                title = stringResource(R.string.settings_clear_logs_confirm_title),
                summary = stringResource(R.string.settings_clear_logs_confirm_summary),
                onDismissRequest = {
                    if (!clearingLogs) showClearLogsDialog = false
                },
            ) {
                MiuixDialogActions(
                    confirmText = if (clearingLogs) {
                        stringResource(R.string.settings_export_logs_working)
                    } else {
                        stringResource(R.string.settings_clear_logs)
                    },
                    destructive = true,
                    cancelEnabled = !clearingLogs,
                    confirmEnabled = !clearingLogs,
                    onCancel = { showClearLogsDialog = false },
                    onConfirm = {
                        if (clearingLogs) return@MiuixDialogActions
                        clearingLogs = true
                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    AppFileLogger.clear()
                                }
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_clear_logs_done),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                showClearLogsDialog = false
                            } catch (throwable: Throwable) {
                                if (throwable is CancellationException) throw throwable
                                Toast.makeText(
                                    context.applicationContext,
                                    throwable.message?.takeIf { it.isNotBlank() }
                                        ?: context.getString(R.string.settings_export_logs_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } finally {
                                clearingLogs = false
                            }
                        }
                    },
                )
            }
        }

        SystemizerConfirmDialog(
            show = showSystemizerDialog,
            installing = installingSystemizer,
            onDismissRequest = {
                if (!installingSystemizer) {
                    showSystemizerDialog = false
                }
            },
            onConfirm = {
                if (installingSystemizer) return@SystemizerConfirmDialog
                if (!capabilities.root.isGranted) {
                    showSystemizerDialog = false
                    onNavigate(AppRoute.SystemEnhance)
                    return@SystemizerConfirmDialog
                }
                enhancementHistory.recordSystemizerUse()
                hasUsedSystemizer = true
                showSystemizerDialog = false
                installingSystemizer = true
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        GoogleAppSystemizerInstaller(context.applicationContext).install()
                    }
                    installingSystemizer = false
                    Toast.makeText(
                        context.applicationContext,
                        result.toToastMessage(context),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
}

// ── Google app systemizer confirmation dialog ───────────────────────────────

@Composable
private fun SystemizerConfirmDialog(
    show: Boolean,
    installing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
        summary = stringResource(R.string.ui_system_applications_have_voice_wake_up_permissions_f_0190f2),
        onDismissRequest = onDismissRequest,
    ) {
        MiuixDialogActions(
            confirmText = if (installing) {
                stringResource(R.string.status_processing)
            } else {
                stringResource(R.string.action_confirm)
            },
            cancelEnabled = !installing,
            confirmEnabled = !installing,
            onCancel = onDismissRequest,
            onConfirm = onConfirm,
        )
    }
}

// ── Boolean switch with icon ────────────────────────────────────────────────

/**
 * A single boolean switch: its state is re-read when [prefs]/[key] changes and written back
 * synchronously when toggled.
 *
 * The caller passes the configuration source that matches its capability boundary. Hook switches
 * may still be disabled while LSPosed is disconnected; Agent Runtime switches always use the
 * app's local configuration.
 */
@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
) {
    val enabled = prefs != null
    val history = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: history.checked(key, default))
    }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { value ->
            // Commit synchronously; when RemotePreferences.commit() fails (binder commit
            // failure) roll the UI state back, so the UI never shows a toggle that the hook
            // process never received.
            val targetPrefs = prefs ?: return@SwitchPreference
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
                history.recordCommittedBoolean(key, value)
                if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                    Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
                }
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        startAction = {
            PreferenceIcon(icon = icon, enabled = enabled)
        },
        enabled = enabled,
    )
}
/**
 * Writes a boolean synchronously. RemotePreferences' [commit] updates this process' map first and
 * then waits for the binder commit; on failure (binder RemoteException) it returns false even
 * though the local map was already rewritten, so the hook process never sees the new value.
 * Returns whether the commit succeeded, so the caller can decide whether to update the UI.
 */
private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

private fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

private fun PowerAssistantTarget.displayName(context: Context): String =
    when (this) {
        PowerAssistantTarget.OEM -> context.getString(R.string.power_assistant_system_default)
        PowerAssistantTarget.GEMINI -> "Gemini"
        PowerAssistantTarget.ETA -> "su"
    }

private fun defaultDiagnosticLogFileName(): String =
    "su-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip"

private fun isEtaAssistantActive(context: Context): Boolean =
    VoiceInteractionService.isActiveService(
        context,
        ComponentName(context, EtaVoiceInteractionService::class.java),
    )

private fun SystemizerInstallResult.toToastMessage(context: Context): String =
    when (this) {
        SystemizerInstallResult.AlreadySystemized -> context.getString(R.string.systemizer_already_system)
        SystemizerInstallResult.GoogleAppMissing -> context.getString(R.string.systemizer_google_missing)
        SystemizerInstallResult.UnsupportedRootManager -> context.getString(R.string.systemizer_root_manager_missing)
        SystemizerInstallResult.KernelSuMetamoduleMissing -> context.getString(R.string.systemizer_metamodule_missing)
        is SystemizerInstallResult.RootPermissionUnavailable -> when (rootManager) {
            RootManager.KERNEL_SU -> context.getString(R.string.systemizer_grant_kernelsu)
            RootManager.MAGISK -> context.getString(R.string.systemizer_grant_magisk)
            RootManager.UNSUPPORTED -> context.getString(R.string.systemizer_root_denied)
        }
        is SystemizerInstallResult.InstalledRebootRequired -> context.getString(R.string.systemizer_installed)
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message: $it" }
            ?: message
    }
