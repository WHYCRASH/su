package io.github.mangi.eta.agent.runtime

import android.content.Context

/** Merges high-frequency text deltas and writes them to the checkpoint, while structured boundaries are synchronously persisted to disk. */
internal class AgentRunCheckpointRecorder private constructor(
    context: Context,
    private val runId: String,
    private val nanoTime: () -> Long,
) {
    private val appContext = context.applicationContext
    private var nextSortIndex = 0
    private var pendingDelta: AgentEvent.AssistantBlockDelta? = null
    private var lastFlushNanos = nanoTime()

    fun accept(event: AgentEvent) {
        val checkpointEvent = event.recoveryProjection() ?: return
        if (checkpointEvent is AgentEvent.AssistantBlockDelta) {
            val pending = pendingDelta
            if (
                pending != null &&
                pending.round == checkpointEvent.round &&
                pending.kind == checkpointEvent.kind &&
                pending.index == checkpointEvent.index
            ) {
                pendingDelta = pending.copy(
                    deltaChars = pending.deltaChars + checkpointEvent.deltaChars,
                    delta = pending.delta + checkpointEvent.delta,
                )
            } else {
                flushPendingDelta()
                pendingDelta = checkpointEvent
            }
            val elapsed = nanoTime() - lastFlushNanos
            if (
                pendingDelta.orEmptyChars() >= MAX_BUFFERED_DELTA_CHARS ||
                elapsed >= MAX_BUFFERED_DELTA_NANOS
            ) {
                flushPendingDelta()
            }
            return
        }

        flushPendingDelta()
        append(checkpointEvent)
    }

    /** Commits the last delta segment to the log; the log is deleted by the result ACK or interruption recovery. */
    fun seal() {
        flushPendingDelta()
    }

    fun discard() {
        pendingDelta = null
        AgentRunCheckpointStore.remove(appContext, runId)
    }

    private fun flushPendingDelta() {
        val event = pendingDelta ?: return
        pendingDelta = null
        append(event)
        lastFlushNanos = nanoTime()
    }

    private fun append(event: AgentEvent) {
        AgentRunCheckpointStore.append(
            context = appContext,
            runId = runId,
            sortIndex = nextSortIndex++,
            event = event,
        )
    }

    private fun AgentEvent.AssistantBlockDelta?.orEmptyChars(): Int = this?.deltaChars ?: 0

    companion object {
        private const val MAX_BUFFERED_DELTA_CHARS = 512
        private const val MAX_BUFFERED_DELTA_NANOS = 250_000_000L

        fun create(
            context: Context,
            request: AgentRuntimeWire.RunRequest,
            nanoTime: () -> Long = System::nanoTime,
        ): AgentRunCheckpointRecorder? {
            if (!AgentRunCheckpointStore.start(context, request)) return null
            return AgentRunCheckpointRecorder(
                context = context,
                runId = request.runId,
                nanoTime = nanoTime,
            )
        }
    }
}
