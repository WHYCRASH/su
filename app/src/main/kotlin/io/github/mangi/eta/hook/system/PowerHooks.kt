package io.github.mangi.eta.hook.system

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.libxposed.api.XposedModule

internal object PowerHooks {
    private const val OEM_ASSISTANT_HAPTIC_EFFECT_ID = 0
    private const val OEM_ASSISTANT_HAPTIC_REASON = "Speech - Long Press"

    @Volatile
    private var lastInterceptUptime = 0L

    private enum class LaunchResult {
        LAUNCHED,
        ACTIVITY_FALLBACK_REQUIRED,
        NOT_HANDLED
    }

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "Power")
        return hooks.install {
            // Field-verified on this device family: OplusSpeechHandler is the required path; the target is read live on the hot path.
            hookOplusSpeechHandler(hooks, classLoader)
        }
    }

    private fun hookOplusSpeechHandler(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val handlerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OP_LUS_SPEECH_HANDLER_CLASS)
        val handleMessageMethod = handlerClass?.let {
            HookSupport.findMethod(it, "handleMessage", Message::class.java)
        }
        if (handleMessageMethod == null) {
            hooks.missing(
                id = "system.power-assist-message",
                description = "OplusSpeechHandler.handleMessage",
                detail = "OplusSpeechHandler.handleMessage(Message) not found"
            )
            return
        }

        hooks.intercept(
            id = "system.power-assist-message",
            executable = handleMessageMethod,
            description = "PhoneWindowManagerExtImpl\$OplusSpeechHandler.handleMessage"
        ) { chain ->
            val message = chain.getArg(0) as? Message
            if (message?.what != ModuleConfig.OP_LUS_ASSIST_MESSAGE_WHAT) {
                return@intercept chain.proceed()
            }

            val target = Prefs.powerAssistantTarget()
            val binding = assistantBindingFor(target)
            if (binding == null) {
                return@intercept chain.proceed()
            }

            val handler = chain.getThisObject() as? Handler
            val pwm = resolvePhoneWindowManager(chain.getThisObject())
            if (pwm == null) {
                logger.warnThrottled("oplus_speech_missing_pwm") {
                    "OplusSpeechHandler could not resolve PhoneWindowManager, falling back to the original behavior"
                }
                return@intercept chain.proceed()
            }

            when (tryLaunchAssistant(
                target = target,
                binding = binding,
                logger = logger,
                phoneWindowManager = pwm,
                source = "OplusSpeechHandler"
            )) {
                LaunchResult.LAUNCHED -> null
                LaunchResult.ACTIVITY_FALLBACK_REQUIRED -> {
                    val activityStarted = tryStartAssistantActivityFallback(
                        target = target,
                        binding = binding,
                        logger = logger,
                        phoneWindowManager = pwm,
                        source = "OplusSpeechHandler"
                    )
                    // The activity fallback handles this trigger, but the preferred voiceinteraction path still needs background repair.
                    scheduleBackgroundRecovery(
                        handler = handler,
                        logger = logger,
                        phoneWindowManager = pwm,
                        source = "OplusSpeechHandler"
                    )
                    if (activityStarted) {
                        null
                    } else {
                        // The current trigger does not wait for background repair; after all fast paths fail, fall back to the OEM assistant immediately.
                        chain.proceed()
                    }
                }
                LaunchResult.NOT_HANDLED -> chain.proceed()
            }
        }
    }

    private fun tryLaunchAssistant(
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): LaunchResult {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_missing_context") {
                "$source missing mContext, falling back to the original behavior"
            }
            return LaunchResult.NOT_HANDLED
        }

        if (!HookSupport.isPackageInstalled(context, binding.packageName)) {
            logger.warnThrottled("${source}_${target.persistedValue}_missing") {
                "$source: ${binding.displayName} is not installed, falling back to the original behavior"
            }
            return LaunchResult.NOT_HANDLED
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "$source: hit the dedup window, swallowing the duplicate trigger" }
            return LaunchResult.LAUNCHED
        }

        if (AssistantManager.showAssistantSession(
                context = context,
                target = target,
                logger = logger,
                source = source,
                logFailures = false
            )) {
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: launched ${binding.displayName} via voiceinteraction" }
            return LaunchResult.LAUNCHED
        }

        return LaunchResult.ACTIVITY_FALLBACK_REQUIRED
    }

    private fun tryStartAssistantActivityFallback(
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): Boolean {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
            ?: return false
        val now = SystemClock.uptimeMillis()
        return when (target) {
            PowerAssistantTarget.OEM -> false
            PowerAssistantTarget.GEMINI -> startAssistantActivity(
                context = context,
                binding = binding,
                logger = logger,
                phoneWindowManager = phoneWindowManager,
                source = source,
                now = now,
                action = Intent.ACTION_ASSIST,
            ) || startAssistantActivity(
                context = context,
                binding = binding,
                logger = logger,
                phoneWindowManager = phoneWindowManager,
                source = source,
                now = now,
                action = Intent.ACTION_VOICE_COMMAND,
            )
            PowerAssistantTarget.ETA -> {
                if (!AssistantManager.isAssistantConfigured(context, target)) {
                    false
                } else {
                    startAssistantActivity(
                        context = context,
                        binding = binding,
                        logger = logger,
                        phoneWindowManager = phoneWindowManager,
                        source = source,
                        now = now,
                        action = Intent.ACTION_ASSIST,
                    )
                }
            }
        }
    }

    private fun startAssistantActivity(
        context: Context,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long,
        action: String
    ): Boolean {
        val intent = Intent(action).apply {
            setPackage(binding.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolves = runCatching { HookSupport.resolvesActivity(context, intent) }
            .getOrElse { throwable ->
                logger.warnThrottled("${source}_${action}_resolve_failed") {
                    "$source: failed to query the ${binding.displayName} $action entry, " +
                        "type=${throwable.safeLogType()}"
                }
                false
            }
        if (!resolves) {
            logger.warnThrottled("${source}_${action}_missing") {
                "$source: ${binding.displayName} does not expose $action, falling back to the original behavior"
            }
            return false
        }

        return runCatching {
            context.startActivity(intent)
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: launched ${binding.displayName} via $action" }
            true
        }.getOrElse { throwable ->
            logger.warnThrottled("${source}_${action}_failed") {
                "$source: $action launch failed, falling back to the original behavior, type=${throwable.safeLogType()}"
            }
            false
        }
    }

    private fun finalizeSuccessfulLaunch(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long
    ) {
        markLaunchSuccess(now)
        maybePerformAssistantHapticFeedback(logger, phoneWindowManager, source)
    }

    private fun maybePerformAssistantHapticFeedback(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (invokeOplusAssistantHapticFeedback(phoneWindowManager)) {
            logger.debug { "$source: replayed the Oplus native assistant haptic" }
            return
        }

        logger.warnThrottled("${source}_assistant_haptic_missing") {
            "$source: Oplus native long-press assistant haptic entry not found"
        }
    }

    private fun invokeOplusAssistantHapticFeedback(phoneWindowManager: Any): Boolean {
        val wrapper = HookSupport.invokeNoArgs(phoneWindowManager, "getWrapper") ?: return false
        val wrapperMethod = HookSupport.findMethod(
            wrapper.javaClass,
            "performHapticFeedback",
            Int::class.javaPrimitiveType!!,
            String::class.java
        ) ?: return false
        return runCatching {
            wrapperMethod.invoke(wrapper, OEM_ASSISTANT_HAPTIC_EFFECT_ID, OEM_ASSISTANT_HAPTIC_REASON)
            true
        }.getOrDefault(false)
    }

    private fun scheduleBackgroundRecovery(
        handler: Handler?,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (!Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG) ||
            Prefs.powerAssistantTarget() == PowerAssistantTarget.OEM
        ) {
            return
        }
        if (handler == null) {
            logger.warnThrottled("${source}_recovery_missing_handler") {
                "$source: could not obtain the OplusSpeechHandler instance, skipping background config repair"
            }
            return
        }

        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_recovery_missing_context") {
                "$source: could not obtain mContext, skipping background config repair"
            }
            return
        }

        val scheduled = AssistantManager.scheduleAssistantRecovery(
            context = context,
            logger = logger,
            handler = handler,
            forceRefresh = true,
        )
        if (!scheduled) {
            logger.warnThrottled("${source}_configuration_schedule_failed") {
                "$source: default-assistant background repair could not be enqueued"
            }
        } else {
            logger.warnThrottled("${source}_assistant_recovery_pending") {
                "$source: voiceinteraction failed, default-assistant config repair scheduled in the background"
            }
        }
    }

    private fun markLaunchSuccess(now: Long) {
        lastInterceptUptime = now
    }

    private fun resolvePhoneWindowManager(handlerInstance: Any): Any? {
        val owner = HookSupport.getFieldValue(handlerInstance, "this$0") ?: return null
        HookSupport.findField(owner.javaClass, "mPhoneWindowManager")?.let { field ->
            return runCatching { field.get(owner) }.getOrNull()
        }

        var current: Class<*>? = owner.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (field.type.name == ModuleConfig.PHONE_WINDOW_MANAGER_CLASS) {
                    field.isAccessible = true
                    return runCatching { field.get(owner) }.getOrNull()
                }
            }
            current = current.superclass
        }
        return null
    }
}
