package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.decodeUserMessageImages

/** Idempotently fold Runtime outbox results back into the App session. */
internal object AgentPendingResultRecovery {
    data class Outcome(
        val state: AgentChatHomeUiState,
        val alreadyApplied: Boolean,
    )

    fun apply(
        state: AgentChatHomeUiState,
        runId: String,
        result: AgentRuntimeWire.RunResult,
        promptSupplement: AgentUiHandoffPayload.Supplement? = null,
        supplements: List<AgentUiHandoffPayload.Supplement>,
        generatedAtMillis: Long? = null,
    ): Outcome {
        val content = result.content.takeIf { result.ok && it.isNotBlank() }
        val history = AgentRuntimeHistoryReducer.apply(
            state = state,
            runId = runId,
            additions = listOfNotNull(
                promptSupplement?.let { supplement ->
                    AgentModelClient.buildUserHistoryMessage(
                        text = supplement.text,
                        images = emptyList(),
                    )
                }
            ) + result.transcript,
        )
        if (history.alreadyApplied) return Outcome(state, alreadyApplied = true)

        val messagesWithResult = state.messages
            .filterNot { it is SystemNoticeMessageUi && it.id == interruptedNoticeId(runId) }
            .toMutableList()
            .also { messages ->
            val assistantIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages, includeNotices = true)
            val resultId = AgentRunMessageProjector.resultFallbackId(runId, messages)
            val targetRound = (messages.getOrNull(assistantIndex) as? AgentMessageUi)
                ?.id
                ?.assistantRound(runId)
            val sameRoundBlocks = targetRound?.let { round ->
                messages.count { message ->
                    message is AgentMessageUi && message.id.assistantRound(runId) == round
                }
            } ?: 0
            val completedMessage: AgentChatMessageUi = when {
                content != null -> AgentMessageUi(
                    id = resultId,
                    content = if (sameRoundBlocks > 1) {
                        (messages[assistantIndex] as AgentMessageUi).content.ifBlank { content }
                    } else {
                        content
                    },
                    isStreaming = false,
                    renderMarkdown = true,
                    generatedAtMillis = (messages.getOrNull(assistantIndex) as? AgentMessageUi)?.generatedAtMillis
                        ?: generatedAtMillis?.takeIf { it > 0L },
                )
                result.ok -> SystemNoticeMessageUi(
                    id = resultId,
                    code = SystemNoticeCode.EmptyResult,
                )
                else -> SystemNoticeMessageUi(
                    id = resultId,
                    code = if (result.error == "Stopped") SystemNoticeCode.Stopped else SystemNoticeCode.RuntimeFailed,
                    detail = result.error,
                )
            }
            val partial = messages.getOrNull(assistantIndex) as? AgentMessageUi
            if (!result.ok && partial != null && partial.content.isNotBlank()) {
                messages[assistantIndex] = partial.copy(isStreaming = false)
                messages += completedMessage.copyWithId(interruptedNoticeId(runId))
            } else if (assistantIndex >= 0) {
                messages[assistantIndex] = completedMessage.copyWithId(messages[assistantIndex].id)
            } else {
                messages += completedMessage
            }
        }
        return Outcome(
            state = state.copy(
                messages = mergeSupplements(
                    runId = runId,
                    supplements = listOfNotNull(promptSupplement) + supplements,
                    messages = messagesWithResult,
                    beforeLatestAssistant = true,
                ),
                history = history.state.history,
                appliedRuntimeRunIds = history.state.appliedRuntimeRunIds,
                isStreaming = false,
                isPaused = false,
            ),
            alreadyApplied = false,
        )
    }

    private fun AgentChatMessageUi.copyWithId(id: String): AgentChatMessageUi = when (this) {
        is AgentMessageUi -> copy(id = id)
        is SystemNoticeMessageUi -> copy(id = id)
        else -> this
    }

    fun mergeSupplements(
        runId: String,
        supplements: List<AgentUiHandoffPayload.Supplement>,
        messages: List<AgentChatMessageUi>,
        beforeLatestAssistant: Boolean = false,
    ): List<AgentChatMessageUi> {
        var updated = messages
        supplements.sortedBy { it.index }.forEach { supplement ->
            val id = supplementMessageId(runId, supplement.index)
            val media = decodeUserMessageImages(supplement.imagesJson)
            val existing = updated.indexOfFirst { it.id == id }
            if (existing >= 0) {
                val previous = updated[existing] as? UserMessageUi
                if (previous != null && previous.images.isEmpty() && media.previews.isNotEmpty()) {
                    updated = updated.toMutableList().also { it[existing] = previous.copy(
                        images = media.previews, imageSources = media.sources,
                        imageIsVideo = media.videoFlags, imageDurationsMs = media.durationsMs,
                    ) }
                }
                return@forEach
            }
            val userMessage = UserMessageUi(id = id, content = supplement.text,
                images = media.previews, imageSources = media.sources,
                imageIsVideo = media.videoFlags, imageDurationsMs = media.durationsMs)
            // Real-time appends must go to the end of the current list: steering is only injected after this turn ends,
            // The user message should appear below the answer currently being generated. When inserted before the streaming assistant,
            // Follow-bottom scrolling will leave the supplement hidden below the previous user message; it won't be visible until generation is complete.
            // Only the restore path needs to insert it before the final assistant message, aligning with the completed transcript.
            if (!beforeLatestAssistant) {
                updated = updated + userMessage
                return@forEach
            }
            val assistantIndex = updated.indexOfLast {
                it is AgentMessageUi && it.isAssistantForRun(runId)
            }
            updated = if (assistantIndex >= 0) {
                updated.toMutableList().also { it.add(assistantIndex, userMessage) }
            } else {
                updated + userMessage
            }
        }
        return updated
    }

    private fun AgentChatMessageUi.isAssistantForRun(runId: String): Boolean =
        this is AgentMessageUi &&
            (id == "assistant-$runId" || id.startsWith(assistantMessagePrefix(runId)))

    private fun assistantMessagePrefix(runId: String): String = "assistant-$runId-"

    private fun String.assistantRound(runId: String): Int? =
        removePrefix(assistantMessagePrefix(runId))
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()

    internal fun supplementMessageId(runId: String, index: Int): String =
        "user-$runId-supplement-$index"

    private fun interruptedNoticeId(runId: String): String = "interrupted-$runId"

}
