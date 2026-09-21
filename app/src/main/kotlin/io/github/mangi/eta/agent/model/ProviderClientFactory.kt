package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes

internal object ProviderClientFactory {

    fun getClient(config: AgentModelClient.ModelConfig): AgentProviderClient {
        io.github.mangi.eta.data.model.RemovedProviderPolicy.requireSupported(
            config.baseUrl, config.openAiEndpointMode,
        )
        require(!io.github.mangi.eta.data.model.SpeechSynthesisModels.matches(config.model)) {
            "A dedicated text-to-speech model can't be used for chat or summaries. Please configure one in Read Aloud settings"
        }
        val provider = when (config.providerType) {
            ProviderTypes.OPENAI_COMPATIBLE -> when (config.openAiEndpointMode) {
                OpenAiEndpointMode.RESPONSES -> OpenAiResponsesProvider
                else -> OpenAiChatCompletionsProvider
            }
            ProviderTypes.ANTHROPIC -> AnthropicMessagesProvider
            else -> error("Unsupported Provider protocol type: ${config.providerType}")
        }
        return UsageRecordingProvider(provider)
    }
}
