package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * Module configuration hub.
 *
 * - Hook processes (system_server / SystemUI / Google / system assistant, etc.) call
 *   [attachRemote] when the module loads, caching the framework-provided read-only [SharedPreferences]; afterwards all interception callbacks use [isEnabled]
 *   to read the remote preferences held by the current process.
 * - Switches consumed by Eta Runtime itself are kept in the app's private config and do not depend on Xposed Service.
 * - Switches consumed by hooks are written to RemotePreferences through [remotePreferencesForUi];
 *   when XposedService is not ready, no local fake fallback is provided.
 *
 * Built on [io.github.libxposed.api.XposedInterface.getRemotePreferences] from libxposed API 102
 * and [XposedService.getRemotePreferences] from service 102; both ends share the same group.
 */
internal object Prefs {

    /** Remote config group name; what the UI writes and what hooks read must match. */
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** Every feature switch key. Defaults are chosen per feature risk. */
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // Compatible with the legacy boolean protocol; the new UI no longer writes it, and when the tri-state config is missing, true still means Gemini.
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "screen_on_voice_command"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"
        const val AGENT_AUTO_COMPRESS_ENABLED = "agent_auto_compress_enabled"
        const val AGENT_COMPRESS_MODEL_PROVIDER_ID = "agent_compress_model_provider_id"
        const val AGENT_COMPRESS_MODEL_ID = "agent_compress_model_id"
        const val AGENT_COMPRESS_CUSTOM_MODEL_ENABLED = "agent_compress_custom_model_enabled"
        const val AGENT_MANUAL_COMPRESS_MODEL_PROVIDER_ID = "agent_manual_compress_model_provider_id"
        const val AGENT_MANUAL_COMPRESS_MODEL_ID = "agent_manual_compress_model_id"
        const val AGENT_COMPRESS_ENDPOINT_MODE = "agent_compress_endpoint_mode"
        const val AGENT_MANUAL_COMPRESS_ENDPOINT_MODE = "agent_manual_compress_endpoint_mode"
        const val AGENT_TTS_MODE = "agent_tts_mode"
        const val AGENT_TTS_MODEL_PROVIDER_ID = "agent_tts_model_provider_id"
        const val AGENT_TTS_MODEL_ID = "agent_tts_model_id"
        const val AGENT_TTS_VOICE = "agent_tts_voice"
        const val AGENT_VOICE_LAST_ENTRY = "agent_voice_last_entry"
        const val AGENT_VOICE_DOUBAO_PROVIDER_ID = "agent_voice_doubao_provider_id"
        const val AGENT_VOICE_DOUBAO_VOICE = "agent_voice_doubao_voice"
        const val AGENT_VOICE_DOUBAO_INSTRUCTIONS = "agent_voice_doubao_instructions"
        const val HAPTIC_TOUCH_FEEDBACK = "haptic_touch_feedback"
        const val HAPTIC_MESSAGE_GENERATION = "haptic_message_generation"
        const val HAPTIC_INTENSITY = "haptic_intensity"

        /** All boolean switches and their defaults. */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            HOTWORD_SELF_HEAL to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            LOCKSCREEN_VOICE_COMMAND to false,
            SCREEN_ON_VOICE_COMMAND to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPRESS_ENABLED to false,
            AGENT_COMPRESS_CUSTOM_MODEL_ENABLED to false,
            HAPTIC_TOUCH_FEEDBACK to true,
            HAPTIC_MESSAGE_GENERATION to true,
        )

        /** Switches ultimately decided by Eta Runtime that do not require the Xposed framework to be online. */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_AUTO_COMPRESS_ENABLED,
            AGENT_COMPRESS_CUSTOM_MODEL_ENABLED,
            HAPTIC_TOUCH_FEEDBACK,
            HAPTIC_MESSAGE_GENERATION,
        )
    }

    /** Read-only remote preferences cached by the hook process, injected by ModuleMain in onModuleLoaded. */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** Reads an Agent-local Int config value. */
    fun getInt(key: String, default: Int): Int {
        return localAgent?.getInt(key, default) ?: default
    }

    /** Reads a string config value. Local Agent config takes priority, avoiding contention with read-only remote overrides over the same key. */
    fun getString(key: String, default: String = ""): String {
        localAgent?.let { prefs ->
            if (prefs.contains(key)) return prefs.getString(key, default) ?: default
        }
        return remote?.getString(key, default) ?: default
    }

    /** Writes an Agent-local Int config value. */
    fun putInt(key: String, value: Int) {
        localAgent?.edit()?.putInt(key, value)?.apply()
    }

    /** Writes an Agent-local String config value. */
    fun putString(key: String, value: String) {
        localAgent?.edit()?.putString(key, value)?.apply()
    }

    /** Writes an Agent-local Boolean config value. */
    fun putBoolean(key: String, value: Boolean) {
        localAgent?.edit()?.putBoolean(key, value)?.apply()
    }

    /** Called by the app process: initializes the Agent config that does not depend on Xposed Service. */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    )
                }
            }
        }
    }

    /** Called by the hook process: caches the read-only SharedPreferences provided by the framework. */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** The hook process listens for config changes delivered by the framework; the caller must hold a strong reference to the listener for the lifetime of the process. */
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * Reads a boolean switch. When remote is unavailable (framework not injected or the call failed), falls back to each feature's own default;
     * the defaults match what the settings page displays.
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }


    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    /**
     * The UI process obtains a writable RemotePreferences.
     *
     * commit on [XposedService.getRemotePreferences] synchronously waits for the binder to commit to the LSPosed
     * database and returns false on failure; when the service is not ready it returns null, leaving the UI non-writable.
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()

    /** Local Agent config used by the Eta settings page and Runtime; does not depend on LSPosed. */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    fun exportAgentPreferences(): Map<String, String> {
        val prefs = localAgent ?: return emptyMap()
        return prefs.all.mapNotNull { (key, value) ->
            when (value) {
                is Boolean -> key to "b:$value"
                is Int -> key to "i:$value"
                is Long -> key to "l:$value"
                is String -> key to "s:$value"
                else -> null
            }
        }.toMap()
    }

    fun restoreAgentPreferences(values: Map<String, String>) {
        val prefs = localAgent ?: return
        val editor = prefs.edit().clear()
        values.forEach { (key, encoded) ->
            if (key.isBlank() || encoded.length < 2 || encoded[1] != ':') return@forEach
            val payload = encoded.substring(2)
            when (encoded[0]) {
                'b' -> editor.putBoolean(key, payload.toBooleanStrictOrNull() ?: return@forEach)
                'i' -> editor.putInt(key, payload.toIntOrNull() ?: return@forEach)
                'l' -> editor.putLong(key, payload.toLongOrNull() ?: return@forEach)
                's' -> editor.putString(key, payload)
            }
        }
        editor.commit()
    }

    /** When off, compression uses the current conversation model; legacy configs that already picked a custom model are treated as on. */
    fun isCustomCompressModelEnabled(preferences: SharedPreferences? = localAgent): Boolean {
        val prefs = preferences ?: return false
        if (prefs.contains(Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED)) {
            return prefs.getBoolean(Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED, false)
        }
        return !prefs.getString(Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, null).isNullOrBlank() &&
            !prefs.getString(Keys.AGENT_COMPRESS_MODEL_ID, null).isNullOrBlank()
    }

    /**
     * On the first upgrade, existing RemotePreferences values are migrated into local storage first; after that the local value is the source of truth, and when the framework
     * is available it is written back to remote, so hook entry points that still assemble requests inside the target process get a consistent initial config.
     */
    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        val localEditor = local.edit()
        val remoteEditor = remotePreferences.edit()
        var updateLocal = false
        var updateRemote = false

        Keys.LOCAL_AGENT_KEYS.forEach { key ->
            val default = Keys.BOOLEAN_DEFAULTS.getValue(key)
            when {
                local.contains(key) -> {
                    remoteEditor.putBoolean(key, local.getBoolean(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putBoolean(key, remotePreferences.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        if (updateLocal) localEditor.commit()
        if (updateRemote) runCatching { remoteEditor.commit() }
    }
}
