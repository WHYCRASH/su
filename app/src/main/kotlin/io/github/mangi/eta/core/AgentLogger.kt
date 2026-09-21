package io.github.mangi.eta.core

import android.util.Log

internal interface AgentLogger {
    /**
     * Logs information used only for development-time diagnostics.
     *
     * supplier may only construct diagnostic text and must not carry side effects that program correctness depends on; Release builds remove the entire call.
     */
    fun debug(message: () -> String)

    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

internal object AndroidAgentLogger : AgentLogger {
    private val logThrottle = LogThrottle()

    override fun debug(message: () -> String) {
        val text = message()
        Log.d(ModuleConfig.TAG, text)
        AppFileLogger.debug(text)
    }

    override fun info(message: String) {
        Log.i(ModuleConfig.TAG, message)
        AppFileLogger.info(message)
    }

    override fun warn(message: String) {
        Log.w(ModuleConfig.TAG, message)
        AppFileLogger.warn(message)
    }

    fun warnThrottled(
        key: String,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (logThrottle.shouldLog("warn:$key", windowMs)) {
            warn(message())
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.e(ModuleConfig.TAG, message)
        } else {
            Log.e(ModuleConfig.TAG, message, throwable)
        }
        AppFileLogger.error(message, throwable)
    }

    fun errorThrottled(
        key: String,
        throwable: Throwable? = null,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (logThrottle.shouldLog("error:$key", windowMs)) {
            error(message(), throwable)
        }
    }
}
