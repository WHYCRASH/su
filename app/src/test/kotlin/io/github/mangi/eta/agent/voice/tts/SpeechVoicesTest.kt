package io.github.mangi.eta.agent.voice.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechVoicesTest {
    private val catalogIds = SpeechVoices.catalog(SpeechEngine.OPENAI).map { it.id }

    @Test fun keepsSavedVoiceWhileProviderCatalogIsStillLoading() {
        assertFalse(
            SpeechVoices.shouldReplaceStoredVoice(
                cloud = true,
                providerId = "provider-1",
                providerReady = false,
                storedVoice = "alloy",
                catalogIds = catalogIds,
            ),
        )
    }

    @Test fun keepsSavedVoiceThatBelongsToTheLoadedCatalog() {
        assertFalse(
            SpeechVoices.shouldReplaceStoredVoice(
                cloud = true,
                providerId = "provider-1",
                providerReady = true,
                storedVoice = "alloy",
                catalogIds = catalogIds,
            ),
        )
    }

    @Test fun fillsBlankVoiceFromCatalog() {
        assertTrue(
            SpeechVoices.shouldReplaceStoredVoice(
                cloud = true,
                providerId = "provider-1",
                providerReady = true,
                storedVoice = "",
                catalogIds = catalogIds,
            ),
        )
    }

    @Test fun replacesVoiceThatDoesNotBelongToTheLoadedCatalog() {
        assertTrue(
            SpeechVoices.shouldReplaceStoredVoice(
                cloud = true,
                providerId = "provider-1",
                providerReady = true,
                storedVoice = "retired-voice",
                catalogIds = catalogIds,
            ),
        )
    }

    @Test fun systemModeAndEmptyCatalogNeverTouchTheStoredVoice() {
        assertFalse(
            SpeechVoices.shouldReplaceStoredVoice(
                cloud = false,
                providerId = "provider-1",
                providerReady = true,
                storedVoice = "",
                catalogIds = catalogIds,
            ),
        )
        assertFalse(
            SpeechVoices.shouldReplaceStoredVoice(
                cloud = true,
                providerId = "provider-1",
                providerReady = true,
                storedVoice = "retired-voice",
                catalogIds = emptyList(),
            ),
        )
    }
}
