package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import io.github.mangi.eta.agent.model.ProviderUrls
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

internal object RemoteModelFetcher {
    private const val MAX_ERROR_CHARS = 600
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(provider: ProviderSetting): Result<List<Model>> =
        withContext(Dispatchers.IO) {
            runCatching {
                io.github.mangi.eta.data.model.RemovedProviderPolicy.requireSupported(provider)
                when (provider) {
                    is AnthropicProviderSetting -> fetchAnthropic(provider)
                    else -> fetchOpenAiCompatible(provider)
                }
            }
        }

    internal fun parseOpenAiModels(body: String): List<Model> {
        val root = json.parseToJsonElement(body).jsonObjectOrNull() ?: return emptyList()
        val data = root["data"]?.jsonArrayOrNull()
            ?: root["models"]?.jsonArrayOrNull()
            ?: return emptyList()
        return data.mapNotNull { element ->
            element.jsonObjectOrNull()?.toModel(defaultOwnedBy = null)
        }
    }

    internal fun parseAnthropicModels(body: String): List<Model> {
        val data = json.parseToJsonElement(body)
            .jsonObjectOrNull()
            ?.get("data")
            ?.jsonArrayOrNull()
            ?: return emptyList()
        return data.mapNotNull { element ->
            element.jsonObjectOrNull()?.toAnthropicModel()
        }
    }

    private fun fetchOpenAiCompatible(provider: ProviderSetting): List<Model> {
        val request = Request.Builder()
            .url(ProviderUrls.openAiModelsUrl(provider.baseUrl))
            .headers(
                okhttp3.Headers.Builder()
                    .add("Accept", "application/json")
                    .apply {
                        if (provider.apiKey.isNotBlank()) {
                            add("Authorization", "Bearer ${provider.apiKey}")
                        }
                        ProviderRequestHeaders.mergeInto(this, provider.baseUrl, provider.customHeaders)
                    }
                    .build()
            )
            .get()
            .build()
        return OfficialModelCatalog.enrich(provider, executeJson(request, "Failed to fetch models").let(::parseOpenAiModels))
    }

    private fun fetchAnthropic(provider: AnthropicProviderSetting): List<Model> {
        val request = Request.Builder()
            .url(ProviderUrls.anthropicModelsUrl(provider.baseUrl))
            .headers(
                okhttp3.Headers.Builder()
                    .add("Accept", "application/json")
                    .add("anthropic-version", provider.anthropicVersion)
                    .apply {
                        if (provider.apiKey.isNotBlank()) {
                            add("x-api-key", provider.apiKey)
                        }
                        ProviderRequestHeaders.mergeInto(this, provider.baseUrl, provider.customHeaders)
                    }
                    .build()
            )
            .get()
            .build()
        return OfficialModelCatalog.enrich(provider, executeJson(request, "Failed to fetch Anthropic models").let(::parseAnthropicModels))
    }

    private fun executeJson(request: Request, errorPrefix: String): String =
        AgentHttpClient.client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("$errorPrefix HTTP ${response.code}: ${body.compactError()}")
            }
            body
        }

    /**
     * Determines whether a model in the remote catalog should be included in the local model list.
     *
     * All entries returned by the remote /models endpoint are kept, and the user selects from them in the list.
     */
    internal fun isCatalogModel(model: Model): Boolean = true

    /**
     * Determines whether a model in the remote catalog can be used for Agent conversations.
     *
     * On OpenAI-compatible platforms, /models also mixes in non-conversational models such as speech recognition, text-to-speech, image/video generation,
     * embedding, and rerank (for example, Alibaba Bailian returns hundreds at once). These models
     * cannot participate in the Agent's text tool-calling loop, so they are filtered out during fetching by id naming patterns and output modality.
     * Image-generation and video-generation models do not enter the conversation loop and are handled separately by the send path.
     */
    internal fun isChatCapableModel(model: Model): Boolean {
        if (model.supportsImageGeneration || model.supportsVideoGeneration) return false
        if (model.outputModalities.isNotEmpty() &&
            model.outputModalities.none { it.equals(Model.TEXT_MODALITY, ignoreCase = true) }
        ) {
            return false
        }
        val id = model.modelId.lowercase()
        return NON_CHAT_MODEL_ID_MARKERS.none { it in id }
    }

    private val NON_CHAT_MODEL_ID_MARKERS = listOf(
        // Speech recognition
        "asr", "whisper", "paraformer", "sensevoice", "gummy",
        // Speech synthesis and voice models
        "tts", "speech", "voice", "cosyvoice", "sambert",
        // Embeddings and reranking
        "embedding", "rerank",
        // Image-specific models other than image generation and understanding
        "image", "dall-e", "flux", "stable-diffusion", "wanx", "hidream",
        // Video generation
        "video", "veo-",
        // Other non-conversational specialized models
        "ocr", "music", "moderation",
    )

    private fun JsonObject.toModel(defaultOwnedBy: String?): Model? {
        val modelId = string("id", "slug")?.trim().orEmpty()
        if (modelId.isBlank()) return null
        val architecture = this["architecture"]?.jsonObjectOrNull()
        val supportedParameters = stringList("supported_parameters", "supportedParameters").orEmpty()
        val reasoningMetadata = this["reasoning"]?.jsonObjectOrNull()
        return Model(
            id = UUID.randomUUID().toString(),
            modelId = modelId,
            displayName = string("display_name", "displayName", "name")?.trim().takeUnless { it.isNullOrBlank() }
                ?: modelId,
            source = ModelSource.REMOTE,
            ownedBy = string("owned_by", "ownedBy")?.trim().takeUnless { it.isNullOrBlank() } ?: defaultOwnedBy,
            contextWindow = positiveInt(
                "context_window",
                "contextWindow",
                "context_length",
                "contextLength",
                "context_limit",
                "contextLimit",
                "max_context_tokens",
                "max_input_tokens",
                "maxInputTokens",
            ),
            inputModalities = inputModalities(architecture),
            outputModalities = stringList("output_modalities", "outputModalities")
                ?: architecture?.stringList("output_modalities", "outputModalities")
                ?: emptyList(),
            attachment = boolean("attachment", "vision", "supports_image_in"),
            toolCall = boolean("tool_call", "toolCall", "tools")
                ?: supportedParameters.supportsAny("tools"),
            reasoning = boolean("reasoning", "thinking", "supports_reasoning")
                ?: supportedParameters.supportsAny(
                    "reasoning",
                    "reasoning_effort",
                    "include_reasoning",
                    "enable_thinking",
                    "thinking_budget",
                )
                ?: reasoningMetadata?.let { true },
            reasoningCapabilities = parseReasoningCapabilities(
                modelObject = this,
                metadata = reasoningMetadata,
                supportedParameters = supportedParameters,
            ),
            structuredOutput = boolean("structured_output", "structuredOutput")
                ?: supportedParameters.supportsAny("structured_outputs", "response_format"),
            supportsTemperature = boolean("supports_temperature", "supportsTemperature")
                ?: supportedParameters.supportsAny("temperature"),
        )
    }

    private fun JsonObject.toAnthropicModel(): Model? {
        val base = toModel(defaultOwnedBy = "anthropic") ?: return null
        val capabilities = this["capabilities"]?.jsonObjectOrNull()
        val effort = capabilities?.get("effort")?.jsonObjectOrNull()
        val thinking = capabilities?.get("thinking")?.jsonObjectOrNull()
        val supportedEfforts = listOf(
            "low" to ReasoningEffort.LOW,
            "medium" to ReasoningEffort.MEDIUM,
            "high" to ReasoningEffort.HIGH,
            "xhigh" to ReasoningEffort.XHIGH,
            "max" to ReasoningEffort.MAX,
        ).mapNotNull { (field, value) ->
            value.takeIf { effort?.capabilitySupported(field) == true }
        }
        val effortSupported = effort?.boolean("supported") == true || supportedEfforts.isNotEmpty()
        val thinkingSupported = thinking?.boolean("supported") == true
        val reasoningSupported = effortSupported || thinkingSupported
        return base.copy(
            contextWindow = positiveInt("max_input_tokens") ?: base.contextWindow,
            attachment = capabilities?.capabilitySupported("image_input") ?: base.attachment,
            reasoning = reasoningSupported.takeIf { capabilities != null } ?: base.reasoning,
            reasoningCapabilities = if (reasoningSupported) {
                ModelReasoningCapabilities(
                    supportedEfforts = supportedEfforts,
                    defaultEffort = ReasoningEffort.HIGH.takeIf {
                        ReasoningEffort.HIGH in supportedEfforts
                    },
                    defaultEnabled = true,
                    canDisable = thinkingSupported,
                )
            } else {
                base.reasoningCapabilities
            },
            structuredOutput = capabilities?.capabilitySupported("structured_outputs")
                ?: base.structuredOutput,
        )
    }

    private fun parseReasoningCapabilities(
        modelObject: JsonObject,
        metadata: JsonObject?,
        supportedParameters: List<String>,
    ): ModelReasoningCapabilities? {
        val supportsBudget = supportedParameters.any {
            it == "thinking_budget" || it == "reasoning_budget"
        }
        val supportsToggle = "enable_thinking" in supportedParameters
        val optionEfforts = modelObject.reasoningOptionEffortValues()
        val declaresNoEffortTiers = modelObject.containsKey("reasoning_options") &&
            optionEfforts.isNullOrEmpty()
        if (
            metadata == null &&
            !supportsBudget &&
            !supportsToggle &&
            optionEfforts.isNullOrEmpty() &&
            !declaresNoEffortTiers
        ) {
            return null
        }
        val declaredEfforts = buildList {
            metadata
                ?.stringList("supported_efforts", "supportedEfforts")
                .orEmpty()
                .mapNotNull(ReasoningEffort::fromWireValue)
                .forEach(::add)
            optionEfforts
                .orEmpty()
                .mapNotNull(ReasoningEffort::fromWireValue)
                .forEach(::add)
        }
            .filter { it != ReasoningEffort.DEFAULT && it != ReasoningEffort.OFF }
            .distinct()
        val supportedEfforts = declaredEfforts.ifEmpty {
            if (supportsBudget && !declaresNoEffortTiers) {
                listOf(
                    ReasoningEffort.LOW,
                    ReasoningEffort.MEDIUM,
                    ReasoningEffort.HIGH,
                    ReasoningEffort.XHIGH,
                    ReasoningEffort.MAX,
                )
            } else {
                emptyList()
            }
        }
        val mandatory = metadata?.boolean("mandatory") == true
        return ModelReasoningCapabilities(
            supportedEfforts = supportedEfforts.filter { it != ReasoningEffort.OFF },
            defaultEffort = ReasoningEffort.fromWireValue(
                metadata?.string("default_effort", "defaultEffort")
            ),
            defaultEnabled = metadata?.boolean("default_enabled", "defaultEnabled"),
            mandatory = mandatory,
            canDisable = !mandatory && (
                metadata != null ||
                    supportsToggle ||
                    declaresNoEffortTiers ||
                    optionEfforts != null ||
                    supportedEfforts.contains(ReasoningEffort.OFF)
                ),
            supportsBudget = supportsBudget,
            maxBudgetTokens = metadata?.positiveInt(
                "max_budget_tokens",
                "maxBudgetTokens",
                "max_reasoning_tokens",
            ),
            supportsMaxTokens = metadata?.boolean("supports_max_tokens", "supportsMaxTokens"),
        )
    }


    private fun JsonObject.reasoningOptionEffortValues(): List<String>? {
        val options = this["reasoning_options"]?.jsonArrayOrNull() ?: return null
        options.forEach { element ->
            val option = element.jsonObjectOrNull() ?: return@forEach
            if (option.string("type") != "effort") return@forEach
            option.stringList("values")?.let { return it }
        }
        return emptyList()
    }

    private fun JsonObject.string(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.int(vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.intOrNull }

    private fun JsonObject.positiveInt(vararg names: String): Int? =
        int(*names)?.takeIf { it > 0 }

    private fun JsonObject.boolean(vararg names: String): Boolean? =
        names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.booleanOrNull }

    private fun JsonObject.stringList(vararg names: String): List<String>? =
        names.firstNotNullOfOrNull { name ->
            this[name]
                ?.jsonArrayOrNull()
                ?.mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull?.trim() }
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
        }

    private fun JsonObject.capabilitySupported(name: String): Boolean? =
        this[name]
            ?.jsonObjectOrNull()
            ?.boolean("supported")

    private fun List<String>.supportsAny(vararg names: String): Boolean? =
        takeIf { supported -> names.any(supported::contains) }?.let { true }

    /**
     * An empty list means the remote did not provide input modality metadata, and only then is the official catalog allowed to fill it in later.
     *
     * A missing field must not be collapsed directly into text: otherwise there is no way to distinguish "the remote explicitly declares text-only" from
     * "the standard /models did not return capability fields at all," and the official catalog would incorrectly override the former case.
     */
    private fun JsonObject.inputModalities(architecture: JsonObject?): List<String> {
        stringList("input_modalities", "inputModalities")?.let { return it }
        architecture?.stringList("input_modalities", "inputModalities")?.let { return it }
        val capabilityNames = listOf(
            "attachment",
            "vision",
            "supports_image_in",
            "supports_video_in",
        )
        if (capabilityNames.none(::containsKey)) return emptyList()
        return buildList {
            add(Model.TEXT_MODALITY)
            if (boolean("attachment", "vision", "supports_image_in") == true) {
                add(Model.IMAGE_MODALITY)
            }
            if (boolean("supports_video_in") == true) {
                add("video")
            }
        }
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun JsonElement.jsonArrayOrNull(): JsonArray? =
        runCatching { jsonArray }.getOrNull()

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
