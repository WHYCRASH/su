package io.github.mangi.eta.agent.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceEntryPolicyTest {
    @Test fun allFourCombinationsExposeOnlyEnabledModes() {
        for (bits in 0..3) {
            val config = VoiceInputConfig.Config(
                inputEnabled = bits and 1 != 0,
                conversationEnabled = bits and 2 != 0,
            )
            val expected = buildList {
                if (bits and 1 != 0) add(VoiceEntryMode.DICTATION)
                if (bits and 2 != 0) add(VoiceEntryMode.UNIVERSAL)
            }
            assertEquals(expected, VoiceEntryPolicy.modes(config))
            assertEquals(expected.singleOrNull(), VoiceEntryPolicy.directMode(config))
            assertEquals(expected.size > 1, VoiceEntryPolicy.canChoose(config))
            VoiceEntryMode.entries.forEach {
                assertEquals(it in expected, VoiceEntryPolicy.enabled(config, it))
            }
        }
    }

    @Test fun allCombinationsRespectLastChoiceWithoutEnablingDisabledModes() {
        for (bits in 0..3) {
            val config = VoiceInputConfig.Config(
                inputEnabled = bits and 1 != 0,
                conversationEnabled = bits and 2 != 0,
            )
            val available = VoiceEntryPolicy.modes(config)
            VoiceEntryMode.entries.forEach { last ->
                val expected = last.takeIf { it in available } ?: available.singleOrNull()
                assertEquals(expected, VoiceEntryPolicy.directMode(config, last.wireValue))
            }
            assertEquals(available.singleOrNull(), VoiceEntryPolicy.directMode(config, "unknown-mode"))
        }
    }

    @Test fun multipleEnabledModesUseTheRememberedModeOnNextClick() {
        val config = VoiceInputConfig.Config(inputEnabled = true, conversationEnabled = true)
        assertEquals(VoiceEntryMode.UNIVERSAL, VoiceEntryPolicy.directMode(config, "universal"))
        assertEquals(VoiceEntryMode.DICTATION, VoiceEntryPolicy.directMode(config, "dictation"))
        assertNull(VoiceEntryPolicy.directMode(config, ""))
        assertTrue(VoiceEntryPolicy.canChoose(config))
    }

    @Test fun historyOfADisabledModeOpensChooserUnlessOnlyOneModeRemains() {
        val two = VoiceInputConfig.Config(inputEnabled = true, conversationEnabled = true)
        assertNull(VoiceEntryPolicy.directMode(two, "universal-disabled"))
        assertNull(VoiceEntryPolicy.directMode(two, ""))

        val dictationOnly = VoiceInputConfig.Config(inputEnabled = true, conversationEnabled = false)
        assertEquals(VoiceEntryMode.DICTATION, VoiceEntryPolicy.directMode(dictationOnly, "universal"))
        assertFalse(VoiceEntryPolicy.canChoose(dictationOnly))

        val nothingEnabled = VoiceInputConfig.Config(inputEnabled = false, conversationEnabled = false)
        assertNull(VoiceEntryPolicy.directMode(nothingEnabled, "dictation"))
    }
}
