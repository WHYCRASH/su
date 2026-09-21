package io.github.mangi.eta.agent.voice.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SpeechPlaybackState(
    val owner: String? = null,
    val preparing: Boolean = false,
    val source: String = "",
    val error: String? = null,
    val recording: Boolean = false,
)

/** Latest-request-wins identity; only used on the main thread, including stop and microphone ownership. */
internal class SpeechPlaybackEpoch {
    private var value = 0L
    fun next(): Long = ++value
    fun isCurrent(token: Long): Boolean = token == value
}

/** Single UI-process output controller. No Agent messages, turns or tool calls are created here. */
internal object SpeechPlayback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val epoch = SpeechPlaybackEpoch()
    private var job: Job? = null
    private var recordingToken: Long? = null
    private val mutableState = MutableStateFlow(SpeechPlaybackState())
    val state = mutableState.asStateFlow()
    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    // Call from main; cancellation never affects the Agent run.
    fun stop() {
        epoch.next()
        job?.cancel()
        job = null
        mutableState.value = SpeechPlaybackState(recording = recordingToken != null)
    }

    suspend fun beginInput(): Long {
        stop()
        val token = epoch.next()
        recordingToken = token
        mutableState.value = SpeechPlaybackState(recording = true)
        try {
            // Do not open the microphone until a cancelled player has released its audio resources.
            mutex.withLock { }
            return token
        } catch (cancelled: CancellationException) {
            endInput(token)
            throw cancelled
        }
    }

    fun endInput(token: Long) {
        if (recordingToken == token) {
            recordingToken = null
            mutableState.value = SpeechPlaybackState()
        }
    }

    fun speak(context: Context, owner: String, markdown: String, diagnostic: io.github.mangi.eta.agent.voice.VoiceDiagnostics = io.github.mangi.eta.agent.voice.VoiceDiagnostics("tts")) {
        if (recordingToken != null) { diagnostic.mark("tts.blocked_recording"); return }
        stop()
        start(context, owner, markdown, diagnostic)
    }

    fun toggle(context: Context, owner: String, markdown: String) {
        if (recordingToken != null) return
        if (state.value.owner == owner) { stop(); return }
        start(context, owner, markdown)
    }

    private fun start(context: Context, owner: String, markdown: String, diagnostic: io.github.mangi.eta.agent.voice.VoiceDiagnostics = io.github.mangi.eta.agent.voice.VoiceDiagnostics("tts")) {
        stop()
        val token = epoch.next()
        val app = context.applicationContext
        // Capture preferences once: settings changed while loading must not mix provider/model/voice.
        val cloud = Prefs.getString(Prefs.Keys.AGENT_TTS_MODE) == "cloud"
        diagnostic.mark("tts.begin", "chars" to markdown.length, "cloud" to if (cloud) 1 else 0)
        val providerId = Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID)
        val modelId = Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_ID)
        val voiceId = Prefs.getString(Prefs.Keys.AGENT_TTS_VOICE).trim()
        mutableState.value = SpeechPlaybackState(owner, preparing = true)
        job = scope.launch {
            // A new player cannot overlap the previous player's finally/shutdown.
            mutex.withLock {
                if (!epoch.isCurrent(token)) return@withLock
                try {
                    withContext(Dispatchers.IO) {
                        File(app.cacheDir, "speech-playback").listFiles()?.filter { it.extension in setOf("mp3", "wav", "ogg") }?.forEach { it.delete() }
                    }
                    speechCheck(markdown.length <= SpeechSpeakableText.MAX_SOURCE_CHARS) { "Reply is too long, read it in parts" }
                    val sentences = withContext(Dispatchers.Default) { SpeechSpeakableText.sentences(markdown) }
                    speechCheck(sentences.isNotEmpty()) { "This reply has no readable text" }
                    diagnostic.mark("tts.sentences", "count" to sentences.size)
                    withAudioFocus(app, token, diagnostic) {
                        if (!cloud) {
                            SystemSpeechSynthesizer().speak(app, sentences) { voice ->
                                if (epoch.isCurrent(token)) mutableState.value = SpeechPlaybackState(owner, source = "System voice · $voice")
                            }
                        } else {
                            val config = withContext(Dispatchers.IO) {
                                val provider = ProviderRepository.providerById(providerId)
                                    ?.takeIf(SpeechSynthesisModels::isReadAloudProvider)
                                    ?: throw SpeechPlaybackFailure("This provider does not support read-aloud")
                                val model = SpeechSynthesisModels.mergeCatalog(provider).firstOrNull {
                                    it.id == modelId && it.isEnabled && SpeechSynthesisModels.isReadAloudModel(it, provider)
                                } ?: throw SpeechPlaybackFailure("Read-aloud model is unavailable, reconfigure or use system speech")
                                RuntimeConfigRepository.buildRuntimeConfig(provider, model)
                            }
                            val voice = voiceId
                            speechCheck(voice.isNotEmpty()) { "Select a voice first" }
                            val synth = CloudSpeechSynthesizer(diagnostic = diagnostic)
                            val label = when (SpeechEngineResolver.resolve(config.providerSourceType, config.baseUrl, config.model)) {
                                SpeechEngine.MINIMAX -> "MiniMax"
                                SpeechEngine.STEP -> "Step"
                                SpeechEngine.QWEN -> "Qwen"
                                SpeechEngine.GROQ -> "Groq"
                                SpeechEngine.XAI -> "xAI"
                                SpeechEngine.GEMINI -> "Gemini"
                                SpeechEngine.ELEVENLABS -> "ElevenLabs"
                                SpeechEngine.FISH -> "Fish Audio"
                                SpeechEngine.COSYVOICE -> "CosyVoice"
                                SpeechEngine.MOSS -> "MOSS-TTSD"
                                SpeechEngine.OPENAI -> "Cloud Speech"
                            }
                            supervisorScope {
                                // At most current + next sentence buffered. Never restart from the beginning on failure.
                                var next = async { synth.synthesize(config, sentences.first(), voice) }
                                for (index in sentences.indices) {
                                    val bytes = next.await()
                                    if (index < sentences.lastIndex) next = async { synth.synthesize(config, sentences[index + 1], voice) }
                                    currentCoroutineContext().ensureActive()
                                    if (epoch.isCurrent(token)) mutableState.value = SpeechPlaybackState(owner, source = "$label · ${index + 1}/${sentences.size}")
                                    playMp3(app, bytes, diagnostic)
                                }
                            }
                        }
                    }
                    diagnostic.mark("tts.completed")
                    if (epoch.isCurrent(token)) mutableState.value = SpeechPlaybackState()
                } catch (e: TimeoutCancellationException) {
                    diagnostic.mark("tts.timeout")
                    if (epoch.isCurrent(token)) mutableState.value = SpeechPlaybackState(error = "Read-aloud timed out, retry or check the system speech engine")
                } catch (e: CancellationException) {
                    diagnostic.mark("tts.cancelled")
                    throw e
                } catch (e: Exception) {
                    diagnostic.mark("tts.failed")
                    // Config/decoder exceptions may include URLs, never surface them verbatim.
                    if (epoch.isCurrent(token)) mutableState.value = SpeechPlaybackState(error = when (e) {
                        is SpeechPlaybackFailure -> e.message
                        else -> "Read-aloud failed, check the engine, network, model and voice"
                    })
                } finally {
                    if (epoch.isCurrent(token)) {
                        job = null
                        if (mutableState.value.owner != null) mutableState.value = SpeechPlaybackState()
                    }
                }
            }
        }
    }

    private suspend fun withAudioFocus(context: Context, token: Long, diagnostic: io.github.mangi.eta.agent.voice.VoiceDiagnostics, block: suspend () -> Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                diagnostic.mark("focus.changed", "change" to change)
                if (change < 0 && epoch.isCurrent(token)) stop()
            }.build()
        val focusResult = audio.requestAudioFocus(request)
        diagnostic.mark("focus.request", "result" to focusResult, "volume" to audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        speechCheck(focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Cannot take audio focus right now, try reading aloud later" }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (epoch.isCurrent(token)) stop()
            }
        }
        var registered = false
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            block()
        } finally {
            if (registered) context.unregisterReceiver(receiver)
            audio.abandonAudioFocusRequest(request)
        }
    }

    private suspend fun playMp3(context: Context, bytes: ByteArray, diagnostic: io.github.mangi.eta.agent.voice.VoiceDiagnostics) {
        diagnostic.mark("player.prepare", "bytes" to bytes.size)
        val directory = File(context.cacheDir, "speech-playback")
        val payload = SpeechProtocols.decodeAudio(bytes)
        val file = File(directory, "${UUID.randomUUID()}.${payload.extension}")
        val player = MediaPlayer()
        try {
            withContext(Dispatchers.IO) {
                speechCheck(directory.isDirectory || directory.mkdirs()) { "Cannot create temporary audio directory" }
                file.writeBytes(payload.bytes)
            }
            player.setAudioAttributes(audioAttributes)
            player.setVolume(1f, 1f)
            player.setDataSource(file.absolutePath)
            withTimeout(120_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    player.setOnPreparedListener {
                        if (continuation.isActive) {
                            diagnostic.mark("player.prepared", "durationMs" to it.duration)
                            try { it.start(); diagnostic.mark("player.started") } catch (_: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(SpeechPlaybackFailure("Audio player failed to start"))
                            }
                        }
                    }
                    player.setOnCompletionListener { diagnostic.mark("player.completed"); if (continuation.isActive) continuation.resume(Unit) }
                    player.setOnErrorListener { _, what, extra ->
                        diagnostic.mark("player.error", "what" to what, "extra" to extra)
                        if (continuation.isActive) continuation.resumeWithException(SpeechPlaybackFailure("Cannot play the audio returned by the service"))
                        true
                    }
                    player.prepareAsync()
                }
            }
        } finally {
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { player.release() }
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }
}
