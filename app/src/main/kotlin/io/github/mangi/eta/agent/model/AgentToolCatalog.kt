package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

/** Declares the model-visible tools and their JSON schemas; contains no execution logic. */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
    ): JSONArray =
        capabilities.project(JSONArray().also { tools ->
            AgentContextAppToolCatalog.appendTo(tools)
            AgentSpeechToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) AgentBrowserToolCatalog.appendTo(tools)
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
            }
        })
}
