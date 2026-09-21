package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownRestoreStateTest {
    @Test fun userResumeCannotOpenGateBeforeRestoredLayout() {
        val state = StreamingMarkdownRestoreState()
        assertFalse(state.animationsAllowed(false))
        state.begin("already visible")
        assertFalse(state.animationsAllowed(false))
        assertFalse(state.animationsAllowed(true))
        assertTrue(state.completeLayout(state.generation, "already visible", "already visible"))
        assertFalse(state.animationsAllowed(true))
        assertTrue(state.animationsAllowed(false))
    }

    @Test fun repeatedPauseResumeKeepsProgressButCannotAnimateWhileBackgrounded() {
        val state = StreamingMarkdownRestoreState()
        state.begin("prefix")
        state.completeLayout(state.generation, "prefix", "prefix")
        repeat(4) {
            assertFalse(state.animationsAllowed(true))
            assertTrue(state.animationsAllowed(false))
        }
        state.pause()
        assertFalse(state.animationsAllowed(false))
        state.begin("prefix plus background output")
        assertFalse(state.animationsAllowed(false))
        assertFalse(state.completeLayout(state.generation, "prefix", "prefix plus background output"))
        assertTrue(state.completeLayout(state.generation, "prefix plus background output", "prefix plus background output"))
        assertTrue(state.animationsAllowed(false))
    }

    @Test
    fun restoredContentWaitsForMatchingLayoutRegardlessOfEarlierLayoutCount() {
        val state = StreamingMarkdownRestoreState()
        state.begin("finished background content")

        repeat(10) {
            assertFalse(state.completeLayout(state.generation, "finished", "finished background content"))
        }
        assertTrue(state.completeLayout(state.generation, "finished background content", "finished background content"))
        assertFalse(state.completeLayout(state.generation, "finished background content plus delta", "finished background content plus delta"))
    }

    @Test
    fun delayedOldLayoutCannotResumeAfterAnotherBackgroundCycle() {
        val state = StreamingMarkdownRestoreState()
        state.begin("existing content")
        val oldGeneration = state.generation
        state.pause()
        assertFalse(state.completeLayout(oldGeneration, "existing content", "existing content"))
        state.begin("existing content plus background delta")

        assertFalse(state.completeLayout(oldGeneration, "existing content plus background delta", "existing content plus background delta"))
        assertFalse(state.completeLayout(state.generation, "existing content", "existing content plus background delta"))
        assertTrue(state.completeLayout(state.generation, "existing content plus background delta", "existing content plus background delta"))
    }

    @Test
    fun newNetworkTextDoesNotKeepMovingTheRestoreBaseline() {
        val state = StreamingMarkdownRestoreState()
        state.begin("history")

        assertTrue(state.completeLayout(state.generation, "history", "history plus new content"))
    }

    @Test
    fun authoritativeReplacementCanCompleteRestoreButStaleLayoutCannot() {
        val state = StreamingMarkdownRestoreState()
        state.begin("old content")

        assertFalse(state.completeLayout(state.generation, "old content", "corrected content"))
        assertTrue(state.completeLayout(state.generation, "corrected content", "corrected content"))
    }

    @Test
    fun onlyCurrentTerminalSnapshotMayFinishReveal() {
        assertFalse(isStreamingMarkdownTargetComplete("body plus delta", false, "body", true))
        assertFalse(isStreamingMarkdownTargetComplete("body", true, "body", true))
        assertFalse(isStreamingMarkdownTargetComplete("body", false, "body", false))
        assertFalse(isStreamingMarkdownTargetComplete("body", false, null, false))
        assertTrue(isStreamingMarkdownTargetComplete("body", false, "body", true))
    }
}
