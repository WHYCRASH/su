package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.LocalToolRequirement
import io.github.mangi.eta.agent.tool.RootRequirement

/** The browser's explainer cards show different actions of the same tool; the requirement still comes from the tool that actually executes. */
internal fun toolCardRequirement(id: String): LocalToolRequirement {
    val toolName = actualToolName(id)
    return requireNotNull(AgentToolRequirements.find(toolName)) { "Unknown tool card: $id" }
}

internal fun visibleOnCurrentDevice(id: String, rootGranted: Boolean): Boolean {
    val requirement = toolCardRequirement(id)
    return rootGranted || requirement.rootRequirement != RootRequirement.REQUIRED
}

internal fun actualToolName(id: String): String = when (id) {
    "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
    else -> id
}

internal fun projectToolGroups(groups: List<ToolGroupUi>, showAll: Boolean, rootGranted: Boolean): List<ToolGroupUi> =
    groups.map { group ->
        group.copy(tools = group.tools.filter { showAll || visibleOnCurrentDevice(it.id, rootGranted) })
    }.filter { it.tools.isNotEmpty() }

/** An unavailable non-root capability has no card action; viewing the enhancement notes never requests Root itself. */
internal fun toolCardAction(id: String, capabilities: AgentToolCapabilities): AgentToolsAction? {
    val requirement = toolCardRequirement(id)
    return when (capabilities.unavailableCode(actualToolName(id))) {
        "ROOT_REQUIRED" -> AgentToolsAction.OpenEnhancements
        null -> when {
            id.startsWith("browser_") -> AgentToolsAction.OpenBrowser
            !capabilities.rootAvailable && requirement.rootRequirement == RootRequirement.PARTIAL ->
                AgentToolsAction.OpenEnhancements
            else -> null
        }
        else -> null
    }
}
