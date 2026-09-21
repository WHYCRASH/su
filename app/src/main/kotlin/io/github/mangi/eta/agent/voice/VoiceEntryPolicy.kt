package io.github.mangi.eta.agent.voice

internal object VoiceEntryPolicy {
    fun modes(config: VoiceInputConfig.Config): List<VoiceEntryMode> = buildList {
        if (config.inputEnabled) add(VoiceEntryMode.DICTATION)
        if (config.conversationEnabled) add(VoiceEntryMode.UNIVERSAL)
    }
    fun enabled(config: VoiceInputConfig.Config, mode: VoiceEntryMode): Boolean = mode in modes(config)
    fun directMode(config: VoiceInputConfig.Config, lastSelected: String = ""): VoiceEntryMode? {
        val available = modes(config)
        // Unknown/disabled history must not silently enable a mode or guess dictation.
        return available.firstOrNull { it.wireValue == lastSelected } ?: available.singleOrNull()
    }
    fun canChoose(config: VoiceInputConfig.Config): Boolean = modes(config).size > 1
}
