package io.github.mangi.eta.ui

import io.github.mangi.eta.agent.voice.tts.SpeechVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechVoiceGroupingTest {
    @Test fun femaleMaleOtherAndPersonalSectionsSplitByVoiceId() {
        val voices = listOf(
            SpeechVoice("voice_female_shaonv", "Young Girl"),
            SpeechVoice("voice_male_jingying", "Elite Male"),
            SpeechVoice("alloy", "Alloy"),
            SpeechVoice("clone-1", "Studio clone", personal = true),
        )
        val sections = groupedSpeechVoices(voices)
        assertEquals(listOf("Young Girl"), sections.female.map { it.name })
        assertEquals(listOf("Elite Male"), sections.male.map { it.name })
        assertEquals(listOf("Alloy"), sections.other.map { it.name })
        assertEquals(listOf("Studio clone"), sections.personal.map { it.name })
    }

    @Test fun emptyCatalogYieldsEmptySections() {
        val sections = groupedSpeechVoices(emptyList())
        assertTrue(sections.female.isEmpty())
        assertTrue(sections.male.isEmpty())
        assertTrue(sections.other.isEmpty())
        assertTrue(sections.personal.isEmpty())
    }
}
