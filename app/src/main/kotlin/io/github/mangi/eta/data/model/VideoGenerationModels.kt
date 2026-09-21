package io.github.mangi.eta.data.model

/**
 * Identify 'dedicated video generation' models: they use a dedicated video endpoint and do not enter the Agent tool loop.
 *
 * Video-understanding chat models (such as kimi / step with video in the input) do not contain these markers and still use normal chat.
 */
internal object VideoGenerationModels {
    private val ID_MARKERS = listOf(
        "veo-",
        "veo3",
        "sora-",
        "sora2",
        "seedance",
        "kling-video",
        "runway",
        "hailuo",
        "cogvideox",
        "hunyuan-video",
        "hunyuanvideo",
        "luma-video",
        "dream-machine",
        "grok-video",
        "grok-imagine-video",
        "imagine-video",
        "minimax-video",
        "ltx-video",
        "vidu-",
        "pika-",
        "gen-3-alpha",
        "gen3a",
        "-t2v",
        "_t2v",
        "-i2v",
        "_i2v",
        "t2v-",
        "i2v-",
    )

    fun matches(model: Model): Boolean = matches(model.modelId, model.outputModalities)

    fun matches(modelId: String, outputModalities: List<String> = emptyList()): Boolean {
        val id = modelId.lowercase()
        if (id.isBlank()) return false
        val outputs = outputModalities.map { it.lowercase() }
        if (outputs.any { it == Model.VIDEO_MODALITY } && outputs.none { it == Model.TEXT_MODALITY }) {
            return true
        }
        val base = id.substringAfterLast('/')
        if (
            base == "sora" ||
            base.startsWith("sora-") ||
            base.startsWith("sora2") ||
            base.startsWith("kling") ||
            base.startsWith("vidu")
        ) {
            return true
        }
        return ID_MARKERS.any { it in id }
    }
}
