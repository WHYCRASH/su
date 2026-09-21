package io.github.mangi.eta.data.assistant

import io.github.mangi.eta.data.model.AssistantPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPromptTest {
    @Test
    fun emptyPromptUsesAssistantName() {
        val prompt = AssistantPrompt.build("Minis", "")
        assertTrue(prompt.startsWith("You are Minis"))
        assertFalse(prompt.contains("Persona setting"))
        assertFalse(prompt.contains("su"))
    }

    @Test
    fun blankNameFallsBackToDefault() {
        assertEquals(AssistantPrompt.EMPTY_PROMPT, AssistantPrompt.build("  ", ""))
        assertTrue(AssistantPrompt.build("", "").startsWith("You are su"))
    }

    @Test
    fun personalityBodyIsAppended() {
        val prompt = AssistantPrompt.build("Eta", "Get things done first, skip the pleasantries.")
        assertTrue(prompt.startsWith("You are Eta"))
        assertTrue(prompt.contains("Persona:"))
        assertTrue(prompt.contains("Get things done first, skip the pleasantries."))
        assertEquals(2, prompt.split("Persona:").size)
    }
}
