package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.media.hasSupportedImageMagic
import io.github.mangi.eta.agent.media.sniffAgentImageMimeType
import io.github.mangi.eta.data.model.ProviderTypes
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class AgentImageGenerationClient(
    private val httpClient: OkHttpClient = AgentHttpClient.modelClient,
) {
    data class InputImage(
        val bytes: ByteArray,
        val mimeType: String,
    )

    data class GeneratedImage(
        val bytes: ByteArray,
        val mimeType: String,
    )

    data class Result(
        val images: List<GeneratedImage>,
        val text: String = "",
    )

    fun generate(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage> = emptyList(),
    ): Result {
        require(config.baseUrl.isNotBlank()) { "Please configure the API address first" }
        require(prompt.isNotBlank()) { "Please enter an image description" }
        require(config.providerType != ProviderTypes.ANTHROPIC) {
            "The current provider does not support the image generation API"
        }
        val headers = requestHeaders(config)
        val inputImages = images.filter { it.bytes.isNotEmpty() }
        val attempts = buildList {
            if (inputImages.isNotEmpty()) {
                add(Attempt.Edits)
            }
            add(Attempt.Generations)
            add(Attempt.ChatCompletions)
        }
        var lastError: String? = null
        attempts.forEach { attempt ->
            val response = runCatching { execute(config, prompt, inputImages, headers, attempt) }
                .getOrElse { throwable ->
                    lastError = throwable.message ?: throwable.javaClass.simpleName
                    return@forEach
                }
            if (response.ok) {
                val parsed = AgentImageGenerationParser.parse(response.body)
                val generated = materialize(parsed)
                if (generated.images.isNotEmpty()) return generated
                lastError = "The response contains no image"
                return@forEach
            }
            lastError = AgentImageGenerationParser.errorMessage(response.body, response.code)
            if (!response.retryable) {
                error(lastError ?: "Image generation failed")
            }
        }
        error(lastError ?: "Image generation failed")
    }

    private enum class Attempt { Generations, Edits, ChatCompletions }

    private data class RawResponse(
        val code: Int,
        val body: String,
        val ok: Boolean,
        val retryable: Boolean,
    )

    private fun execute(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage>,
        headers: Headers,
        attempt: Attempt,
    ): RawResponse {
        val request = when (attempt) {
            Attempt.Generations -> {
                val body = generationsBody(config, prompt).toString().toRequestBody(JSON_MEDIA_TYPE)
                Request.Builder()
                    .url(ProviderUrls.openAiImagesGenerationsUrl(config.baseUrl))
                    .headers(headers)
                    .post(body)
                    .build()
            }
            Attempt.Edits -> {
                val body = editsBody(config, prompt, images)
                Request.Builder()
                    .url(ProviderUrls.openAiImagesEditsUrl(config.baseUrl))
                    .headers(headers)
                    .post(body)
                    .build()
            }
            Attempt.ChatCompletions -> {
                val body = chatBody(config, prompt, images).toString().toRequestBody(JSON_MEDIA_TYPE)
                Request.Builder()
                    .url(ProviderUrls.openAiChatCompletionsUrl(config.baseUrl))
                    .headers(headers)
                    .post(body)
                    .build()
            }
        }
        return httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            val retryable = response.code in RETRYABLE_HTTP_CODES ||
                (response.code == 400 && looksLikeWrongEndpoint(body))
            RawResponse(
                code = response.code,
                body = body,
                ok = response.isSuccessful,
                retryable = retryable,
            )
        }
    }

    private fun generationsBody(config: AgentModelClient.ModelConfig, prompt: String): JSONObject =
        JSONObject()
            .put("model", config.model)
            .put("prompt", prompt)
            .put("n", 1)
            .also { mergeRequestExtras(it, config, keepMessages = false) }

    private fun chatBody(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage>,
    ): JSONObject {
        val content: Any = if (images.isEmpty()) {
            prompt
        } else {
            JSONArray().put(
                JSONObject().put("type", "text").put("text", prompt),
            ).also { array ->
                images.forEach { image ->
                    val mime = image.mimeType.ifBlank { "image/png" }
                    val dataUrl = "data:$mime;base64," +
                        java.util.Base64.getEncoder().encodeToString(image.bytes)
                    array.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", dataUrl)),
                    )
                }
            }
        }
        return JSONObject()
            .put("model", config.model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content),
                ),
            )
            .also { mergeRequestExtras(it, config, keepMessages = true) }
            .put("stream", false)
    }

    private fun editsBody(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage>,
    ): MultipartBody {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", config.model)
            .addFormDataPart("prompt", prompt)
        images.forEachIndexed { index, image ->
            val mime = image.mimeType.ifBlank { "image/png" }
            val filename = "image$index.${AgentImageGenerationParser.extensionForMime(mime)}"
            builder.addFormDataPart(
                "image",
                filename,
                image.bytes.toRequestBody(mime.toMediaType()),
            )
        }
        return builder.build()
    }

    private fun mergeRequestExtras(
        target: JSONObject,
        config: AgentModelClient.ModelConfig,
        keepMessages: Boolean,
    ) {
        val messages = if (keepMessages) target.optJSONArray("messages") else null
        val prompt = if (!keepMessages) target.opt("prompt") else null
        if (config.extraBodyJson.isNotBlank()) {
            runCatching { JSONObject(config.extraBodyJson) }.getOrNull()?.let { extra ->
                extra.keys().forEach { key -> target.put(key, extra.get(key)) }
            }
        }
        RequestBodyMerge.mergeCustomBody(target, config.customBody)
        target.remove("tools")
        target.remove("tool_choice")
        if (keepMessages) {
            target.put("stream", false)
            if (messages != null) target.put("messages", messages)
        } else {
            target.remove("stream")
            target.remove("messages")
            if (prompt != null) target.put("prompt", prompt)
            if (!target.has("n")) target.put("n", 1)
        }
        target.put("model", config.model)
    }

    private fun requestHeaders(config: AgentModelClient.ModelConfig): Headers =
        Headers.Builder()
            .add("Accept", "application/json")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders) }
            .build()

    private fun materialize(parsed: AgentImageGenerationParser.Parsed): Result {
        val images = parsed.images.mapNotNull { ref ->
            val bytes = when {
                ref.bytes != null -> ref.bytes
                !ref.url.isNullOrBlank() -> download(ref.url)
                else -> null
            } ?: return@mapNotNull null
            if (bytes.isEmpty() || bytes.size > MAX_AGENT_IMAGE_BYTES) return@mapNotNull null
            val mime = when {
                bytes.hasSupportedImageMagic() -> bytes.sniffAgentImageMimeType()
                ref.mimeType.startsWith("image/") -> ref.mimeType
                else -> return@mapNotNull null
            }
            GeneratedImage(bytes = bytes, mimeType = mime)
        }
        return Result(images = images, text = parsed.text)
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).get().build()
        return downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val declared = response.body.contentLength()
            if (declared > MAX_AGENT_IMAGE_BYTES) return@use null
            val bytes = response.body.bytes()
            bytes.takeIf { it.isNotEmpty() && it.size <= MAX_AGENT_IMAGE_BYTES }
        }
    }

    private fun looksLikeWrongEndpoint(body: String): Boolean {
        val lower = body.lowercase()
        return listOf(
            "unknown endpoint",
            "not found",
            "no such route",
            "does not exist",
            "unsupported",
            "not supported",
            "unknown url",
        ).any { it in lower }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val RETRYABLE_HTTP_CODES = setOf(404, 405, 501, 502, 503)
        private val downloadClient by lazy {
            AgentHttpClient.modelClient.newBuilder()
                .readTimeout(60_000, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
