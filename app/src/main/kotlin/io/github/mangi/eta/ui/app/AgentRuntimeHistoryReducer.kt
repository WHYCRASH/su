package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatHomeUiState

/** Shared idempotent history commit point for live results and outbox recovery. */
internal object AgentRuntimeHistoryReducer {
    data class Outcome(
        val state: AgentChatHomeUiState,
        val alreadyApplied: Boolean,
    )

    fun apply(
        state: AgentChatHomeUiState,
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
    ): Outcome {
        if (runId in state.appliedRuntimeRunIds) {
            return Outcome(state, alreadyApplied = true)
        }
        return Outcome(
            state = state.copy(
                history = state.history + additions,
                appliedRuntimeRunIds = (state.appliedRuntimeRunIds + runId)
                    .takeLast(MAX_APPLIED_RUN_IDS),
            ),
            alreadyApplied = false,
        )
    }

    fun wasApplied(state: AgentChatHomeUiState, runId: String): Boolean =
        runId in state.appliedRuntimeRunIds

    private const val MAX_APPLIED_RUN_IDS = 128
}
