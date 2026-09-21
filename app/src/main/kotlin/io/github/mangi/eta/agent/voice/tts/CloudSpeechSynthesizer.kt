package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal class CloudSpeechSynthesizer(
    private val httpClient: OkHttpClient = AgentHttpClient.modelClient.newBuilder()
        .callTimeout(90, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
    private val diagnostic: io.github.mangi.eta.agent.voice.VoiceDiagnostics = io.github.mangi.eta.agent.voice.VoiceDiagnostics("synthesis"),
) {
    private val streamingClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .callTimeout(210, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }
    suspend fun synthesize(config: AgentModelClient.ModelConfig, text: String, voice: String): ByteArray {
        speechCheck(text.isNotBlank()) { "No readable text" }
        speechCheck(voice.isNotBlank()) { "Select a voice" }
        val engine = SpeechEngineResolver.resolve(config.providerSourceType, config.baseUrl, config.model)
        val request = SpeechProtocols.request(engine, config, text, voice)
        val client = if (engine == SpeechEngine.MINIMAX || engine == SpeechEngine.QWEN) {
            streamingClient
        } else {
            httpClient
        }
        return executeAudio(client, request, rawMp3 = engine == SpeechEngine.OPENAI || engine == SpeechEngine.COSYVOICE || engine == SpeechEngine.MOSS) { type, bytes ->
            SpeechProtocols.decode(engine, type, bytes)
        }
    }
    private suspend fun executeAudio(
        client: OkHttpClient,
        request: Request,
        rawMp3: Boolean,
        decode: (String?, ByteArray) -> ByteArray = Companion::decodeJsonAudio,
    ): ByteArray =
        suspendCancellableCoroutine { continuation ->
            diagnostic.mark("synthesis.http.begin")
            val call = client.newCall(request)
            continuation.invokeOnCancellation { diagnostic.mark("synthesis.cancelled"); call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    diagnostic.mark("synthesis.network_failure")
                    if (continuation.isActive) continuation.resumeWithException(IOException("Speech network request failed; check the connection and endpoint"))
                }
                override fun onResponse(call: Call, response: Response) {
                    diagnostic.mark("synthesis.http.response", "http" to response.code)
                    try {
                        val bytes = response.use {
                            speechCheck(it.isSuccessful) { "Speech endpoint HTTP ${it.code}; check the Speech protocol, model, and voice" }
                            val body = it.body
                            speechCheck(body.contentLength() <= MAX_AUDIO_BYTES) { "Spoken audio exceeds the size limit" }
                            val out = ByteArrayOutputStream()
                            body.byteStream().use { stream ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("cancelled")
                                    val n = stream.read(buffer)
                                    if (n < 0) break
                                    speechCheck(out.size() + n <= MAX_AUDIO_BYTES) { "Spoken audio exceeds the size limit" }
                                    out.write(buffer, 0, n)
                                }
                            }
                            val payload = out.toByteArray()
                            diagnostic.mark("synthesis.body", "bytes" to payload.size)
                            if (rawMp3) {
                                val type = it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                                if (type in setOf("audio/wav", "audio/x-wav", "audio/wave")) payload
                                else {
                                    validateAudio(it.header("Content-Type"), payload)
                                    payload
                                }
                            } else {
                                decode(it.header("Content-Type"), payload)
                            }
                        }
                        diagnostic.mark("synthesis.decoded", "bytes" to bytes.size)
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (e: Exception) {
                        diagnostic.mark("synthesis.failed")
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is SpeechPlaybackFailure) e else SpeechPlaybackFailure("Failed to read the spoken audio"),
                        )
                    }
                }
            })
        }

    companion object {
        const val MAX_AUDIO_BYTES = 8 * 1024 * 1024
        internal fun validateAudio(contentType: String?, bytes: ByteArray) {
            val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
            speechCheck(type in setOf("audio/mpeg", "audio/mp3", "application/octet-stream")) { "Speech endpoint did not return MP3 audio" }
            val id3 = bytes.size >= 10 && bytes[0] == 73.toByte() && bytes[1] == 68.toByte() && bytes[2] == 51.toByte()
            val frame = bytes.size >= 4 && (bytes[0].toInt() and 0xff) == 0xff &&
                (bytes[1].toInt() and 0xe0) == 0xe0 && (bytes[1].toInt() and 0x06) != 0
            speechCheck(id3 || frame) { "Speech endpoint returned invalid audio; it may be an error page or JSON" }
        }

        internal fun decodeJsonAudio(contentType: String?, bytes: ByteArray): ByteArray {
            if (looksLikeMp3(bytes)) {
                validateAudio("audio/mpeg", bytes)
                return bytes
            }
            val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
            speechCheck(
                type.isEmpty() ||
                    type == "application/json" ||
                    type == "text/plain" ||
                    type == "text/event-stream" ||
                    type == "application/octet-stream",
            ) { "Speech endpoint returned neither audio nor JSON" }
            val text = runCatching { bytes.decodeToString() }.getOrDefault("").trim()
            speechCheck(text.isNotBlank()) { "Speech endpoint returned an empty response" }
            val payloads = extractJsonPayloads(text)
            speechCheck(payloads.isNotEmpty()) { "Speech endpoint returned invalid audio; it may be an error page or JSON" }
            val out = ByteArrayOutputStream()
            var failed = false
            payloads.forEach { json ->
                val code = json.opt("code")
                if (code is Number && code.toInt() != 0 && code.toInt() != 20000000 && code.toInt() != 3000) {
                    failed = true
                    return@forEach
                }
                val audio = json.optString("audio").ifBlank { json.optString("data") }
                if (audio.isBlank()) return@forEach
                val decoded = runCatching {
                    Base64.getDecoder().decode(audio.filterNot { it.isWhitespace() })
                }.getOrNull() ?: return@forEach
                speechCheck(out.size() + decoded.size <= MAX_AUDIO_BYTES) { "Spoken audio exceeds the size limit" }
                out.write(decoded)
            }
            val audioBytes = out.toByteArray()
            speechCheck(audioBytes.isNotEmpty()) {
                if (failed) "Speech endpoint returned an error status" else "Speech endpoint returned no audio data"
            }
            val decoded = SpeechProtocols.decodeAudio(audioBytes)
            speechCheck(decoded.bytes.isNotEmpty()) { "Speech endpoint returned no audio data" }
            return decoded.bytes
        }

        internal fun extractJsonPayloads(text: String): List<JSONObject> {
            val stripped = text.replace(Regex("^data:", RegexOption.MULTILINE), "").trim()
            val out = ArrayList<JSONObject>()
            val tokener = org.json.JSONTokener(stripped)
            while (tokener.more()) {
                val next = runCatching { tokener.nextValue() }.getOrNull() ?: break
                if (next is JSONObject) out += next
            }
            if (out.isNotEmpty()) return out
            stripped.lineSequence().forEach { raw ->
                val line = raw.trim().removePrefix("data:").trim()
                if (line.isEmpty() || line == "[DONE]") return@forEach
                runCatching { JSONObject(line) }.getOrNull()?.let(out::add)
            }
            return out
        }

        private fun looksLikeMp3(bytes: ByteArray): Boolean {
            if (bytes.size < 4) return false
            val id3 = bytes.size >= 10 && bytes[0] == 73.toByte() && bytes[1] == 68.toByte() && bytes[2] == 51.toByte()
            val frame = (bytes[0].toInt() and 0xff) == 0xff &&
                (bytes[1].toInt() and 0xe0) == 0xe0 && (bytes[1].toInt() and 0x06) != 0
            return id3 || frame
        }
    }
}
