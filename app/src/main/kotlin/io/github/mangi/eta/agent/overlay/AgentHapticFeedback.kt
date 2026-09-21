package io.github.mangi.eta.agent.overlay

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * Haptic semantics for foreground Agent actions.
 *
 * Prefer letting the system render a predefined primitive matched to the linear-motor capability;
 * fall back to a system effect on devices without support, avoiding the constant buzz of fixed
 * durations and default amplitudes.
 */
internal object AgentHapticFeedback {
    enum class Type(
        val primitiveId: Int,
        val primitiveScale: Float,
        val fallbackEffectId: Int,
    ) {
        TAP(
            primitiveId = VibrationEffect.Composition.PRIMITIVE_CLICK,
            primitiveScale = 0.45f,
            fallbackEffectId = VibrationEffect.EFFECT_TICK,
        ),
        LONG_PRESS(
            primitiveId = VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            primitiveScale = 0.7f,
            fallbackEffectId = VibrationEffect.EFFECT_HEAVY_CLICK,
        ),
        SWIPE(
            primitiveId = VibrationEffect.Composition.PRIMITIVE_TICK,
            primitiveScale = 0.32f,
            fallbackEffectId = VibrationEffect.EFFECT_TICK,
        ),
        RUN_STARTED(
            primitiveId = VibrationEffect.Composition.PRIMITIVE_CLICK,
            primitiveScale = 0.62f,
            fallbackEffectId = VibrationEffect.EFFECT_CLICK,
        ),
    }

    fun perform(context: Context, type: Type) {
        if (!Prefs.isEnabled(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK)) return
        if (!isSystemHapticEnabled(context)) return
        val vibrator = context.getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?: return
        if (!vibrator.hasVibrator()) return

        runCatching {
            val supportsPrimitive = vibrator
                .arePrimitivesSupported(type.primitiveId)
                .firstOrNull() == true
            val effect = if (supportsPrimitive) {
                VibrationEffect.startComposition()
                    .addPrimitive(
                        type.primitiveId,
                        TouchHaptics.currentIntensity().primitiveScale(type.primitiveScale),
                    )
                    .compose()
            } else {
                VibrationEffect.createPredefined(type.fallbackEffectId)
            }
            vibrator.vibrate(effect)
        }
    }

    @Suppress("DEPRECATION")
    private fun isSystemHapticEnabled(context: Context): Boolean =
        runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1,
            ) != 0
        }.getOrDefault(true)
}
