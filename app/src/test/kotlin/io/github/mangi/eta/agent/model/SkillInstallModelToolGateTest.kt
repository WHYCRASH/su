package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInstallModelToolGateTest {
    @Test
    fun `model always sees GitHub skill tools without prompt keyword gating`() {
        listOf(
            "Summarize this repository",
            "List the installable Skills",
            "Install the openai-docs Skill from GitHub for me",
            "\$skill-installer linear",
            "Translate this sentence: install this Skill",
        ).forEach { prompt ->
            val provider = CapturingProvider()
            complete(prompt, provider)
            assertTrue(prompt, "skills_list_curated" in provider.toolNames)
            assertTrue(prompt, "skills_inspect_github" in provider.toolNames)
            assertTrue(prompt, "skills_install_from_github" in provider.toolNames)
        }
    }

    private fun complete(prompt: String, provider: CapturingProvider) {
        AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
                browserTools = false,
            ),
            prompt = prompt,
            toolExecutor = AgentModelClient.ToolExecutor {
                error("No tools should be executed")
            },
            provider = provider,
        )
    }

    private class CapturingProvider : AgentProviderClient {
        override val id: String = "capturing"
        override val capabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = false,
            streamingToolCalls = false,
            imageInput = false,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )
        var toolNames: Set<String> = emptySet()

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            toolNames = request.tools.toolNames()
            return ProviderResponse(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "Done")
                    .put("finish_reason", "stop"),
            )
        }

        private fun JSONArray.toolNames(): Set<String> =
            (0 until length()).mapTo(mutableSetOf()) { index ->
                getJSONObject(index).getJSONObject("function").getString("name")
            }
    }
}
