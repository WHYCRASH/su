package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun SubAgentSettingsScreen(onBack: () -> Unit) {
    var selections by remember { mutableStateOf((0 until SubAgentPreferences.SLOT_COUNT).map(SubAgentPreferences::selection)) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    MiuixScaffoldPage(title = "Subagents", onBack = onBack) {
        item {
            Text("The current chat model acts as the lead agent, automatically delegating tasks suited for parallel work and reviewing subagent results. At most two subagents run at once. Implementers can only read and write their assigned worktree; reviewer/summarizer agents are read-only; subagents cannot delegate further. Long-press the model picker in chat to toggle collaboration for this session.",
                Modifier.padding(24.dp))
            Card(Modifier.padding(horizontal = 12.dp)) {
                SubAgentPreferences.displayOrder.forEach { index ->
                    val selected = selections[index]
                    val model = AgentModelPickerProjector.project(providers, selected.providerId, selected.modelId).selectedModel
                    ArrowPreference(title = SubAgentPreferences.label(index),
                        summary = model?.let { "${it.providerName} / ${it.displayName}" } ?: "Not configured",
                        onClick = { editing = index })
                    if (selected.modelId.isNotBlank()) {
                        ArrowPreference(title = "Clear ${SubAgentPreferences.label(index)}", onClick = {
                            val empty = ModelFeatureSelection(true, "", "")
                            SubAgentPreferences.save(index, empty)
                            selections = selections.toMutableList().also { it[index] = empty }
                        })
                    }
                }
            }
            Text("Only the model reference is saved, using credentials already present in the provider. Roles with no configured model cannot be delegated; different slots may share one model. The workspace lives under the project's .agent directory, requires the terminal enabled, and needs Python and Git installed in the selected Linux environment. Builds and tests run on the lead agent. Each task may run up to 3 minutes, compaction gets a separate cumulative 3-minute budget, and each lead-agent run may delegate at most 16 tasks.",
                Modifier.padding(24.dp))
        }
    }
    editing?.let { index ->
        val selected = selections[index]
        val all = AgentModelPickerProjector.project(providers, selected.providerId, selected.modelId)
        val models = all.copy(providerGroups = all.providerGroups.map { group ->
            group.copy(models = group.models.filter { !it.supportsImageGeneration && !it.supportsVideoGeneration })
        }.filter { it.models.isNotEmpty() })
        TtsModelPickerDialog(models, true, { editing = null }, { providerId, modelId ->
            val selection = ModelFeatureSelection(true, providerId, modelId)
            SubAgentPreferences.save(index, selection)
            selections = selections.toMutableList().also { it[index] = selection }
            editing = null
        }, "Select ${SubAgentPreferences.label(index)} model", onClearSelection = {
            val empty = ModelFeatureSelection(true, "", "")
            SubAgentPreferences.save(index, empty)
            selections = selections.toMutableList().also { it[index] = empty }
            editing = null
        })
    }
}
