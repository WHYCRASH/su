package io.github.mangi.eta.agent.delegation

import android.app.Application
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SubAgentPreferencesTest {
    @Test fun additionalSlotsPreserveOriginalRoleAndModelReferences() {
        Prefs.init(RuntimeEnvironment.getApplication())
        val original = SubAgentPreferences.selection(0)
        val review = SubAgentPreferences.selection(1)
        val saved = SubAgentPreferences.selection(3)
        val savedReasoning = SubAgentPreferences.reasoning(3)
        try {
            val extra = io.github.mangi.eta.agent.model.ModelFeatureSelection(true, "extra-provider", "extra-model")
            SubAgentPreferences.save(3, extra)
            assertEquals(extra, SubAgentPreferences.selection(3))
            assertEquals(original, SubAgentPreferences.selection(0))
            assertEquals(review, SubAgentPreferences.selection(1))
            assertEquals(listOf("implementation", "review", "implementation", "implementation"),
                (0 until SubAgentPreferences.SLOT_COUNT).map(SubAgentPreferences::role))
        } finally { SubAgentPreferences.save(3, saved); SubAgentPreferences.saveReasoning(3, savedReasoning) }
    }
    @Test fun draftSwitchPromotesOnceAndConversationsRemainIndependent() {
        Prefs.init(RuntimeEnvironment.getApplication())
        val id = java.util.UUID.randomUUID().toString()
        assertTrue(SubAgentPreferences.enabled(id))
        SubAgentPreferences.setEnabled(null, false)
        SubAgentPreferences.promote(id)
        assertFalse(SubAgentPreferences.enabled(id))
        assertTrue(SubAgentPreferences.enabled(null))
        assertTrue(SubAgentPreferences.enabled("another-$id"))
    }
    @Test fun reasoningIsSlotLocalAndLeavesSharedModelAndParentSnapshotUnchanged() {
        Prefs.init(RuntimeEnvironment.getApplication())
        val saved = (0 until SubAgentPreferences.SLOT_COUNT).map(SubAgentPreferences::reasoning)
        try {
            (0 until SubAgentPreferences.SLOT_COUNT).forEach { SubAgentPreferences.saveReasoning(it, null) }
            val shared = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
                model = "same-model", systemPrompt = "", reasoningEffort = ReasoningEffort.LOW, thinkingEnabled = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), canDisable = true))
            val parentSnapshot = shared.copy()
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.HIGH)
            SubAgentPreferences.saveReasoning(1, ReasoningEffort.OFF)
            assertEquals(ReasoningEffort.HIGH, SubAgentPreferences.applyReasoning(0, shared).reasoningEffort)
            assertFalse(SubAgentPreferences.applyReasoning(1, shared).thinkingEnabled)
            assertSame(shared, SubAgentPreferences.applyReasoning(2, shared))
            assertEquals(parentSnapshot, shared)
            assertEquals(ReasoningEffort.LOW, parentSnapshot.reasoningEffort)
            val runningChild = SubAgentPreferences.applyReasoning(0, shared)
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.OFF)
            assertEquals(ReasoningEffort.HIGH, runningChild.reasoningEffort)
            assertEquals(ReasoningEffort.OFF, SubAgentPreferences.applyReasoning(0, shared).reasoningEffort)
        } finally { saved.forEachIndexed(SubAgentPreferences::saveReasoning) }
    }

    @Test fun changingModelClearsOnlyThatSlotsOverride() {
        Prefs.init(RuntimeEnvironment.getApplication())
        val savedModel = SubAgentPreferences.selection(0)
        val saved = (0..1).map(SubAgentPreferences::reasoning)
        try {
            val selection = ModelFeatureSelection(true, "provider-A", "model-A")
            SubAgentPreferences.save(0, selection)
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.HIGH)
            SubAgentPreferences.saveReasoning(1, ReasoningEffort.LOW)
            SubAgentPreferences.save(0, selection)
            assertEquals(ReasoningEffort.HIGH, SubAgentPreferences.reasoning(0))
            SubAgentPreferences.save(0, selection.copy(modelId = "model-B"))
            assertNull(SubAgentPreferences.reasoning(0))
            assertEquals(ReasoningEffort.LOW, SubAgentPreferences.reasoning(1))
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.HIGH)
            SubAgentPreferences.save(0, selection.copy(providerId = "provider-B", modelId = "model-B"))
            assertNull(SubAgentPreferences.reasoning(0))
        } finally {
            SubAgentPreferences.save(0, savedModel)
            saved.forEachIndexed(SubAgentPreferences::saveReasoning)
        }
    }

    @Test fun unsupportedOverridesAreNormalizedWithoutChangingStoredChoice() {
        Prefs.init(RuntimeEnvironment.getApplication())
        val saved = SubAgentPreferences.reasoning(3)
        try {
            val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
                model = "mandatory", systemPrompt = "", reasoningEffort = ReasoningEffort.LOW, thinkingEnabled = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), mandatory = true))
            SubAgentPreferences.saveReasoning(3, ReasoningEffort.OFF)
            assertEquals(ReasoningEffort.LOW, SubAgentPreferences.applyReasoning(3, config).reasoningEffort)
            SubAgentPreferences.saveReasoning(3, ReasoningEffort.XHIGH)
            assertEquals(ReasoningEffort.HIGH, SubAgentPreferences.applyReasoning(3, config).reasoningEffort)
            assertEquals(ReasoningEffort.XHIGH, SubAgentPreferences.reasoning(3))
            assertEquals(ReasoningEffort.OFF, SubAgentPreferences.applyReasoning(3, config.copy(reasoningCapabilities = null)).reasoningEffort)
        } finally { SubAgentPreferences.saveReasoning(3, saved) }
    }

}
