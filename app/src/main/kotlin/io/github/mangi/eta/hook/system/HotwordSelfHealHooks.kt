package io.github.mangi.eta.hook.system

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.mangi.eta.config.Prefs
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicInteger

internal object HotwordSelfHealHooks {
    private const val RESUME_RETRY_COUNT = 3
    private const val RESUME_INITIAL_DELAY_MS = 1_200L
    private const val RESUME_STEP_DELAY_MS = 1_400L
    private const val SCREEN_OFF_COOLDOWN_MS = 8_000L

    @Volatile
    private var lastScreenOffScheduleUptime = 0L

    private val resumeGeneration = AtomicInteger(0)
    private val pendingLock = Any()
    private var pendingResumeHandler: Handler? = null
    private var pendingResumeRunnable: Runnable? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "HotwordSelfHeal")
        val logger = hooks.logger
        return hooks.install {
            val phoneWindowManagerClass = HookSupport.findClassOrNull(
                classLoader,
                ModuleConfig.PHONE_WINDOW_MANAGER_CLASS
            )
            if (phoneWindowManagerClass == null) {
                hooks.skipped(
                    id = "system.hotword-screen-off",
                    description = "PhoneWindowManager.screenTurnedOff",
                    detail = "PhoneWindowManager not found, skipping screenTurnedOff hook"
                )
                hooks.skipped(
                    id = "system.hotword-screen-on",
                    description = "PhoneWindowManager.screenTurnedOn",
                    detail = "PhoneWindowManager not found, skipping screenTurnedOn hook"
                )
                return@install
            }

            val screenTurnedOffMethod = HookSupport.findMethod(
                phoneWindowManagerClass,
                "screenTurnedOff",
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )
            if (screenTurnedOffMethod != null) {
                hooks.intercept(
                    id = "system.hotword-screen-off",
                    executable = screenTurnedOffMethod,
                    description = "PhoneWindowManager.screenTurnedOff"
                ) { chain ->
                    val displayId = chain.getArg(0) as? Int ?: -1
                    val result = chain.proceed()
                    // Do not resume hotword detection while the toggle is off.
                    if (displayId == 0 && Prefs.isEnabled(Prefs.Keys.HOTWORD_SELF_HEAL)) {
                        scheduleHotwordResume(chain.getThisObject(), logger)
                    }
                    result
                }
            } else {
                hooks.missing(
                    id = "system.hotword-screen-off",
                    description = "PhoneWindowManager.screenTurnedOff",
                    detail = "PhoneWindowManager.screenTurnedOff(int, boolean) not found"
                )
            }

            val screenTurnedOnMethod = HookSupport.findMethod(
                phoneWindowManagerClass,
                "screenTurnedOn",
                Int::class.javaPrimitiveType!!
            )
            if (screenTurnedOnMethod != null) {
                hooks.intercept(
                    id = "system.hotword-screen-on",
                    executable = screenTurnedOnMethod,
                    description = "PhoneWindowManager.screenTurnedOn"
                ) { chain ->
                    val displayId = chain.getArg(0) as? Int ?: -1
                    val result = chain.proceed()
                    if (displayId == 0) {
                        cancelPendingResume()
                    }
                    result
                }
            } else {
                hooks.missing(
                    id = "system.hotword-screen-on",
                    description = "PhoneWindowManager.screenTurnedOn",
                    detail = "PhoneWindowManager.screenTurnedOn(int) not found"
                )
            }
        }
    }

    private fun scheduleHotwordResume(phoneWindowManager: Any, logger: ModuleLogger) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScreenOffScheduleUptime < SCREEN_OFF_COOLDOWN_MS) {
            return
        }

        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
            ?: return
        if (!isDeviceNonInteractive(context)) {
            return
        }
        lastScreenOffScheduleUptime = now
        val handler = HookSupport.getFieldValue(phoneWindowManager, "mHandler") as? Handler
            ?: Handler(Looper.getMainLooper())
        val generation = resumeGeneration.incrementAndGet()
        var attempt = 1

        lateinit var retryRunnable: Runnable
        retryRunnable = Runnable {
            if (resumeGeneration.get() != generation) {
                return@Runnable
            }
            // Switched off mid-flight: the toggle may have been turned off while the delayed task was queued.
            if (!Prefs.isEnabled(Prefs.Keys.HOTWORD_SELF_HEAL)) {
                cancelPendingResume(resetCooldown = true)
                return@Runnable
            }
            if (!isDeviceNonInteractive(context)) {
                cancelPendingResume(resetCooldown = true)
                return@Runnable
            }

            val resumed = AssistantManager.resumeSoftwareHotwordDetection(
                logger = logger,
                source = "ScreenOffHotwordSelfHeal$attempt",
                logFailures = attempt == RESUME_RETRY_COUNT
            )
            if (resumed) {
                cancelPendingResume(resetCooldown = false)
                logger.debug { "ScreenOffHotwordSelfHeal: resumed Google software hotword detection" }
                return@Runnable
            }

            if (attempt >= RESUME_RETRY_COUNT) {
                clearPendingResume(resetCooldown = false)
                return@Runnable
            }

            attempt += 1
            handler.postDelayed(retryRunnable, RESUME_STEP_DELAY_MS)
        }

        replacePendingResume(handler, retryRunnable)
        handler.postDelayed(retryRunnable, RESUME_INITIAL_DELAY_MS)
    }

    private fun cancelPendingResume() {
        cancelPendingResume(resetCooldown = true)
    }

    private fun cancelPendingResume(resetCooldown: Boolean) {
        resumeGeneration.incrementAndGet()
        clearPendingResume(resetCooldown)
    }

    private fun replacePendingResume(handler: Handler, runnable: Runnable) {
        synchronized(pendingLock) {
            pendingResumeRunnable?.let { pendingResumeHandler?.removeCallbacks(it) }
            pendingResumeHandler = handler
            pendingResumeRunnable = runnable
        }
    }

    private fun clearPendingResume(resetCooldown: Boolean) {
        synchronized(pendingLock) {
            pendingResumeRunnable?.let { pendingResumeHandler?.removeCallbacks(it) }
            pendingResumeHandler = null
            pendingResumeRunnable = null
        }
        if (resetCooldown) {
            lastScreenOffScheduleUptime = 0L
        }
    }

    private fun isDeviceNonInteractive(context: Context): Boolean =
        runCatching {
            context.getSystemService(PowerManager::class.java)?.isInteractive == false
        }.getOrDefault(false)
}
