package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.delegation.*
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentLoop
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * Blocking executor for a single Runtime run.
 *
 * It only owns the model, tools, and final-state submission; it holds no Service, Messenger, Compose, or WindowManager state.
 * All external side effects are handed back to the host through narrow callbacks.
 */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )

    private val appContext = context.applicationContext

    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome {
        val runController = session.controller
        val archivedEvents = mutableListOf<AgentEvent>()
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var toolExecutor: AutoCloseable? = null
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var children: SubAgentCoordinator? = null
        var childBinding: AgentRunController.ResourceBinding? = null
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        var unownedSkillRoot: java.io.File? = null
        val timing = AgentRunTiming(AndroidAgentLogger)

        val result = try {
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            entrySurfaceGuard = EntrySurfaceGuard.from(
                handoff = request.handoff,
                logger = AndroidAgentLogger,
                etaVoiceSurfaceDismissal = {
                    EtaAssistantOverlayService.dismissForForegroundOperation(appContext)
                },
            )
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(
                cacheRoot = appContext.cacheDir,
                baseClient = AgentHttpClient.client,
            )
            val assistant = requireNotNull(AssistantRepository.currentProfile(request.assistantId)) { "The assistant that owns this task does not exist. Please start the task again." }
            val enabledSkillIds = assistant.enabledSkillIds.toSet()
            val (runSkillsRoot, runSkillEntries) = SkillRuntime.createRunSkills(appContext, assistant.id,
                skillIndexService.listSkillsForManagement(forceRefresh = true)
                    .filter { it.installed && it.id in enabledSkillIds }
                    .filter { SkillCompatibilityChecker.evaluate(it).available })
            unownedSkillRoot = runSkillsRoot
            val skillContext = SkillContext(installedSkills = runSkillEntries)
            val memoryEnabled = assistant.memoryEnabled
            val memoryContext = if (memoryEnabled) {
                runCatching {
                    AgentMemoryContextBuilder.build(
                        snapshot = AgentMemoryRepository.snapshot(assistant.id),
                        contextWindow = request.config.contextWindow,
                    )
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            } else {
                AgentMemoryContext.DISABLED
            }
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") {
                        "MCP tool snapshot unavailable: type=${throwable.safeLogType()}"
                    }
                    McpRunSnapshot.EMPTY
                }
            }
            val mcpTools = JSONArray().also(mcpSnapshot::appendModelTools)
            val executor = AgentLocalTools(
                context = appContext,
                logger = AndroidAgentLogger,
                browserRunId = request.runId,
                browserToolsEnabled = {
                    request.config.browserTools && currentPermissions().browserTools
                },
                terminalToolsEnabled = {
                    request.config.terminalTools && currentPermissions().terminalTools
                },
                deviceDirectToolsEnabled = {
                    request.config.deviceDirectTools && currentPermissions().deviceDirectTools
                },
                deviceSensitiveReadToolsEnabled = {
                    request.config.deviceSensitiveReadTools &&
                        currentPermissions().deviceSensitiveReadTools
                },
                deviceSensitiveActionToolsEnabled = {
                    request.config.deviceSensitiveActionTools &&
                        currentPermissions().deviceSensitiveActionTools
                },
                memoryToolsEnabled = { AssistantRepository.currentProfile(assistant.id)?.memoryEnabled == true },
                screenshotExcludedPackages = {
                    entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty()
                },
                beforeToolExecution = { toolName ->
                    val requiresAccessibility =
                        AgentToolRequirements.requiresAccessibility(toolName)
                    if (
                        !requiresAccessibility &&
                        !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName)
                    ) {
                        ToolExecutionDecision.Allow
                    } else {
                        val accessibility = if (requiresAccessibility) {
                            AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
                        } else {
                            null
                        }
                        when {
                            accessibility != null && !accessibility.available ->
                                ToolExecutionDecision.Reject(
                                    code = accessibility.code,
                                    message = accessibility.message,
                                )
                            entrySurfaceGuard?.dismissOnce() == false ->
                                ToolExecutionDecision.Reject(
                                    code = "ENTRY_SURFACE_NOT_READY",
                                    message = "The entry window has not finished closing; this tool was not executed. Do not repeatedly call it in the current task.",
                                )
                            else -> ToolExecutionDecision.Allow
                        }
                    }
                },
                skillIndexService = skillIndexService,
                skillLoader = skillLoader,
                skillResourceReader = skillResourceReader,
                githubSkillSource = githubSkillSource,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                runSkillEntries = skillContext.installedSkills,
                memoryAssistantId = assistant.id,
                runSkillsRoot = runSkillsRoot,
                pendingSkillConflict = pendingSkillConflict,
            )
            unownedSkillRoot = null
            toolExecutor = executor
            val routingExecutor = RoutingToolExecutor(
                local = executor,
                mcp = McpToolExecutor(mcpSnapshot),
            )
            toolExecutor = routingExecutor
            toolsBinding = runController.register { routingExecutor.close() }
            timing.preparationFinished(skillContext.installedSkills.size)
            val configuredChildren = if (SubAgentPreferences.enabled(request.effectiveModelSessionId)) runBlocking {
                (0 until SubAgentPreferences.SLOT_COUNT).mapNotNull { slot ->
                    runCatching { SubAgentPreferences.selection(slot).resolve()?.let { SubAgentPreferences.applyReasoning(slot, it) } }.getOrNull()
                        ?.takeIf { it.apiKey.isNotBlank() && it.baseUrl.isNotBlank() }
                        ?.let { slot to it }
                }
            } else emptyList()
            val childModels = configuredChildren.map { it.second }
            if (childModels.isNotEmpty()) {
                val workspace = if (request.config.terminalTools && currentPermissions().terminalTools) SubAgentWorkspace(appContext, executor) else null
                children = SubAgentCoordinator(childModels,
                    roles = configuredChildren.map { SubAgentPreferences.role(it.first) },
                    workspace = workspace,
                    onContext = { stats -> acceptEvent(session, AgentEvent.ChildContextUpdated(stats), archivedEvents, entrySurfaceGuard, checkpointRecorder) },
                    executeObservedChild = { config, prompt, controller, project, id, writable, progress ->
                        if (id != null) {
                            val backend = requireNotNull(workspace)
                            SubAgentRunner.run(config, prompt, SubAgentWorkspace.childTools(writable),
                                backend.childExecutor(project, id, writable, controller), controller,
                                workspaceMode = true, writable = writable, sessionId = request.effectiveModelSessionId, onProgress = progress)
                        } else {
                            val readTools = SubAgentTools.filter(AgentToolCatalog.build(
                                terminalTools = request.config.terminalTools && currentPermissions().terminalTools,
                                browserTools = false,
                                deviceDirectTools = request.config.deviceDirectTools && currentPermissions().deviceDirectTools,
                                deviceSensitiveReadTools = request.config.deviceSensitiveReadTools && currentPermissions().deviceSensitiveReadTools,
                                memoryTools = memoryEnabled, capabilities = AgentToolCapabilities.capture(appContext)))
                            SubAgentRunner.run(config, prompt, readTools, executor, controller,
                                sessionId = request.effectiveModelSessionId, onProgress = progress)
                        }
                    },
                    executeWorkspaceChild = { config, prompt, controller, project, id, writable ->
                        val backend = requireNotNull(workspace)
                        SubAgentRunner.run(config, prompt, SubAgentWorkspace.childTools(writable),
                            backend.childExecutor(project, id, writable, controller), controller,
                            workspaceMode = true, writable = writable, sessionId = request.effectiveModelSessionId)
                    },
                ) { config, prompt, controller ->
                    val readTools = SubAgentTools.filter(AgentToolCatalog.build(
                        terminalTools = request.config.terminalTools && currentPermissions().terminalTools,
                        browserTools = false,
                        deviceDirectTools = request.config.deviceDirectTools && currentPermissions().deviceDirectTools,
                        deviceSensitiveReadTools = request.config.deviceSensitiveReadTools && currentPermissions().deviceSensitiveReadTools,
                        memoryTools = memoryEnabled,
                        capabilities = AgentToolCapabilities.capture(appContext),
                    ))
                    SubAgentRunner.run(config, prompt, readTools, executor, controller, sessionId = request.effectiveModelSessionId)
                }
                childBinding = runController.register { children?.close() }
                SubAgentTools.appendTo(mcpTools, configuredChildren.mapIndexed { i, (slot, model) ->
                    "${i + 1}: ${if (SubAgentPreferences.role(slot) == "implementation") "implementation" else "review/summary"} — ${model.providerName} / ${model.modelDisplayName.ifBlank { model.model }}"
                }, workspaceEnabled = workspace != null)
            }
            val delegatedExecutor = AgentModelClient.ToolExecutor { call ->
                val coordinator = children
                if (coordinator != null && call.name in SubAgentTools.names) coordinator.execute(call)
                else routingExecutor.execute(call)
            }
            val compactPolicy = runBlocking { AgentCompressionPolicy.resolve(request.config) }
            val completedResponse = AgentModelClient.complete(
                config = request.config,
                sessionId = request.effectiveModelSessionId,
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                prompt = request.prompt,
                toolExecutor = delegatedExecutor,
                images = request.images,
                history = request.history,
                // Runtime owns all request-budget decisions; never silently trim protected history here.
                skipHistoryTrimming = true,
                compactionArchive = io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, request.effectiveModelSessionId),
                turnId = request.effectiveTurnId,
                runController = runController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                skillContextProvider = {
                    check(AssistantRepository.currentProfile(assistant.id) != null) { "The assistant that owns this task has been deleted." }
                    SkillContext(installedSkills = executor.currentSkillEntries())
                },
                memoryContextProvider = {
                    val current = requireNotNull(AssistantRepository.currentProfile(assistant.id)) { "The assistant that owns this task has been deleted." }
                    if (!current.memoryEnabled) {
                        AgentMemoryContext.DISABLED
                    } else {
                        runCatching {
                            AgentMemoryContextBuilder.build(
                                snapshot = AgentMemoryRepository.snapshot(current.id),
                                contextWindow = request.config.contextWindow,
                            )
                        }.getOrElse { AgentMemoryContextBuilder.empty(request.config.contextWindow) }
                    }
                },
                additionalTools = mcpTools,
                linuxEnvironmentLabelProvider = {
                    when (LinuxEnvironmentSettingsRepository.current(appContext)) {
                        LinuxDistribution.ALPINE -> "Alpine"
                        LinuxDistribution.DEBIAN -> "Debian"
                    }
                },
                terminalSessionEnvironmentProvider = executor::terminalSessionEnvironment,
                terminalSessionIdentityProvider = executor::terminalSessionIdentity,
                compactPolicy = compactPolicy,
                onEvent = { event ->
                    timing.accept(event)
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                },
            )
            response = completedResponse
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent,
                transcript = completedResponse.transcript,
            )
        } catch (throwable: Throwable) {
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException
            val modelFailure = throwable as? AgentModelExecutionException
            val message = if (cancelled) {
                "Stopped"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                AndroidAgentLogger.info("Agent runtime stopped")
            } else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                AndroidAgentLogger.error(
                    "Agent runtime failed: type=${throwable.safeLogType()}, " +
                        "model_code=${requestFailure?.code.orEmpty()}, " +
                        "cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}, " +
                        "detail=${(requestFailure?.message ?: throwable.message).orEmpty().take(600)}"
                )
                val event = AgentEvent.RunFailed(message)
                runCatching {
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }.onFailure { checkpointFailure ->
                    AndroidAgentLogger.error(
                        "Agent runtime failure checkpoint failed: " +
                            "type=${checkpointFailure.safeLogType()}"
                    )
                    session.emit(event)
                }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = message,
                reasoningContent = modelFailure?.reasoningContent
                    ?: (throwable as? AgentRunCancelledException)?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript
                    ?: (throwable as? AgentRunCancelledException)?.transcript.orEmpty(),
            )
        } finally {
            runCatching { childBinding?.close() }
            runCatching { children?.close() }
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
            unownedSkillRoot?.let { root -> runCatching { SkillRuntime.releaseRunSkills(appContext, root) } }
        }

        // Stopped runs use the same durable outbox path as successful/failed runs.
        val completedRequest = runCatching { snapshotRequest(request) }
            .getOrElse { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime request snapshot failed: type=${throwable.safeLogType()}"
                )
                request
            }
        val committed = session.complete(result) { terminal ->
            runCatching { checkpointRecorder?.seal() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
            runCatching { persistArtifacts(completedRequest, terminal, archivedEvents) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        return Outcome(
            result = session.terminalResult ?: result,
            entrySurfaceGuard = entrySurfaceGuard,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed && session.terminalResult?.ok == true },
            shouldUpdateHost = committed,
        )
    }

    @Synchronized private fun acceptEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>,
        entrySurfaceGuard: EntrySurfaceGuard?,
        checkpointRecorder: AgentRunCheckpointRecorder?,
    ) {
        checkpointRecorder?.accept(event)
        if (!session.emit(event)) return
        archivedEvents += event
        if (event is AgentEvent.ModelRetryScheduled) {
            AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        } else if (event !is AgentEvent.AssistantBlockDelta) {
            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                    "Agent runtime event projection failed: type=${throwable.safeLogType()}"
                }
            }
    }

}
