package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Module configuration hub.
 *
 * Every switch the runtime reads lives in the app's own private configuration: the app ships no
 * framework-side hook, so there is no remote preference store and nothing depends on another process.
 * Feature defaults are declared per key in [Keys.BOOLEAN_DEFAULTS] and match what the settings page shows.
 */
internal object Prefs {

    private const val PREFERENCES_GROUP = "eta_agent_preferences"

    /** Every feature switch key. Defaults are chosen per feature risk. */
    object Keys {
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
    }

    @Volatile
    private var preferences: SharedPreferences? = null

    /** Reads an Int config value. */
    fun getInt(key: String, default: Int): Int {
        return preferences?.getInt(key, default) ?: default
    }

    /** Reads a string config value. */
    fun getString(key: String, default: String = ""): String {
        return preferences?.getString(key, default) ?: default
    }

    /** Writes an Int config value. */
    fun putInt(key: String, value: Int) {
        preferences?.edit()?.putInt(key, value)?.apply()
    }

    /** Writes a string config value. */
    fun putString(key: String, value: String) {
        preferences?.edit()?.putString(key, value)?.apply()
    }

    /** Writes a Boolean config value. */
    fun putBoolean(key: String, value: Boolean) {
        preferences?.edit()?.putBoolean(key, value)?.apply()
    }

    /** Called by the app process before any runtime reads configuration. */
    fun init(context: Context) {
        if (preferences == null) {
            synchronized(this) {
                if (preferences == null) {
                    preferences = context.applicationContext.getSharedPreferences(
                        PREFERENCES_GROUP,
                        Context.MODE_PRIVATE,
                    )
                }
            }
        }
    }

    /** Reads a boolean switch; a key without a declared default counts as on. */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        return preferences?.getBoolean(key, default) ?: default
    }

    fun localAgentPreferences(): SharedPreferences? = preferences

    fun exportAgentPreferences(): Map<String, String> {
        val prefs = preferences ?: return emptyMap()
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
        val prefs = preferences ?: return
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
    fun isCustomCompressModelEnabled(preferences: SharedPreferences? = this.preferences): Boolean {
        val prefs = preferences ?: return false
        if (prefs.contains(Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED)) {
            return prefs.getBoolean(Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED, false)
        }
        return !prefs.getString(Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, null).isNullOrBlank() &&
            !prefs.getString(Keys.AGENT_COMPRESS_MODEL_ID, null).isNullOrBlank()
    }
}
