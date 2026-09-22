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
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
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
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.agent.voice.EtaVoiceInteractionService
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
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
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Module configuration screen.
 *
 * Switch defaults are defined centrally by [Prefs.Keys.BOOLEAN_DEFAULTS]. Switches consumed by the
 * su Runtime are written to the app's local configuration.
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
                        title = stringResource(R.string.ui_enable_web_browsing_tools_8b6b03),
                        key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                        icon = Icons.Rounded.Language,
                    )

                    SwitchPref(
                        title = stringResource(R.string.ui_enable_device_direct_tools_e2d595),
                        key = Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                        icon = Icons.Rounded.Smartphone,
                    )

                    SwitchPref(
                        title = stringResource(R.string.ui_allow_reading_of_sensitive_device_information_feaec0),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                        icon = Icons.Rounded.Visibility,
                    )

                    SwitchPref(
                        title = stringResource(R.string.ui_allow_sensitive_device_operation_3d42ea),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                        icon = Icons.Rounded.GppMaybe,
                    )

                    SwitchPref(
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

            // ── System assistant ───────────────────────────────
            item(key = "section_system_assistant") {
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
                                enabled = !accessibilityProtectionPending,
                            )
                        },
                        enabled = !accessibilityProtectionPending,
                    )
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
}

// ── Boolean switch with icon ────────────────────────────────────────────────

/**
 * A single boolean switch backed by the app's local configuration: its state is read with
 * [Prefs.isEnabled] and written back with [Prefs.putBoolean] when toggled.
 */
@Composable
private fun SwitchPref(
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
) {
    var checked by remember(key) { mutableStateOf(Prefs.isEnabled(key)) }
    val prefs = remember { Prefs.localAgentPreferences() }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) {
                checked = Prefs.isEnabled(key)
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
            Prefs.putBoolean(key, value)
            checked = value
        },
        startAction = {
            PreferenceIcon(icon = icon)
        },
    )
}

private fun defaultDiagnosticLogFileName(): String =
    "su-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip"

private fun isEtaAssistantActive(context: Context): Boolean =
    VoiceInteractionService.isActiveService(
        context,
        ComponentName(context, EtaVoiceInteractionService::class.java),
    )
