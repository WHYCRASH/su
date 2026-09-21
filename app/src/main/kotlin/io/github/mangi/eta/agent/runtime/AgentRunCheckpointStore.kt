package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeInFlightEventEntity
import io.github.mangi.eta.data.db.RuntimeInFlightRunEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Durable per-process log for in-flight UI runs.
 *
 * It never stores provider configs, API keys, tool-call argument deltas, or raw tool results, only the
 * argument summaries, terminal commands, and result summaries already visible in the UI.
 */
internal object AgentRunCheckpointStore {
    data class Checkpoint(
        val runId: String,
        val ownerInstanceId: String,
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val createdAt: Long,
        val updatedAt: Long,
    )

    fun start(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        ownerInstanceId: String = AgentRuntimeProcessIdentity.id,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val handoff = request.handoff ?: return false
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return false
        val runId = request.runId.takeIf(String::isNotBlank) ?: return false
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao().replaceInFlightRun(
                RuntimeInFlightRunEntity(
                    runId = runId,
                    ownerInstanceId = ownerInstanceId,
                    handoffId = handoff.id,
                    handoffSource = handoff.source,
                    handoffPayload = handoff.payload,
                    dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        return true
    }

    fun append(
        context: Context,
        runId: String,
        sortIndex: Int,
        event: AgentEvent,
        now: Long = System.currentTimeMillis(),
    ) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).runtimeRunDao().appendInFlightEvent(
                event = RuntimeInFlightEventEntity(
                    runId = runId,
                    sortIndex = sortIndex,
                    eventJson = AgentEventJsonCodec.encode(event),
                ),
                updatedAt = now,
            )
        }
    }

    /** Return all unacknowledged runs; whether each is active or finished is judged by the recovery coordinator against Runtime state. */
    fun list(context: Context): List<Checkpoint> =
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .inFlightRuns()
                .asSequence()
                .map { stored ->
                    Checkpoint(
                        runId = stored.run.runId,
                        ownerInstanceId = stored.run.ownerInstanceId,
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = stored.run.handoffId,
                            source = stored.run.handoffSource,
                            payload = stored.run.handoffPayload,
                            dismissEntrySurfaceOnForegroundOperation =
                                stored.run.dismissEntrySurface,
                        ),
                        events = stored.events
                            .sortedBy { it.sortIndex }
                            .mapNotNull { AgentEventJsonCodec.decode(it.eventJson) },
                        createdAt = stored.run.createdAt,
                        updatedAt = stored.run.updatedAt,
                    )
                }
                .toList()
        }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .deleteInFlightRun(runId)
        }
    }
}
