package io.github.mangi.eta.data.model

/**
 * Detects "dedicated image-generation" models: they use the standalone images endpoint and do not enter the Agent tool loop.
 *
 * Vision chat models (vl / vision / gpt-4o, etc.) do not contain these markers and still use regular chat.
 */
internal object ImageGenerationModels {
    private val ID_MARKERS = listOf(
        "dall-e",
        "dall_e",
        "dalle",
        "gpt-image",
        "gpt_image",
        "flux",
        "stable-diffusion",
        "stable_diffusion",
        "sdxl",
        "sd3",
        "wanx",
        "hidream",
        "imagen",
        "kolors",
        "ideogram",
        "recraft",
        "seedream",
        "qwen-image",
        "qwen_image",
        "cogview",
        "hunyuan-image",
        "hunyuan_image",
        "hunyuanimage",
        "playground",
        "midjourney",
        "niji-",
        "auraflow",
        "aura-flow",
        "pixart",
        "kandinsky",
        "grok-imagine",
    )

    private val VIDEO_MARKERS = listOf("video", "t2v", "i2v", "veo-", "seedance")

    private val NON_IMAGE_SPECIALTY_MARKERS = listOf(
        "asr", "whisper", "paraformer", "sensevoice", "gummy",
        "tts", "speech", "voice", "cosyvoice", "sambert",
        "embedding", "rerank",
        "ocr", "music", "moderation",
    )

    fun matches(model: Model): Boolean = matches(model.modelId, model.outputModalities)

    fun matches(modelId: String, outputModalities: List<String> = emptyList()): Boolean {
        val id = modelId.lowercase()
        if (id.isBlank()) return false
        if (VIDEO_MARKERS.any { it in id }) return false
        if (VideoGenerationModels.matches(modelId, outputModalities)) return false
        if (NON_IMAGE_SPECIALTY_MARKERS.any { it in id }) return false
        val outputs = outputModalities.map { it.lowercase() }
        if (outputs.any { it == Model.IMAGE_MODALITY } && outputs.none { it == Model.TEXT_MODALITY }) {
            return true
        }
        if (ID_MARKERS.any { it in id }) return true
        return "image" in id && !isVisionChatId(id)
    }

    private fun isVisionChatId(id: String): Boolean =
        "vision" in id ||
            "-vl" in id ||
            "_vl" in id ||
            "vl-" in id ||
            "vl_" in id ||
            id.endsWith("vl")
}
