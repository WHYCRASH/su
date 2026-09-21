package io.github.mangi.eta.agent.voice.offline

import org.junit.Assert.*
import org.junit.Test

class SpeechInputTest {
    @Test fun disabledVoiceDoesNotShowAnIdleIndicator() {
        assertFalse(SpeechInputPolicy.visible(generating = false, enabled = false))
        assertTrue(SpeechInputPolicy.visible(generating = true, enabled = false))
    }
    @Test fun enabledVoiceShowsCircleWithoutGeneration() {
        assertTrue(SpeechInputPolicy.visible(generating = false, enabled = true))
        assertTrue(SpeechInputPolicy.visible(generating = true, enabled = true))
    }
    @Test fun enablingRequiresVerifiedCompletePack() {
        assertFalse(SpeechInputPolicy.canEnable(false, false, false))
        assertFalse(SpeechInputPolicy.canEnable(true, true, false))
        assertFalse(SpeechInputPolicy.canEnable(true, false, true))
        assertTrue(SpeechInputPolicy.canEnable(true, false, false))
    }
    @Test fun initialSilenceAndLongSessionHaveFiniteDeadlines() {
        assertFalse(SpeechInputPolicy.timedOut(7_999, false))
        assertTrue(SpeechInputPolicy.timedOut(8_000, false))
        assertFalse(SpeechInputPolicy.timedOut(8_000, true))
        assertTrue(SpeechInputPolicy.timedOut(60_000, true))
    }
    @Test fun partialTextReplacesOnlyCurrentDictation() {
        val draft = SpeechDraft("abcd", 4, 4)
        assertEquals("abcde", draft.accept("abcd", "e"))
        assertEquals("abcdef", draft.accept("abcde", "ef"))
        assertEquals(6, draft.cursor)
    }
    @Test fun decoderRevisionDoesNotDuplicatePartialWords() {
        val draft = SpeechDraft("", 0, 0)
        assertEquals("Hello", draft.accept("", "Hello"))
        assertEquals("Hello world", draft.accept("Hello", "Hello world"))
        assertEquals("Hello world again", draft.accept("Hello world", "Hello world again"))
    }
    @Test fun selectionAndSuffixArePreserved() {
        val draft = SpeechDraft("aSELECTz", 7, 1)
        assertEquals("aVz", draft.accept("aSELECTz", "V"))
        assertEquals(2, draft.cursor)
    }
    @Test fun typingOrSendingInvalidatesDictationInsteadOfOverwriting() {
        val draft = SpeechDraft("draft", 5, 5)
        assertNull(draft.accept("typed", "speech"))
        assertNull(draft.accept("", "speech"))
        assertEquals("draft", draft.expectedText)
    }
    @Test fun cancellingBeforeTextDoesNotChangeTheDraft() {
        val draft = SpeechDraft("source", 1, 1)
        assertEquals("source", draft.expectedText)
    }
    @Test fun manifestIsBoundedPinnedAndContainsOnlyExpectedFiles() {
        val entries = SpeechModelManifest.assets
        assertEquals(7, entries.size)
        assertEquals(7, entries.map { it.name }.toSet().size)
        assertTrue(entries.all { it.bytes > 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(entries.none { it.name.contains('/') || it.name.contains("..") })
        assertTrue(SpeechModelManifest.BASE_URL.contains(SpeechModelManifest.REVISION))
        assertTrue(SpeechModelManifest.totalBytes in 140_000_000..145_000_000)
    }
}
