package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController

/** Retries wrap only the model request; don't commit history or run local tools until the complete response is returned. */
internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var hostedToolStarted = false
            var callbackFailed = false
            var sawCompleted = false
            var sawVisibleText = false
            try {
                val response = provider.complete(request, controller) { event ->
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    if (event is ProviderEvent.Completed) sawCompleted = true
                    if (
                        event is ProviderEvent.BlockDelta &&
                        event.kind == AssistantBlockKind.TEXT &&
                        event.delta.isNotBlank()
                    ) {
                        sawVisibleText = true
                    }
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                // Interrupted by steering/pause before any visible content is emitted: treat it as an empty assistant turn, and Loop continues the same run.
                // When visible content already exists, the Provider must return partial content; don't overwrite it with an empty message here.
                if (
                    (controller.hasPendingSteering || controller.hasPausedInterrupt) &&
                    !sawVisibleText &&
                    !hostedToolStarted &&
                    !sawCompleted
                ) {
                    return Result(
                        round,
                        ProviderResponse(
                            org.json.JSONObject()
                                .put("role", "assistant")
                                .put("content", "")
                                .put("finish_reason", "stop"),
                        ),
                    )
                }
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                val reasonDetail = AgentHttpFailureDiagnostics.safe(classified.message.orEmpty(), listOf(request.config.apiKey), 600)
                // Log the first failure, including terminal/non-retryable responses, before scheduling retries.
                if (classified.diagnostic.isNotBlank()) runCatching {
                    io.github.mangi.eta.core.AndroidAgentLogger.warn(
                        "Model HTTP failure: provider=${AgentHttpFailureDiagnostics.safe(provider.id, limit = 80)}, " +
                            "model=${AgentHttpFailureDiagnostics.safe(request.config.model, limit = 120)}, " +
                            "round=$round, attempt=${retries + 1}, " +
                            AgentHttpFailureDiagnostics.safe(classified.diagnostic, listOf(request.config.apiKey), 4000),
                    )
                }
                if (!classified.retryable || hostedToolStarted || sawCompleted || sawVisibleText) {
                    throw classified
                }
                if (retries == MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} has been retried $MAX_RETRIES times but still hasn't recovered; previously completed tool results have been retained.",
                        classified,
                        diagnostic = classified.diagnostic,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, MAX_RETRIES, delayMs.toInt(), classified.code, reasonDetail))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // Display retained failed attempts; the model context and final reasoning summary only accept successful attempts.
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L
    }
}
