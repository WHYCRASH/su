package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure orchestration loop for a single Agent run.
 *
 * One assistant response plus its complete tool batch forms one request round,
 * which is not the same as a compressed user-logical turn.
 * Pause, append, and stop keep the same turnId; request counts and UI text-block
 * ids do not delimit user turns.
 * Mid-stream steering interrupts the current model request, keeps already-written
 * content, then injects follow-up instructions for the next request;
 * a tool batch still runs to completion without cancelling in-flight tools.
 * The loop sets no local round cap; it ends naturally via the model, cancellation, or an error.
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val compactPolicy: CompactPolicy = CompactPolicy.Disabled,
    private val systemCount: Int = 0,
    private val compactionArchive: AgentCompactionArchive? = null,
    private val turnId: String = java.util.UUID.randomUUID().toString(),
    private val onHistoryCompacted: () -> Unit = {},
    private val compactHistory: ((
        List<AgentModelClient.ConversationMessage>,
        CompactPolicy,
    ) -> List<AgentModelClient.ConversationMessage>)? = null,
) {
    data class CompactPolicy(
        val enabled: Boolean,
        val contextWindow: Int,
        val keepRecentMessages: Int,
        val compressModelConfig: AgentModelClient.ModelConfig?,
        val keepStartOverride: Int? = null,
    ) {
        companion object {
            val Disabled = CompactPolicy(
                enabled = false,
                contextWindow = 128_000,
                keepRecentMessages = AgentContextCompactor.DEFAULT_KEEP_RECENT,
                compressModelConfig = null,
            )
        }
    }
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private val auxiliaryVision = AuxiliaryVision.create(config, runController, sessionId)

    private var toolCallValidator = AgentToolCallValidator(tools)
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private var pendingToolImageMessage: JSONObject? = null
    private val incompleteText = sortedMapOf<Int, String>()
    private var responseStored = false

    private fun rememberIncompleteText(event: ProviderEvent) {
        when (event) {
            is ProviderEvent.BlockDelta -> if (event.kind == AssistantBlockKind.TEXT) {
                incompleteText[event.index] = incompleteText[event.index].orEmpty() + event.delta
            }
            is ProviderEvent.BlockEnd -> if (event.kind == AssistantBlockKind.TEXT && event.replaceContent) {
                incompleteText[event.index] = event.content
            }
            else -> Unit
        }
    }

    fun preserveIncompleteResponse() {
        if (responseStored) return
        val text = incompleteText.values.joinToString("").trim()
        if (text.isNotBlank()) {
            messages.put(JSONObject().put("role", "assistant").put("content", text)
                .put(AgentTurnIdentity.JSON_KEY, turnId))
            responseStored = true
        }
    }
    private var lastUsage: AgentTokenUsage? = null
    private var lastUsageMessageCount: Int = 0
    private var suppressThinkingForNextRequest = false
    private val continuationBlocks = AgentContinuationBlocks()
    private val continuationReasoning = AgentContinuationReasoning()
    private val interruptedTextPrefix = StringBuilder()
    private var continuingInterruptedRequest = false
    private var supplementStartsNewBlock = false
    private var compactionFailure = ""
    private var currentRoundTools = tools
    private var manualBudgetAttempt = false
    private var budgetKeepRecent = compactPolicy.keepRecentMessages
    private var budgetCompressModelConfig = compactPolicy.compressModelConfig
    private var overflowPending = false
    private var overflowRecoveryAttempts = 0
    private var lastFailedCompaction: Pair<String, Int>? = null
    private var skipIneffectiveAutoCompact = false

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        // Only annotate messages created by this run. The current user entry is initially last.
        messages.optJSONObject(messages.length() - 1)?.put(AgentTurnIdentity.JSON_KEY, turnId)
        var round = 1

        roundLoop@ while (true) {
            runController.throwIfCancelled()
            appendPendingSteeringMessage()
            currentRoundTools = toolsForRound?.invoke() ?: tools
            try {
                auxiliaryVision.prepare(messages)
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (runController.hasPausedInterrupt || runController.hasPendingSteering) {
                    runController.consumePausedInterrupt()
                    continue
                }
                throw failure
            }
            maybeCompactBeforeRound(round)
            var reductions = 0
            while (requestOverBudget() || overflowPending) {
                if (reductions++ < 2 && overflowRecoveryAttempts <= 1 && tryBudgetCompaction(round)) {
                    overflowPending = false
                    continue
                }
                runController.pause()
                onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length(),
                    blocked = true, reason = compactionFailure.ifBlank {
                        "Out of context space; protected history was not deleted. You can stay paused, allow compacting earlier steps for this run only, or stop and pick a larger-window model."
                    }))
                runController.throwIfCancelled()
                reductions = 0
                appendPendingSteeringMessage()
                currentRoundTools = toolsForRound?.invoke() ?: tools
                try {
                    auxiliaryVision.prepare(messages)
                } catch (failure: Exception) {
                    runController.throwIfCancelled()
                    if (runController.hasPausedInterrupt || runController.hasPendingSteering) {
                        runController.consumePausedInterrupt()
                        continue@roundLoop
                    }
                    throw failure
                }
                maybeCompactBeforeRound(round)
                if (manualBudgetAttempt) overflowRecoveryAttempts = 0
            }
            emitProjectedPrompt(round)

            manualBudgetAttempt = false
            val roundTools = currentRoundTools
            toolCallValidator = AgentToolCallValidator(roundTools)
            incompleteText.clear()
            responseStored = false
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val continuationText = AgentContinuationTextEvents(
                if (continuingInterruptedRequest) interruptedTextPrefix.toString() else "")
            continuationReasoning.beginRequest(continuingInterruptedRequest)
            continuationBlocks.beginRequest(continuingInterruptedRequest && !supplementStartsNewBlock)
            supplementStartsNewBlock = false
            continuingInterruptedRequest = false
            val completedRound = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(requestConfigForRound(),
                        AgentRequestMediaPolicy.filter(messages, config.supportsVision, config.supportsVideo),
                        roundTools, sessionId),
                    provider = provider,
                    controller = runController,
                    onEvent = onEvent,
                    onProviderEvent = { attemptRound, providerEvent ->
                        if (providerEvent is ProviderEvent.Usage) {
                            lastUsage = providerEvent.usage
                            lastUsageMessageCount = messages.length()
                        }
                        continuationReasoning.visibleEvent(providerEvent)?.let { visibleEvent ->
                            if (visibleEvent is ProviderEvent.BlockDelta &&
                                visibleEvent.kind == AssistantBlockKind.THINKING
                            ) {
                                accumulatedReasoning.append(visibleEvent.delta)
                            }
                            continuationText.map(visibleEvent).forEach { textEvent ->
                                rememberIncompleteText(textEvent)
                                continuationBlocks.map(attemptRound, textEvent).toAgentEvent(attemptRound)?.let(onEvent)
                            }
                        }
                    },
                    discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                )
            } catch (failure: AgentModelFailure) {
                if (failure.code != "CONTEXT_WINDOW_EXCEEDED") throw failure
                overflowPending = true
                overflowRecoveryAttempts++
                compactionFailure = "The provider reported context overflow. Retry on a limited basis only after a successful reduction; otherwise stay paused without deleting protected history."
                continue
            }
            // Failed/overflowed requests keep their image observation until a successful request.
            discardPendingToolImageMessage()
            overflowRecoveryAttempts = 0
            // Transport retries use a distinct display round; its fallback must not
            // duplicate text already retained in the previous display block.
            if (completedRound.round != round) interruptedTextPrefix.setLength(0)
            round = completedRound.round
            val providerResponse = completedRound.response

            // Keep a fully returned response before observing a concurrent user stop.
            continuationText.finish().forEach { textEvent ->
                continuationBlocks.map(round, textEvent).toAgentEvent(round)?.let(onEvent)
            }
            val assistantMessage = providerResponse.assistantMessage
            val originalContent = assistantMessage.opt("content")
            if (originalContent is String && originalContent != "null") {
                assistantMessage.put("content", continuationText.normalize(originalContent))
            }
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            toolCalls.filter { AgentSensitiveToolPolicy.isSensitive(it.name) }
                .forEach { sensitiveToolCallIds += it.id }
            val assistantReasoning = continuationReasoning.visibleCompletedReasoning(
                assistantMessage.optString("reasoning_content"))
            val content = assistantMessage.optString("content").trim()
            val hasAssistantPayload = (content.isNotBlank() && content != "null") ||
                assistantReasoning.isNotBlank() ||
                toolCalls.isNotEmpty()
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            val pausedInterrupt = runController.consumePausedInterrupt()
            val completeToolCallsOnResume = pausedInterrupt &&
                toolCalls.isNotEmpty() &&
                providerResponse.stopReason == AssistantStopReason.TOOL_USE
            if (providerResponse.stopReason == AssistantStopReason.INTERRUPTED ||
                (pausedInterrupt && !completeToolCallsOnResume)) {
                continuingInterruptedRequest = true
                // Resume keeps the user's reasoning configuration. Only the first
                // reasoning block's UI projection is hidden, not the model's thinking.
                // Keep the partial text in the current round history so the model can continue writing on resume.
                // Do not execute incomplete tool calls; fully-formed TOOL_USE calls go through the normal batch after resume.
                if (hasAssistantPayload) {
                    assistantMessage.optString("content").takeIf { it != "null" }?.let(interruptedTextPrefix::append)
                    messages.put(
                        AgentConversationCodec.assistantHistoryMessage(
                            source = assistantMessage,
                            toolCalls = emptyList(),
                        ).put(AgentTurnIdentity.JSON_KEY, turnId),
                    )
                    responseStored = true
                    if (!runController.isCancelled) appendCompactContinueIfNeeded(suppressOptionalThinking = false)
                }
                runController.throwIfCancelled()
                appendPendingSteeringMessage()
                continue
            }
            if (hasAssistantPayload) {
                messages.put(
                    AgentConversationCodec.assistantHistoryMessage(
                        source = assistantMessage,
                        toolCalls = toolCalls,
                    ).put(AgentTurnIdentity.JSON_KEY, turnId)
                )
                responseStored = true
                onEvent(
                    AgentEvent.AssistantReceived(
                        round = round,
                        contentChars = assistantMessage.optString("content").length,
                        reasoningContent = assistantReasoning,
                        toolNames = toolCalls.map { it.name },
                    )
                )
            } else if (runController.hasPendingSteering) {
                appendPendingSteeringMessage()
                interruptedTextPrefix.setLength(0)
                round += 1
                continue
            }

            runController.throwIfCancelled()
            if (toolCalls.isNotEmpty()) {
                val finishedContent = assistantMessage.optString("content").trim()
                val finishedNaturally = providerResponse.stopReason != AssistantStopReason.TOOL_USE &&
                    providerResponse.stopReason != AssistantStopReason.OUTPUT_LIMIT &&
                    finishedContent.isNotBlank() &&
                    finishedContent != "null"
                if (finishedNaturally) {
                    onEvent(AgentEvent.RunFinished(round = round, contentChars = finishedContent.length, generatedAtMillis = System.currentTimeMillis()))
                    return Result(
                        content = (interruptedTextPrefix.toString() + assistantMessage.optString("content")).trim(),
                        reasoningContent = reasoningSnapshot(),
                        sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
                    )
                }
                val outcomes = mutableListOf<ToolOutcome>()
                try {
                    when (providerResponse.stopReason) {
                        AssistantStopReason.TOOL_USE ->
                            toolCalls.forEach { call -> outcomes += executeTool(round, call) }
                        AssistantStopReason.OUTPUT_LIMIT ->
                            toolCalls.forEach { call ->
                                outcomes += rejectedToolOutcome(
                                    round = round,
                                    toolCall = call,
                                    code = "TRUNCATED_TOOL_CALL",
                                    message = "The model hit the output length limit and the tool arguments may be incomplete; this call was not executed, please resubmit complete arguments.",
                                )
                            }
                        else ->
                            toolCalls.forEach { call ->
                                outcomes += rejectedToolOutcome(
                                    round = round,
                                    toolCall = call,
                                    code = "UNEXPECTED_TOOL_CALL",
                                    message = "The model returned tool calls under the ${providerResponse.stopReason.name} stop state; " +
                                        "this batch was not executed, please replan.",
                                )
                            }
                    }
                } finally {
                    appendToolOutcomes(round, outcomes)
                }
                emitProjectedPrompt(round)
                interruptedTextPrefix.setLength(0)
                round += 1
                continue
            }

            // Natural completion is not an interrupted reply. Drain queued maintenance
            // at this safe boundary; never insert a continuation or make an extra call.
            maybeCompactBeforeRound(round)

            // Inject steering only after the text round ends: keep written content in history and carry follow-up instructions into the next round.
            var hasSupplement: Boolean
            do {
                while (runController.hasPendingCompact) maybeCompactBeforeRound(round)
                hasSupplement = appendPendingSteeringOrSeal()
            } while (!hasSupplement && runController.hasPendingCompact)
            if (hasSupplement) {
                interruptedTextPrefix.setLength(0)
                round += 1
                continue
            }

            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("Model API returned empty on round $round${finishReason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
            }

            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length, generatedAtMillis = System.currentTimeMillis()))
            return Result(
                content = (interruptedTextPrefix.toString() + assistantMessage.optString("content")).trim(),
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    /**
     * Compact before the next model request.
     * Automatic compaction is evaluated by threshold before each request and at natural-completion boundaries; manual compaction drains the queue at the same boundaries.
     * Control returns here only after a tool batch finishes, so the current tool loop is never torn apart.
     */
    private fun historyForCompaction() = (systemCount.coerceIn(0, messages.length()) until messages.length())
        .map { AgentConversationCodec.fromJsonObject(messages.getJSONObject(it)) }

    private fun estimatedRequestTokens(): Int {
        val local = AgentContextBudget.estimate(messages) +
            AgentContextBudget.countTokens(currentRoundTools.toString())
        val projected = projectedPromptTokens()
        // When billed usage is available it wins. The local heuristic inflates tool JSON / code by Latin-character count,
        // which would pause the task at roughly half the real usage on a 500k window.
        return projected ?: local
    }

    private fun storedHistoryChars(): Long {
        val safeHistory = AgentConversationCodec.transcript(messages, systemCount, sensitiveToolCallIds)
        return safeHistory.sumOf { AgentConversationCodec.toJsonObject(it).toString().length.toLong() }
    }

    private fun persistenceCharLimit(): Long {
        val window = (config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow).toLong()
        val roomBudget = AgentConversationCodec.MAX_CONVERSATION_CHECKPOINT_CHARS.toLong() - 128_000L
        // Scale by window: the 1MB Room cap is only about 200k Latin tokens, so a 500k window would pause by mistake at half capacity.
        return maxOf(roomBudget, window * 6)
    }

    private fun requestOverBudget(): Boolean {
        val storedChars = storedHistoryChars()
        val charLimit = persistenceCharLimit()
        if (storedChars > charLimit) {
            compactionFailure = compactionFailure.ifBlank {
                "This round history is near the on-device persistence capacity; paused without truncating protected originals. You may allow compacting earlier steps or stop the task."
            }
            return true
        }
        val window = config.contextWindow?.takeIf { it > 0 } ?: return false
        return estimatedRequestTokens() > AgentCompressionBoundary.inputLimit(window, AgentCompressionBoundary.outputReserve(config))
    }

    private fun maybeCompactBeforeRound(round: Int, pressureRetry: Boolean = false) {
        val override = runController.takePendingCompact()
        val forced = override != null
        if (forced) { manualBudgetAttempt = true; lastFailedCompaction = null }
        if (!forced && !pressureRetry && (!compactPolicy.enabled || overflowPending)) return
        val window = config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow
        val charPressure = storedHistoryChars() > persistenceCharLimit() * 7 / 10
        if (!forced && !pressureRetry && skipIneffectiveAutoCompact && !charPressure && !requestOverBudget()) return
        if (!forced && !charPressure && estimatedRequestTokens() < AgentContextCompactor.autoPressureTokens(window)) return
        budgetCompressModelConfig = override?.compressModelConfig
            ?: if (pressureRetry) budgetCompressModelConfig else compactPolicy.compressModelConfig
        val keep = AgentContextCompactor.coerceKeepRecent(
            override?.keepRecentMessages ?: compactPolicy.keepRecentMessages,
        )
        budgetKeepRecent = keep
        var history = historyForCompaction()
        var cut = compactionStart(history)
        if (cut <= 0) {
            if (forced) onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length(),
                reason = "No complete compressible history unit within the current retention scope."))
            return
        }
        if (forced) onEvent(AgentEvent.ContextCompactionStarted(round, config.modelDisplayName.ifBlank { config.model }))
        val pruned = pruneOversizedToolResults(round, systemCount + cut)
        if (pruned) {
            // Both the DTO and same-model JSON replay must come from this new snapshot.
            history = historyForCompaction()
            cut = compactionStart(history)
            if (!forced && storedHistoryChars() <= persistenceCharLimit() * 7 / 10 &&
                estimatedRequestTokens() < AgentContextCompactor.autoPressureTokens(window) && !requestOverBudget()) return
        }
        if (forced && cut <= 0) {
            onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length(),
                reason = "No complete compressible history unit within the current retention scope."))
        }
        val reduced = cut > 0 && applyCompaction(round, history, cut)
        if (reduced || pruned) {
            overflowPending = false
            skipIneffectiveAutoCompact = false
            // Re-evaluate the whole request, not a desired summary length. At most
            // one additional pressure pass, and only after measurable progress.
            if (reduced && !pressureRetry && estimatedRequestTokens() >= AgentContextCompactor.autoPressureTokens(window)) {
                maybeCompactBeforeRound(round, pressureRetry = true)
            }
        } else if (!forced) {
            skipIneffectiveAutoCompact = true
        }
    }

    private fun compactionStart(history: List<AgentModelClient.ConversationMessage>): Int {
        return runCatching {
            AgentCompressionBoundary.selectStart(history,
                config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow,
                overflowPending)
        }.getOrDefault(0)
    }

    private fun tryBudgetCompaction(round: Int): Boolean {
        if (!compactPolicy.enabled && !manualBudgetAttempt) return false
        val history = historyForCompaction()
        val cut = compactionStart(history)
        if (cut <= 0) return false
        // Commit a pruning-only reduction first. The caller remeasures and takes a
        // fresh snapshot before attempting a summary if pressure is still high.
        if (pruneOversizedToolResults(round, systemCount + cut)) return true
        return applyCompaction(round, history, cut)
    }

    /** Only prune the selected prefix; the retained tail is always verbatim. */
    private fun pruneOversizedToolResults(round: Int, endExclusive: Int): Boolean {
        val archive = compactionArchive ?: return false
        val replacements = mutableListOf<Pair<Int, JSONObject>>()
        val checkpoints = mutableListOf<String>()
        try {
            for (index in systemCount until endExclusive) {
                runController.throwIfCancelled()
                if (Thread.currentThread().isInterrupted) throw io.github.mangi.eta.agent.runtime.AgentRunCancelledException()
                val original = messages.getJSONObject(index)
                if (original.optString("role") != "tool" || original.optString("tool_call_id") in sensitiveToolCallIds) continue
                val text = original.opt("content") as? String ?: continue
                if (text.codePointCount(0, text.length) <= 8192 ||
                    text.contains(AgentContextCompactor.TOOL_PRUNED_PREFIX) ||
                    text.contains(AgentContextCompactor.LEGACY_TOOL_PRUNED_PREFIX)
                ) {
                    continue
                }
                val id = archive.save(listOf(AgentConversationCodec.fromJsonObject(original)))
                archive.record(id, "started")
                val head = text.offsetByCodePoints(0, 4096)
                val tail = text.offsetByCodePoints(text.length, -1024)
                val shorter = text.substring(0, head) +
                    "\n${AgentContextCompactor.TOOL_PRUNED_PREFIX} original: context-checkpoint:$id; read_compacted_history]\n" +
                    text.substring(tail)
                val copy = JSONObject(original.toString()).put("content", shorter)
                if (AgentContextBudget.countTokens(shorter) >= AgentContextBudget.countTokens(text)) continue
                archive.record(id, "ready")
                replacements += index to copy
                checkpoints += id
            }
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            if (Thread.currentThread().isInterrupted || failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException) throw failure
            compactionFailure = failure.message ?: "Failed to archive tool originals, pruning not applied"
            return false
        }
        if (replacements.isEmpty()) return false
        runController.throwIfCancelled()
        replacements.forEach { (index, message) -> messages.put(index, message) }
        lastUsage = null
        lastUsageMessageCount = 0
        onHistoryCompacted()
        onEvent(AgentEvent.ContextCompacted(round, true, messages.length(), messages.length(),
            history = AgentConversationCodec.transcript(messages, systemCount, sensitiveToolCallIds),
            compressorLabel = "Tool-output budget pruning (originals readable)"))
        emitProjectedPrompt(round)
        checkpoints.forEach { runCatching { archive.record(it, "committed") } }
        return true
    }

    private fun applyCompaction(round: Int, history: List<AgentModelClient.ConversationMessage>, cut: Int): Boolean {
        val compressConfig = budgetCompressModelConfig?.let { model ->
            val window = model.contextWindow?.takeIf { it > 0 }
                ?: config.contextWindow?.takeIf { it > 0 }
                ?: compactPolicy.contextWindow.takeIf { it > 0 }
            if (window == null) model else model.copy(contextWindow = window)
        } ?: return false
        val original = messages.toString()
        val originalCount = messages.length()
        if (lastFailedCompaction == (original to cut)) return false
        onEvent(AgentEvent.ContextCompactionStarted(round, config.modelDisplayName.ifBlank { config.model }))
        var savedCheckpoint: String? = null
        var compactionStage = "archive"
        runCatching { io.github.mangi.eta.core.AndroidAgentLogger.info(
            "Mid-run compaction started: round=$round, selected=$cut, kept=${history.size - cut}, retention budget=${AgentCompressionBoundary.continuationRetentionBudget(compactPolicy.contextWindow)}") }
        val rewritten = try {
            val prefix = history.take(cut)
            val tail = history.drop(cut)
            val durablePrefix = AgentConversationCodec.redactSensitiveMessages(prefix, sensitiveToolCallIds)
            savedCheckpoint = compactionArchive?.save(durablePrefix)
            savedCheckpoint?.let { compactionArchive?.record(it, "started") }
            savedCheckpoint?.let { compactionArchive?.canAttach(it, prefix.size.coerceAtLeast(tail.size + 1), tail.size) }
            compactionStage = "summary"
            val summarySource = durablePrefix + tail
            val compressed = if (compactHistory != null) {
                compactHistory.invoke(summarySource, compactPolicy.copy(keepRecentMessages = budgetKeepRecent, compressModelConfig = compressConfig, keepStartOverride = cut))
            } else AgentContextCompactor.compress(
                summarySource, AgentContextCompactor.Config(
                    budgetKeepRecent,
                    compressConfig,
                    compactionArchive = compactionArchive,
                    usageConversationId = sessionId,
                ),
                keepStartOverride = cut, controller = runController,
                replay = if (compressConfig.providerType == config.providerType && compressConfig.baseUrl == config.baseUrl &&
                    compressConfig.model == config.model && compressConfig.openAiEndpointMode == config.openAiEndpointMode)
                    AgentContextCompactor.ReplayContext(
                        JSONArray().also { a -> for (i in 0 until systemCount) a.put(messages.getJSONObject(i)) },
                        JSONArray().also { a -> durablePrefix.forEach { a.put(AgentConversationCodec.toJsonObject(it)) } },
                        currentRoundTools, sessionId,
                    ) else null,
            )
            compactionStage = "validate"
            require(compressed.size >= tail.size && compressed.takeLast(tail.size) == tail) { "Protected tail changed after summarization" }
            require(compressed != history) { "No compressible history" }
            runController.throwIfCancelled()
            require(messages.toString() == original) { "Context changed while generating the summary, summary not applied" }
            val withPointers = savedCheckpoint?.let {
                requireNotNull(compactionArchive).attachReferences(durablePrefix, it, compressed, tail.size)
            } ?: compressed
            require(withPointers.sumOf { AgentContextBudget.countMessage(it).toLong() } < history.sumOf { AgentContextBudget.countMessage(it).toLong() }) {
                "Summary and index did not reduce context, originals kept"
            }
            savedCheckpoint?.let { compactionArchive?.record(it, "ready") }
            withPointers
        } catch (failure: Exception) {
            savedCheckpoint?.let { runCatching { compactionArchive?.record(it, "failed") } }
            if (runController.isCancelled || Thread.currentThread().isInterrupted || failure is InterruptedException ||
                failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException) throw failure
            lastFailedCompaction = original to cut
            compactionFailure = failure.message ?: "Compaction failed, originals kept"
            runCatching { io.github.mangi.eta.core.AndroidAgentLogger.warn(
                "Mid-run compaction failed: stage=$compactionStage, checkpoint=$savedCheckpoint, round=$round, ${failure.javaClass.simpleName}: $compactionFailure") }
            onEvent(AgentEvent.ContextCompacted(round, false, originalCount, originalCount, reason = compactionFailure))
            return false
        }
        // Never reserialize the kept live tail through the persistence DTO. That would strip
        // provider-only response items, images, and long tool bodies even in protected mode.
        val keptJson = (systemCount + cut until messages.length()).map { messages.getJSONObject(it) }
        val prefixJson = (0 until systemCount).map { messages.getJSONObject(it) }
        val newPrefix = rewritten.dropLast(history.size - cut)
        while (messages.length() > 0) messages.remove(messages.length() - 1)
        prefixJson.forEach(messages::put)
        newPrefix.forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        keptJson.forEach(messages::put)
        lastUsage = null
        lastUsageMessageCount = 0
        compactionFailure = ""
        onHistoryCompacted()
        onEvent(AgentEvent.ContextCompacted(round, true, originalCount, messages.length(),
            history = AgentConversationCodec.transcript(messages, systemCount, sensitiveToolCallIds),
            compressorLabel = compressorLabel(compressConfig)))
        emitProjectedPrompt(round)
        savedCheckpoint?.let { runCatching { compactionArchive?.record(it, "committed") } }
        runCatching { io.github.mangi.eta.core.AndroidAgentLogger.info(
            "Mid-run compaction committed: checkpoint=$savedCheckpoint, round=$round, messages=$originalCount->${messages.length()}") }
        return true
    }

    private fun requestConfigForRound(): AgentModelClient.ModelConfig {
        if (!suppressThinkingForNextRequest) return config
        suppressThinkingForNextRequest = false
        return AgentRuntimePolicy.withoutOptionalThinking(config)
    }

    private fun compressorLabel(config: AgentModelClient.ModelConfig): String {
        val provider = config.providerName.trim()
        val model = config.modelDisplayName.trim().ifBlank { config.model.trim() }
        return when {
            provider.isNotBlank() && model.isNotBlank() -> "$provider · $model"
            model.isNotBlank() -> model
            else -> provider
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringInput() ?: return false
        supplementStartsNewBlock = true
        messages.put(AgentSupplementMedia.userMessage(steeringPrompt(supplement.text), supplement.imagesJson).put(AgentTurnIdentity.JSON_KEY, turnId))
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringInputOrSeal() ?: return false
        supplementStartsNewBlock = true
        messages.put(AgentSupplementMedia.userMessage(steeringPrompt(supplement.text), supplement.imagesJson).put(AgentTurnIdentity.JSON_KEY, turnId))
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        AgentContextCompactor.steeringUserContent(supplement)

    private fun appendCompactContinueIfNeeded(suppressOptionalThinking: Boolean = true) {
        val last = messages.optJSONObject(messages.length() - 1) ?: return
        if (!last.optString("role").equals("assistant", ignoreCase = true)) return
        messages.put(
            AgentConversationCodec.userTextMessage(
                AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT,
            ).put(AgentTurnIdentity.JSON_KEY, turnId),
        )
        suppressThinkingForNextRequest = suppressOptionalThinking
    }

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        toolCallValidator.validate(toolCall)?.let { validationError ->
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "INVALID_TOOL_ARGUMENTS",
                message = validationError,
            )
        }
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )

        val result = try {
            if (toolCall.name == AgentCompactionArchive.TOOL && compactionArchive != null) {
                compactionArchive.read(toolCall.argumentsJson)
            } else toolExecutor.execute(toolCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
            sensitiveToolCallIds += toolCall.id
        }

        // Once returned, this result remains evidence even if stop arrived concurrently.
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
            )
        )
    }

    private fun projectedPromptTokens(): Int? {
        val billedInput = lastUsage?.occupancyTokens() ?: return null
        if (lastUsageMessageCount <= 0) return billedInput
        var added = 0
        for (index in lastUsageMessageCount until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            added += AgentContextBudget.countMessage(AgentConversationCodec.fromJsonObject(message))
        }
        return billedInput + added
    }

    private fun emitProjectedPrompt(round: Int) {
        val projected = projectedPromptTokens() ?: (AgentContextBudget.estimate(messages) +
            AgentContextBudget.countTokens(currentRoundTools.toString()))
        if (projected <= 0) return
        onEvent(
            AgentEvent.UsageReceived(
                round = round,
                usage = AgentTokenUsage(inputTokens = projected),
                projected = true,
            ),
        )
    }

    private fun appendToolOutcomes(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // The provider requires all tool results of the same assistant batch to appear consecutively; image observations go together after the batch.
        outcomes.forEach { outcome ->
            messages.put(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result).put(AgentTurnIdentity.JSON_KEY, turnId))
        }

        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) return

        // Tool screenshots are transient observations, not session assets. Delete them right after the next inference consumes them.
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.\n" +
                imageOutcomes.joinToString("\n") { AuxiliaryVision.observationMetadata(it.result.content) } +
                images.mapIndexed { index, image -> "image ${index + 1}: ${image.width ?: "unknown"} x ${image.height ?: "unknown"} px" }.joinToString("\n", prefix = "\n"),
            images = images,
        ).put(AgentTurnIdentity.JSON_KEY, turnId).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}
