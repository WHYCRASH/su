package io.github.mangi.eta.agent.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View

/**
 * Entry bridge for the system digital assistant.
 *
 * The system creates a TYPE_VOICE_INTERACTION window for this class, but Eta's actual UI is hosted by its own
 * TYPE_APPLICATION_OVERLAY window, so vendor assistant animations and input layers are not tied to the system session window.
 */
internal class EtaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_HIDE_FOR_FOREGROUND_OPERATION) {
                hide()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
        context.registerReceiver(
            controlReceiver,
            IntentFilter(ACTION_HIDE_FOR_FOREGROUND_OPERATION),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCreateContentView(): View = View(context)

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        EtaAssistantOverlayService.show(context)
    }

    override fun onBackPressed() {
        EtaAssistantOverlayService.dismiss(context)
        hide()
    }

    override fun onCloseSystemDialogs() {
        EtaAssistantOverlayService.dismiss(context)
        hide()
    }

    override fun onDestroy() {
        // The overlay has an independent lifecycle; a system session rebuild does not mean the user dismissed the assistant.
        context.unregisterReceiver(controlReceiver)
        super.onDestroy()
    }

    internal companion object {
        private const val ACTION_HIDE_FOR_FOREGROUND_OPERATION =
            "io.github.mangi.eta.agent.voice.HIDE_FOR_FOREGROUND_OPERATION"

        fun requestHideForForegroundOperation(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_HIDE_FOR_FOREGROUND_OPERATION)
                    .setPackage(context.packageName),
            )
        }
    }
}
