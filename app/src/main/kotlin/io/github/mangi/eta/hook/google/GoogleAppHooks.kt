package io.github.mangi.eta.hook.google

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mangi.eta.config.Prefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.WeakHashMap

internal object GoogleAppHooks {
    private const val FLOATY_ACTIVITY_CLASS =
        "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity"
    private const val VOICE_COMMAND_DELAY_MS = 350L
    private const val VOICE_COMMAND_ACTIVITY_DEDUP_MS = 6_000L

    private val voiceCommandAttemptLock = Any()
    private val voiceCommandAttempts = WeakHashMap<Activity, Long>()

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "GoogleApp")
        val logger = hooks.logger
        return hooks.install {
            // Device spoofing: pose as a Samsung S24 Ultra inside the Google process to unlock the Circle to Search capability ring.
            // Build fields are a one-time write side effect at startup, always applied as the underlying dependency of Circle to Search.
            setBuildField(logger, Build::class.java, "MANUFACTURER", ModuleConfig.SPOOF_MANUFACTURER)
            setBuildField(logger, Build::class.java, "BRAND", ModuleConfig.SPOOF_BRAND)
            setBuildField(logger, Build::class.java, "MODEL", ModuleConfig.SPOOF_MODEL)
            setBuildField(logger, Build::class.java, "PRODUCT", ModuleConfig.SPOOF_PRODUCT)
            setBuildField(logger, Build::class.java, "DEVICE", ModuleConfig.SPOOF_DEVICE)

            // Lock-screen/screen-on voice-input top-up: the toggle is evaluated live inside the intercept callback.
            hookFloatyVoiceCommand(hooks, classLoader)
        }
    }

    private fun hookFloatyVoiceCommand(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val floatyOnResumeMethod = HookSupport.findClassOrNull(classLoader, FLOATY_ACTIVITY_CLASS)
            ?.let { clazz ->
                runCatching {
                    clazz.getDeclaredMethod("onResume").apply { isAccessible = true }
                }.getOrNull()
            }
        if (floatyOnResumeMethod != null) {
            hooks.intercept(
                id = "google.floaty-on-resume",
                executable = floatyOnResumeMethod,
                description = "FloatyActivity.onResume(Google voice command)"
            ) { chain ->
                val result = chain.proceed()
                val activity = chain.getThisObject() as? Activity
                if (activity != null) {
                    scheduleVoiceCommand(activity, logger, fromKeyguard = activity.isKeyguardLocked())
                }
                result
            }
            return
        }

        val onResumeMethod = HookSupport.findMethod(Activity::class.java, "onResume")
        if (onResumeMethod == null) {
            hooks.missing(
                id = "google.floaty-on-resume",
                description = "FloatyActivity.onResume(Google voice command)",
                detail = "FloatyActivity/Activity.onResume() not found, skipping the Gemini voice top-up"
            )
            return
        }

        hooks.intercept(
            id = "google.floaty-on-resume",
            executable = onResumeMethod,
            description = "Activity.onResume(Google Floaty voice command)"
        ) { chain ->
            val result = chain.proceed()
            val activity = chain.getThisObject() as? Activity
            if (activity?.javaClass?.name == FLOATY_ACTIVITY_CLASS) {
                scheduleVoiceCommand(activity, logger, fromKeyguard = activity.isKeyguardLocked())
            }
            result
        }
    }

    private fun scheduleVoiceCommand(activity: Activity, logger: ModuleLogger, fromKeyguard: Boolean) {
        // Locked screens take LOCKSCREEN_VOICE_COMMAND, lit screens take SCREEN_ON_VOICE_COMMAND; no top-up is sent while the toggle is off.
        val prefKey = if (fromKeyguard) {
            Prefs.Keys.LOCKSCREEN_VOICE_COMMAND
        } else {
            Prefs.Keys.SCREEN_ON_VOICE_COMMAND
        }
        if (!Prefs.isEnabled(prefKey)) return
        // Only dedup repeat onResume calls from the same FloatyActivity instance; a freshly reopened overlay right after closing is unaffected.
        if (!markVoiceCommandAttempt(activity)) {
            return
        }

        val scenario = if (fromKeyguard) "lockscreen" else "screen-on"
        Handler(Looper.getMainLooper()).postDelayed({
            // Live toggle check: the user may have turned the switch off while the delayed task was queued.
            if (!Prefs.isEnabled(prefKey)) {
                clearVoiceCommandAttempt(activity)
                return@postDelayed
            }
            if (activity.isFinishing || activity.isDestroyed) {
                clearVoiceCommandAttempt(activity)
                return@postDelayed
            }
            // Abandon if the lock state changed during the delay: the lockscreen branch must still be locked, the screen-on branch still unlocked.
            if (activity.isKeyguardLocked() != fromKeyguard) {
                clearVoiceCommandAttempt(activity)
                return@postDelayed
            }
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        setPackage(ModuleConfig.GOOGLE_PACKAGE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                logger.debug { "GSA: sent the ACTION_VOICE_COMMAND top-up for the ${scenario} Gemini overlay" }
            }.onFailure { throwable ->
                clearVoiceCommandAttempt(activity)
                logger.warnThrottled("gsa_floaty_voice_command_failed") {
                    "GSA: failed to send the ACTION_VOICE_COMMAND top-up for the ${scenario} Gemini overlay, " +
                        "type=${throwable.safeLogType()}"
                }
            }
        }, VOICE_COMMAND_DELAY_MS)
    }

    private fun markVoiceCommandAttempt(activity: Activity): Boolean =
        synchronized(voiceCommandAttemptLock) {
            val now = SystemClock.uptimeMillis()
            val previous = voiceCommandAttempts[activity]
            if (previous != null && now - previous < VOICE_COMMAND_ACTIVITY_DEDUP_MS) {
                false
            } else {
                voiceCommandAttempts[activity] = now
                true
            }
        }

    private fun clearVoiceCommandAttempt(activity: Activity) {
        synchronized(voiceCommandAttemptLock) {
            voiceCommandAttempts.remove(activity)
        }
    }

    private fun Activity.isKeyguardLocked(): Boolean =
        runCatching {
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(false)

    private fun setBuildField(
        logger: ModuleLogger,
        clazz: Class<*>,
        fieldName: String,
        value: String
    ) {
        val field = runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }
        }.getOrElse { throwable ->
            logger.warn("GSA: Build.$fieldName not found, type=${throwable.safeLogType()}")
            return
        }

        runCatching {
            field.set(null, value)
        }.recoverCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply {
                isAccessible = true
            }.get(null)
            val base = unsafeClass.getDeclaredMethod("staticFieldBase", Field::class.java)
                .invoke(theUnsafe, field)
            val offset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field::class.java)
                .invoke(theUnsafe, field) as Long
            unsafeClass.getDeclaredMethod(
                "putObjectVolatile",
                Any::class.java,
                Long::class.javaPrimitiveType!!,
                Any::class.java
            ).invoke(theUnsafe, base, offset, value)
        }.onFailure { throwable ->
            logger.warn("GSA: failed to modify Build.$fieldName, type=${throwable.safeLogType()}")
        }
    }
}
