package io.github.mangi.eta.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.mangi.eta.agent.delegation.SubAgentContextStats

internal data class AgentContextTelemetry(
    val children: List<SubAgentContextStats> = emptyList(),
    val mainModelName: String = "",
    val compactingModelName: String = "",
)
internal val LocalAgentContextTelemetry = staticCompositionLocalOf { AgentContextTelemetry() }
internal fun SubAgentContextStats.contextLabel(): String {
    val roleLabel = when (role) {
        "implementation" -> "Implementation"
        "review" -> "Review"
        "summary" -> "Summary"
        else -> "Research"
    }
    return "$modelName ($roleLabel ${taskId.take(4)})"
}
