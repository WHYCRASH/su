package io.github.mangi.eta.data.model

/**
 * Treat chat as image-capable by default so each new model does not need an allowlist change.
 * Only exclude known text-only / image-generation models. Catalog image modalities are often mislabeled, so they are no longer trusted on their own.
 * The "vision support" toggle in model editing writes visionOverride, which takes precedence over this automatic judgment.
 */
internal object VisionChatModels {
    private val NEGATIVE = listOf(
        "deepseek-chat",
        "deepseek-reasoner",
        "deepseek-r1",
        "deepseek-v3",
        "deepseek-v2",
        "deepseek-coder",
        "deepseek-moe",
        "r1-distill",
        "qwq",
        "qwen3-coder",
        "qwen2.5-coder",
        "codestral",
        "starcoder",
        "whisper",
        "tts",
        "embedding",
        "rerank",
        "moderation",
        "dall-e",
        "gpt-image",
        "flux",
        "stable-diffusion",
    )

    fun matches(model: Model): Boolean = matches(model.modelId, model.inputModalities)

    @Suppress("UNUSED_PARAMETER")
    fun matches(modelId: String, inputModalities: List<String> = emptyList()): Boolean {
        val id = modelId.lowercase()
        if (id.isBlank()) return false
        if (ImageGenerationModels.matches(modelId)) return false
        if (NEGATIVE.any { it in id }) return false
        return true
    }
}
