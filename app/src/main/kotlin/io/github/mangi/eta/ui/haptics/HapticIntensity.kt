package io.github.mangi.eta.ui.haptics

import androidx.annotation.StringRes
import io.github.mangi.eta.R

/** Haptic feedback strength. Default follows the system View haptics; low / medium / high use custom amplitudes. */
internal enum class HapticIntensity(
    val wireValue: String,
    @StringRes val labelRes: Int,
    val clickScale: Float,
    val tickScale: Float,
    val clickAmplitude: Int,
    val tickAmplitude: Int,
) {
    DEFAULT("default", R.string.haptics_intensity_default, 0f, 0f, 0, 0),
    LOW("low", R.string.haptics_intensity_low, 0.25f, 0.18f, 48, 32),
    MEDIUM("medium", R.string.haptics_intensity_medium, 0.60f, 0.48f, 140, 96),
    HIGH("high", R.string.haptics_intensity_high, 1.0f, 0.85f, 255, 200),
    ;

    fun primitiveScale(base: Float): Float = when (this) {
        DEFAULT -> base
        LOW -> (base * 0.35f).coerceIn(0.12f, 1f)
        MEDIUM -> (base * 0.75f).coerceIn(0.35f, 1f)
        HIGH -> 1f
    }

    companion object {
        fun fromWire(value: String?): HapticIntensity {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireValue == normalized } ?: DEFAULT
        }
    }
}
