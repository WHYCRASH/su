package io.github.mangi.eta.agent.voice

import kotlinx.coroutines.flow.collect
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechSession
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.agent.voice.tts.SpeechSpeakableText
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class VoiceEntryMode(val wireValue: String) {
    DICTATION("dictation"),
    UNIVERSAL("universal");

    companion object {
        // Unknown legacy values fall back to dictation instead of enabling a missing mode.
        fun fromWireValue(value: String): VoiceEntryMode =
            entries.firstOrNull { it.wireValue == value } ?: DICTATION
    }
}

enum class VoiceModePhase { Off, Connecting, Listening, Transcribing, Thinking, Speaking, Error }

data class VoiceModeState(
    val mode: VoiceEntryMode? = null,
    val phase: VoiceModePhase = VoiceModePhase.Off,
    val transcript: String = "",
    val reply: String = "",
    val error: String? = null,
) {
    val active: Boolean get() = phase != VoiceModePhase.Off && phase != VoiceModePhase.Error
}

data class VoiceChatSnapshot(
    val isStreaming: Boolean = false,
    val lastAgentId: String? = null,
    val lastAgentText: String = "",
)

/** Owns the cascaded voice-chat loop: offline recognition, model reply, spoken output. */
internal class VoiceModeController(
    context: Context,
    private val scope: CoroutineScope,
    private val submit: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val mutableState = MutableStateFlow(VoiceModeState())
    val state = mutableState.asStateFlow()
    private val chat = MutableStateFlow(VoiceChatSnapshot())
    private var job: Job? = null
    private var diagnostic: VoiceDiagnostics? = null

    init {
        VoiceInputConfig.load(app)
        OfflineSpeechPack.initialize(app)
        scope.launch {
            VoiceInputConfig.state.collect { config ->
                state.value.mode?.let { if (!VoiceEntryPolicy.enabled(config, it)) stop() }
            }
        }
    }

    fun updateChat(snapshot: VoiceChatSnapshot) {
        chat.value = snapshot
        val current = mutableState.value
        if (current.mode == VoiceEntryMode.UNIVERSAL &&
            current.phase in setOf(VoiceModePhase.Thinking, VoiceModePhase.Speaking)) {
            mutableState.value = current.copy(reply = snapshot.lastAgentText)
        }
    }

    fun start(mode: VoiceEntryMode) {
        if (mode == VoiceEntryMode.DICTATION || job?.isActive == true ||
            !VoiceEntryPolicy.enabled(VoiceInputConfig.state.value, mode)) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = VoiceModeState(mode, VoiceModePhase.Error, error = "Microphone permission is required")
            return
        }
        when (mode) {
            VoiceEntryMode.UNIVERSAL -> startUniversal()
            VoiceEntryMode.DICTATION -> Unit
        }
    }

    private fun startUniversal() {
        VoiceInputConfig.load(app)
        if (!SpeechInputSession.ready(VoiceEntryMode.UNIVERSAL)) {
            mutableState.value = VoiceModeState(
                VoiceEntryMode.UNIVERSAL,
                VoiceModePhase.Error,
                error = "Enable conversation mode and download the offline speech pack first",
            )
            return
        }
        stop()
        val trace = VoiceDiagnostics("universal")
        diagnostic = trace
        trace.mark("session.begin")
        job = scope.launch {
            try {
                while (true) {
                    var text = ""
                    mutableState.value = VoiceModeState(VoiceEntryMode.UNIVERSAL, VoiceModePhase.Connecting)
                    val token = SpeechPlayback.beginInput()
                    trace.mark("recognition.begin")
                    val heard = try {
                        SpeechInputSession.recognize(
                            app,
                            mode = VoiceEntryMode.UNIVERSAL,
                            onListening = {
                                trace.mark("recognition.listening")
                                mutableState.value = mutableState.value.copy(phase = VoiceModePhase.Listening)
                            },
                            onText = { next ->
                                trace.mark("recognition.partial", "chars" to next.length, sampled = true)
                                text = next
                                mutableState.value = mutableState.value.copy(
                                    phase = VoiceModePhase.Listening,
                                    transcript = next,
                                )
                            },
                        )
                    } finally {
                        SpeechPlayback.endInput(token)
                    }
                    trace.mark("recognition.done", "heard" to if (heard) 1 else 0, "chars" to text.length)
                    if (!heard || text.isBlank()) continue

                    val baseline = chat.value.lastAgentId
                    mutableState.value = mutableState.value.copy(phase = VoiceModePhase.Transcribing)
                    trace.mark("model.submit", "chars" to text.length)
                    submit(text)
                    mutableState.value = mutableState.value.copy(phase = VoiceModePhase.Thinking)
                    withTimeout(20_000) {
                        chat.first { it.isStreaming || it.lastAgentId != baseline }
                    }
                    val owner = "voice-mode-${UUID.randomUUID()}"
                    var spoken = 0
                    var speakingMessageId: String? = null
                    withTimeout(240_000) {
                        while (true) {
                            val snap = chat.value
                            trace.mark("model.snapshot", "streaming" to if (snap.isStreaming) 1 else 0, "chars" to snap.lastAgentText.length, sampled = true)
                            if (snap.lastAgentId == baseline || snap.lastAgentId == null) {
                                chat.first { it != snap }
                                continue
                            }
                            if (speakingMessageId != snap.lastAgentId) {
                                speakingMessageId = snap.lastAgentId
                                spoken = 0
                            }
                            val ready = SpeechSpeakableText.committedSentences(
                                snap.lastAgentText,
                                finalized = !snap.isStreaming && snap.lastAgentId != baseline,
                            )
                            val utterances = ready.drop(spoken)
                            if (utterances.isNotEmpty()) {
                                mutableState.value = mutableState.value.copy(
                                    phase = VoiceModePhase.Speaking,
                                    reply = snap.lastAgentText,
                                )
                                for (utterance in utterances) {
                                    SpeechPlayback.speak(app, owner, utterance, trace)
                                    val playback = SpeechPlayback.state.first { it.owner != owner }
                                    trace.mark("tts.await_done", "error" to if (playback.error != null) 1 else 0)
                                    playback.error?.let { error(it) }
                                }
                                spoken = ready.size
                            } else if (snap.lastAgentText.isNotBlank()) {
                                mutableState.value = mutableState.value.copy(reply = snap.lastAgentText)
                            }
                            if (!snap.isStreaming && snap.lastAgentId != baseline) break
                            chat.first {
                                it.lastAgentText != snap.lastAgentText ||
                                    it.isStreaming != snap.isStreaming ||
                                    it.lastAgentId != snap.lastAgentId
                            }
                        }
                    }
                    delay(300)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                mutableState.value = VoiceModeState(
                    mode = VoiceEntryMode.UNIVERSAL,
                    phase = VoiceModePhase.Error,
                    error = e.message ?: "Voice conversation failed",
                )
            } finally {
                SpeechPlayback.stop()
            }
        }
    }

    fun stop() {
        diagnostic?.mark("controller.stop")
        job?.cancel()
        job = null
        SpeechPlayback.stop()
        mutableState.value = VoiceModeState()
        diagnostic?.finish()
        diagnostic = null
    }
}
