package io.github.mangi.eta.ui.app

import android.os.SystemClock

/**
 * Accidental-exit guard for root pages: the first back press only records the time; a second press within [timeoutMs] exits.
 */
internal class DoublePressExitGuard(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val uptimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastPressAt = 0L

    fun consume(): Boolean {
        val now = uptimeMillis()
        val shouldExit = lastPressAt != 0L && now - lastPressAt <= timeoutMs
        lastPressAt = if (shouldExit) 0L else now
        return shouldExit
    }

    fun reset() {
        lastPressAt = 0L
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}
