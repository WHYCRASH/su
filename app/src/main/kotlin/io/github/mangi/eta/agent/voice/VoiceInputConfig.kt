package io.github.mangi.eta.agent.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Generic on-device speech enablement. Recognition always runs offline;
 * there is no cloud ASR branch anymore.
 *
 * Legacy: the enablement flags stay in the `doubao_voice` preferences file
 * under the `input_enabled` / `conversation_enabled` keys so existing installs
 * keep their settings. The legacy `asr` flag is only read once to seed
 * `input_enabled` on first load, then ignored.
 */
internal object VoiceInputConfig {
    data class Config(val inputEnabled: Boolean = false, val conversationEnabled: Boolean = true)
    private val mutable = MutableStateFlow(Config())
    val state = mutable.asStateFlow()
    fun load(context: Context) {
        val p = context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE)
        // Migrate once from either previously enabled engine; explicit OFF survives reloads.
        if (!p.contains("input_enabled")) p.edit().putBoolean("input_enabled",
            p.getBoolean("asr", false) || context.getSharedPreferences("offline_speech", Context.MODE_PRIVATE).getBoolean("enabled", false)).apply()
        mutable.value = Config(p.getBoolean("input_enabled", false), p.getBoolean("conversation_enabled", true))
    }
    fun save(context: Context, value: Config) {
        context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE).edit()
            .putBoolean("input_enabled", value.inputEnabled)
            .putBoolean("conversation_enabled", value.conversationEnabled).apply()
        mutable.value = value
    }
}
