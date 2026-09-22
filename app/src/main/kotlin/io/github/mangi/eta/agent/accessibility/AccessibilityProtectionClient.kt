package io.github.mangi.eta.agent.accessibility

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * Thin local facade for the "Force-keep accessibility" toggle.
 *
 * Enforcement lives in-process in [AccessibilityProtectionRuntime] and writes Secure
 * Settings directly, which requires `android.permission.WRITE_SECURE_SETTINGS` held via
 * the privileged system-app install. A plain APK install without the module cannot
 * write secure settings and fails closed: [setEnabled] reports [ControlStatus.REJECTED]
 * and [requestRecoveryBlocking] reports [ControlStatus.UNAVAILABLE].
 */
internal object AccessibilityProtectionClient {
    private const val PREFERENCES_NAME = "accessibility_protection"
    private const val PREFERENCE_ENABLED = "enabled"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        val fallback = appContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(
                PREFERENCE_ENABLED,
                AccessibilityProtectionRuntime.DEFAULT_ENABLED,
            )
        return try {
            Settings.Global.getInt(
                appContext.contentResolver,
                AccessibilityProtectionRuntime.SETTING_NAME,
                if (fallback) 1 else 0,
            ) == 1
        } catch (_: RuntimeException) {
            fallback
        }
    }

    fun setEnabled(
        context: Context,
        enabled: Boolean,
        onResult: (ControlResult) -> Unit,
    ) {
        val appContext = context.applicationContext
        val result = try {
            if (enabled && !AccessibilityProtectionRuntime.isServiceValid(appContext)) {
                ControlResult(ControlStatus.REJECTED, isEnabled(appContext))
            } else {
                val stored = Settings.Global.putInt(
                    appContext.contentResolver,
                    AccessibilityProtectionRuntime.SETTING_NAME,
                    if (enabled) 1 else 0,
                )
                if (!stored) {
                    ControlResult(ControlStatus.UNAVAILABLE, isEnabled(appContext))
                } else {
                    appContext
                        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREFERENCE_ENABLED, enabled)
                        .apply()
                    // Idempotent either way: reconcile starts enforcement when turning
                    // on and tears the watchers down when turning off.
                    AccessibilityProtectionRuntime.start(appContext)
                    ControlResult(ControlStatus.APPLIED, enabled)
                }
            }
        } catch (_: SecurityException) {
            ControlResult(ControlStatus.REJECTED, isEnabled(appContext))
        } catch (_: RuntimeException) {
            ControlResult(ControlStatus.UNAVAILABLE, isEnabled(appContext))
        }
        mainHandler.post { onResult(result) }
    }

    fun requestRecoveryBlocking(context: Context): ControlStatus {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ControlStatus.UNAVAILABLE
        }
        val appContext = context.applicationContext
        return try {
            if (!AccessibilityProtectionRuntime.isProtectionEnabled(appContext)) {
                return ControlStatus.UNAVAILABLE
            }
            if (!AccessibilityProtectionRuntime.isServiceValid(appContext)) {
                return ControlStatus.REJECTED
            }
            val limiter = AccessibilityRepairLimiter()
            while (true) {
                AccessibilityProtectionRuntime.enforceOnce(appContext, "recovery")
                if (isConfiguredAndConnected(appContext)) {
                    return ControlStatus.APPLIED
                }
                val attempt = limiter.nextAttempt(SystemClock.elapsedRealtime())
                    ?: break
                AccessibilityProtectionRuntime.rebindOnce(appContext)
                SystemClock.sleep(attempt.disabledDurationMs)
            }
            if (isConfiguredAndConnected(appContext)) {
                ControlStatus.APPLIED
            } else {
                ControlStatus.UNAVAILABLE
            }
        } catch (_: SecurityException) {
            ControlStatus.REJECTED
        } catch (_: RuntimeException) {
            ControlStatus.UNAVAILABLE
        }
    }

    private fun isConfiguredAndConnected(context: Context): Boolean =
        AccessibilityProtectionRuntime.isServiceConfigured(context) &&
            AgentAccessibilityService.isAvailable()

    data class ControlResult(
        val status: ControlStatus,
        val enabled: Boolean,
    )

    enum class ControlStatus {
        APPLIED,
        UNAVAILABLE,
        REJECTED,
    }
}
