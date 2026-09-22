package io.github.mangi.eta.agent.accessibility

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * Before the GUI tools execute, confirm that the Eta accessibility service is actually connected.
 *
 * Persistent protection, Secure Settings writes, and rebinding after disconnection are all handled in-process by
 * AccessibilityProtectionRuntime using WRITE_SECURE_SETTINGS held through the privileged system-app install. This does not request
 * Root, nor does it directly modify system settings from here; fail closed when protection is disabled or the app cannot write
 * secure settings (a plain APK install without the module reports the missing privilege explicitly).
 */
object AgentAccessibilityKeeper {
    internal fun ensureEnabledForGuiOperation(context: Context): AccessibilityEnableResult {
        val startedAt = SystemClock.elapsedRealtime()
        val result = ensureAvailable(
            serviceAvailable = AgentAccessibilityService::isAvailable,
            protectionEnabled = { AccessibilityProtectionClient.isEnabled(context) },
            requestRecovery = {
                AccessibilityProtectionClient.requestRecoveryBlocking(context) ==
                    AccessibilityProtectionClient.ControlStatus.APPLIED
            },
            awaitServiceBinding = ::awaitServiceBinding,
            protectionAvailable = { canWriteSecureSettings(context) },
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (result.available) {
            AndroidAgentLogger.info(
                "Agent accessibility action=ensure_for_gui outcome=completed " +
                    "recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs"
            )
        } else {
            AndroidAgentLogger.warn(
                "Agent accessibility action=ensure_for_gui outcome=failed " +
                    "code=${result.code} recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs"
            )
        }
        return result
    }

    internal fun ensureAvailable(
        serviceAvailable: () -> Boolean,
        protectionEnabled: () -> Boolean,
        requestRecovery: () -> Boolean,
        awaitServiceBinding: () -> Boolean,
        protectionAvailable: () -> Boolean = { true },
    ): AccessibilityEnableResult {
        if (serviceAvailable()) {
            return AccessibilityEnableResult.available(recoveryRequested = false)
        }
        if (!protectionAvailable() || !protectionEnabled()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_UNAVAILABLE",
                message = "su accessibility service is not connected; enable the su accessibility service in system settings",
                recoveryRequested = false,
            )
        }
        if (!requestRecovery()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_PROTECTION_UNAVAILABLE",
                message = "Accessibility protection backend is unavailable; this GUI operation was not executed",
                recoveryRequested = true,
            )
        }
        if (!awaitServiceBinding()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_REPAIR_TIMEOUT",
                message = "su accessibility service did not connect within the recovery time limit; this GUI operation was not executed",
                recoveryRequested = true,
            )
        }
        return AccessibilityEnableResult.available(recoveryRequested = true)
    }

    /**
     * The privileged system-app install grants WRITE_SECURE_SETTINGS; a plain APK
     * install never holds it, so protection (and any Secure Settings write) fails
     * closed here with an explicit unavailable result instead of attempting recovery.
     */
    private fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun awaitServiceBinding(): Boolean {
        repeat(SERVICE_BIND_ATTEMPTS) {
            if (AgentAccessibilityService.isAvailable()) return true
            SystemClock.sleep(SERVICE_BIND_POLL_MS)
        }
        return AgentAccessibilityService.isAvailable()
    }

    private const val SERVICE_BIND_ATTEMPTS = 60
    private const val SERVICE_BIND_POLL_MS = 100L
}

internal data class AccessibilityEnableResult(
    val available: Boolean,
    val code: String = "",
    val message: String = "",
    val recoveryRequested: Boolean,
) {
    companion object {
        fun available(
            recoveryRequested: Boolean,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = true,
            recoveryRequested = recoveryRequested,
        )

        fun failure(
            code: String,
            message: String,
            recoveryRequested: Boolean,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = false,
            code = code,
            message = message,
            recoveryRequested = recoveryRequested,
        )
    }
}
