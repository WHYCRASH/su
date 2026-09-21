package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort

/** Slot-local model references and reasoning overrides; never mutate shared model settings. */
internal object SubAgentPreferences {
    const val SLOT_COUNT = 4
    val displayOrder = listOf(0, 2, 3, 1)
    fun role(slot: Int): String = if (slot == 1) "review" else "implementation"
    fun label(slot: Int): String = when (slot) {
        0 -> "Execution Agent 1"
        1 -> "Review/Summary Agent"
        2 -> "Execution Agent 2"
        3 -> "Execution Agent 3"
        else -> error("Invalid slot")
    }
    fun selection(slot: Int) = ModelFeatureSelection(true,
        Prefs.getString("agent_child_${slot}_provider"), Prefs.getString("agent_child_${slot}_model"))

    fun save(slot: Int, selection: ModelFeatureSelection) {
        require(slot in 0 until SLOT_COUNT)
        val previous = selection(slot)
        if (selection.providerId.isBlank() || selection.modelId.isBlank() ||
            previous.providerId != selection.providerId || previous.modelId != selection.modelId) {
            saveReasoning(slot, null)
        }
        Prefs.putString("agent_child_${slot}_provider", selection.providerId)
        Prefs.putString("agent_child_${slot}_model", selection.modelId)
    }

    fun reasoning(slot: Int): ReasoningEffort? {
        require(slot in 0 until SLOT_COUNT)
        return ReasoningEffort.fromWireValue(Prefs.getString("agent_child_${slot}_reasoning"))
    }

    fun saveReasoning(slot: Int, effort: ReasoningEffort?) {
        require(slot in 0 until SLOT_COUNT)
        Prefs.putString("agent_child_${slot}_reasoning", effort?.wireValue.orEmpty())
    }

    fun applyReasoning(slot: Int, config: AgentModelClient.ModelConfig): AgentModelClient.ModelConfig {
        val requested = reasoning(slot) ?: return config
        val effort = config.reasoningCapabilities?.normalize(requested) ?: ReasoningEffort.OFF
        return config.copy(reasoningEffort = effort, thinkingEnabled = effort.enablesReasoning)
    }

    private fun key(conversation: String?) = "agent_collaboration_${conversation ?: "draft"}"
    fun enabled(conversation: String?) = Prefs.getString(key(conversation), "true") != "false"
    fun setEnabled(conversation: String?, enabled: Boolean) = Prefs.putString(key(conversation), enabled.toString())
    fun promote(conversation: String) {
        setEnabled(conversation, enabled(null))
        setEnabled(null, true)
    }
}
