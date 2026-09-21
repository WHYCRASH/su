package io.github.mangi.eta.agent.voice.tts

internal data class SpeechVoice(val id: String, val name: String, val personal: Boolean = false)

internal object SpeechVoices {
    fun shouldReplaceStoredVoice(
        cloud: Boolean,
        providerId: String,
        providerReady: Boolean,
        storedVoice: String,
        catalogIds: Collection<String>,
    ): Boolean {
        if (!cloud || catalogIds.isEmpty()) return false
        // Providers load asynchronously. A transient fallback catalog must not overwrite
        // a saved voice with the first entry before the real catalog arrives.
        if (providerId.isNotBlank() && !providerReady) return false
        if (storedVoice.isBlank()) return true
        return storedVoice !in catalogIds
    }

    fun catalog(engine: SpeechEngine, model: String = ""): List<SpeechVoice> = when (engine) {
        SpeechEngine.OPENAI -> openai
        SpeechEngine.MINIMAX -> minimax
        SpeechEngine.STEP -> step
        SpeechEngine.QWEN -> qwen(model)
        SpeechEngine.GROQ -> groq
        SpeechEngine.XAI -> xai
        SpeechEngine.GEMINI -> gemini
        SpeechEngine.ELEVENLABS -> elevenlabs
        SpeechEngine.FISH -> emptyList()
        SpeechEngine.COSYVOICE -> relayVoices(model, "FunAudioLLM/CosyVoice2-0.5B")
        SpeechEngine.MOSS -> relayVoices(model, "fnlp/MOSS-TTSD-v0.5")
    }

    private val openai = listOf(
        SpeechVoice("alloy", "Alloy"),
        SpeechVoice("echo", "Echo"),
        SpeechVoice("fable", "Fable"),
        SpeechVoice("onyx", "Onyx"),
        SpeechVoice("nova", "Nova"),
        SpeechVoice("shimmer", "Shimmer"),
    )
    private val minimax = listOf(
        SpeechVoice("female-shaonv", "Young Girl"),
        SpeechVoice("female-yujie", "Mature Woman"),
        SpeechVoice("female-chengshu", "Mature Female"),
        SpeechVoice("female-tianmei", "Sweet Female"),
        SpeechVoice("male-qn-qingse", "Youthful Male"),
        SpeechVoice("male-qn-jingying", "Elite Male"),
        SpeechVoice("male-qn-badao", "Bold Male"),
        SpeechVoice("male-qn-daxuesheng", "College Male"),
        SpeechVoice("audiobook_male_1", "Audiobook Male"),
        SpeechVoice("audiobook_female_1", "Audiobook Female"),
        SpeechVoice("cartoon_pig", "Cartoon"),
    )
    private val step = listOf(
        SpeechVoice("elegantgentle-female", "Gentle Female"),
        SpeechVoice("livelybreezy-female", "Lively Female"),
        SpeechVoice("jingdiannvsheng", "Classic Female"),
        SpeechVoice("wenroushunv", "Tender Female"),
        SpeechVoice("tianmeinvsheng", "Sweet Female"),
        SpeechVoice("qingchunshaonv", "Youthful Girl"),
        SpeechVoice("cixingnansheng", "Magnetic Male"),
        SpeechVoice("wenrounansheng", "Gentle Male"),
        SpeechVoice("yuanqinansheng", "Energetic Male"),
        SpeechVoice("zhengpaiqingnian", "Upright Youth"),
        SpeechVoice("ruyananshi", "Refined Gentleman"),
        SpeechVoice("boyinnansheng", "Announcer Male"),
    )
    private val groq = listOf(
        SpeechVoice("austin", "Austin"),
        SpeechVoice("natalie", "Natalie"),
        SpeechVoice("kailin", "Kailin"),
    )
    private val xai = listOf(
        SpeechVoice("eve", "Eve"),
        SpeechVoice("ara", "Ara"),
        SpeechVoice("rex", "Rex"),
        SpeechVoice("sal", "Sal"),
        SpeechVoice("leo", "Leo"),
    )
    private val gemini = listOf(
        SpeechVoice("Kore", "Kore"),
        SpeechVoice("Puck", "Puck"),
        SpeechVoice("Charon", "Charon"),
        SpeechVoice("Fenrir", "Fenrir"),
        SpeechVoice("Aoede", "Aoede"),
        SpeechVoice("Leda", "Leda"),
        SpeechVoice("Orus", "Orus"),
        SpeechVoice("Zephyr", "Zephyr"),
    )
    private val elevenlabs = listOf(
        SpeechVoice("JBFqnCBsd6RMkjVDRZzb", "George"),
    )

    // SiliconFlow's documented preset IDs include the upstream model name.
    // Keep full model IDs intact; the relay's short aliases need canonical prefixes.
    private fun relayVoices(model: String, canonicalModel: String): List<SpeechVoice> {
        val prefix = if ("/" in model) model else canonicalModel
        return listOf(
            "alex" to "Steady Male", "benjamin" to "Deep Male",
            "charles" to "Magnetic Male", "david" to "Cheerful Male",
            "anna" to "Steady Female", "bella" to "Passionate Female",
            "claire" to "Gentle Female", "diana" to "Cheerful Female",
        ).map { (id, name) -> SpeechVoice("$prefix:$id", name) }
    }

    private fun qwen(model: String): List<SpeechVoice> {
        val id = model.lowercase()
        return if ("plus" in id) {
            listOf(SpeechVoice("longanlingxin", "Lingxin"), SpeechVoice("longanlufeng", "Lufeng"))
        } else {
            listOf(
                SpeechVoice("longanhuan_v3.6", "Huan"),
                SpeechVoice("longanfengyue", "Fengyue"),
                SpeechVoice("longanyuanfei", "Yuanfei"),
                SpeechVoice("longanlingxi", "Lingxi"),
                SpeechVoice("longanxiaoxin", "Xiaoxin"),
                SpeechVoice("loongmary", "Mary"),
                SpeechVoice("loongjohn", "John"),
            )
        }
    }
}
