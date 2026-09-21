package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.agent.model.AgentModelClient

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.model.ProviderSourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEngineTest {
    @Test fun chatRelaysResolveTheirActualSpeechModel() {
        val chat = OpenAiCompatibleProviderSetting(
            id = "fish", name = "Fish", baseUrl = "https://api.example.com/v1", apiKey = "k",
        )
        assertEquals(SpeechEngine.COSYVOICE, SpeechEngineResolver.resolve(chat, "CosyVoice2"))
        assertTrue(SpeechSynthesisModels.isReadAloudModel(Model("c", "CosyVoice2", "CosyVoice2"), chat))
    }

    @Test fun dedicatedSpeechProviderMapsCosyVoiceAndMoss() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "speech", name = "Speech synthesis", baseUrl = "https://api.example.com/v1", apiKey = "k",
            sourceType = ProviderSourceTypes.COMPATIBLE_SPEECH,
        )
        assertEquals(SpeechEngine.COSYVOICE, SpeechEngineResolver.resolve(provider, "CosyVoice2"))
        assertEquals(SpeechEngine.MOSS, SpeechEngineResolver.resolve(provider, "MOSS-TTSD"))
        val cosy = SpeechVoices.catalog(SpeechEngine.COSYVOICE, "CosyVoice2")
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:alex", cosy.first().id)
        assertTrue(cosy.any { it.id == "FunAudioLLM/CosyVoice2-0.5B:anna" })
        assertTrue(cosy.none { it.id == "alloy" })
        assertEquals("fnlp/MOSS-TTSD-v0.5:alex", SpeechVoices.catalog(SpeechEngine.MOSS, "MOSS-TTSD").first().id)
        val request = SpeechProtocols.request(
            SpeechEngine.COSYVOICE,
            AgentModelClient.ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "k", model = "CosyVoice2", systemPrompt = ""),
            "Hello there",
            "FunAudioLLM/CosyVoice2-0.5B:alex",
        )
        assertTrue(request.url.toString().endsWith("/audio/speech"))
    }
}
