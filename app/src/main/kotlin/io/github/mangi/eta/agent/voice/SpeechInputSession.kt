package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal object SpeechInputSession {
    private val microphone = Mutex()
    fun ready(mode: VoiceEntryMode = VoiceEntryMode.DICTATION): Boolean = VoiceInputConfig.state.value.let {
        VoiceEntryPolicy.enabled(it, mode) && OfflineSpeechPack.state.value.ready
    }
    suspend fun recognize(context: Context, onListening: suspend () -> Unit, onText: suspend (String) -> Unit, mode: VoiceEntryMode = VoiceEntryMode.DICTATION): Boolean {
        check(microphone.tryLock()) { "Speech input is already using the microphone" }
        try {
            VoiceInputConfig.load(context)
            check(ready(mode)) { "Enable speech input and download the offline speech pack first" }
            return coroutineScope {
                val owner = currentCoroutineContext().job
                val watcher = launch {
                    VoiceInputConfig.state.first { !VoiceEntryPolicy.enabled(it, mode) }
                    owner.cancel(CancellationException("Speech input was turned off"))
                }
                try {
                    OfflineSpeechSession.recognize(context, onListening, onText, mode)
                } finally { watcher.cancel() }
            }
        } finally { microphone.unlock() }
    }
}
