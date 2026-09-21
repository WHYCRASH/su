package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runtime client on the entry-process side.
 *
 * It only hands one agent request to the module process and carries events/results back to the entry adapter layer;
 * it runs no models, no tools, and renders no UI.
 */
internal class AgentRuntimeClient(
    private val context: Context,
    private val logger: AgentLogger
) {
    sealed interface AttachOutcome {
        data class Completed(val result: AgentRuntimeWire.RunResult) : AttachOutcome
        data object NotActive : AttachOutcome
        data object Unavailable : AttachOutcome
    }

    sealed interface ActiveRunQuery {
        data class Known(val runIds: Set<String>) : ActiveRunQuery {
            val runId: String? get() = runIds.firstOrNull()
        }
        data object Unavailable : ActiveRunQuery
    }

    sealed interface CompletedRunsQuery {
        data class Known(val runs: List<AgentRuntimeWire.CompletedRun>) : CompletedRunsQuery
        data object Unavailable : CompletedRunsQuery
    }

    fun run(
        request: AgentRuntimeWire.RunRequest,
        onEvent: (AgentEvent) -> Unit,
    ): AgentRuntimeWire.RunResult = run(request, onEvent, isStopRequested = { false })

    fun run(
        request: AgentRuntimeWire.RunRequest,
        onEvent: (AgentEvent) -> Unit,
        isStopRequested: () -> Boolean,
    ): AgentRuntimeWire.RunResult {
        if (isStopRequested()) return AgentRuntimeWire.RunResult(request.runId, false, "", "Stopped")
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<AgentRuntimeWire.RunResult?>()
        val preparedImagesRef = AtomicReference<AgentRuntimeImageTransfer.PreparedImages?>()
        val preparedHistoryRef = AtomicReference<AgentRuntimeHistoryTransfer.PreparedHistory?>()
        val clientMessenger = Messenger(
            ClientHandler(
                onEvent = onEvent,
                onResult = { result ->
                    resultRef.set(result)
                    resultLatch.countDown()
                },
                onRequestIngested = {
                    preparedImagesRef.getAndSet(null)?.close()
                    preparedHistoryRef.getAndSet(null)?.close()
                },
            )
        )

        val lease = AgentRuntimeConnection.acquire(context, logger)
            ?: return AgentRuntimeWire.RunResult("", false, "", "Failed to bind the Agent Runtime service")
        val serviceMessenger = lease.messenger
        val deathRecipient = IBinder.DeathRecipient {
            if (resultRef.get() == null) {
                resultRef.set(
                    AgentRuntimeWire.RunResult("", false, "", "Agent Runtime service connection was lost")
                )
                resultLatch.countDown()
            }
        }

        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_START_RUN)
            msg.replyTo = clientMessenger
            val preparedImages = AgentRuntimeImageTransfer.prepare(context, request.images)
            preparedImagesRef.set(preparedImages)
            val preparedHistory = AgentRuntimeHistoryTransfer.prepare(context, request.history)
            preparedHistoryRef.set(preparedHistory)
            msg.data = AgentRuntimeWire.toBundle(request, preparedImages.images, preparedHistory.descriptor)
            serviceMessenger.send(msg)
            if (isStopRequested()) {
                val cancel = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
                cancel.data = AgentRuntimeWire.ackBundle(request.runId)
                serviceMessenger.send(cancel)
            }
            // The terminal result or a Binder disconnect wakes the waiter; normal long tasks are not cancelled by client wait time.
            resultLatch.await()
            return resultRef.get() ?: AgentRuntimeWire.RunResult("", false, "", "Agent Runtime returned no result")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching {
                val cancelMessage = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
                cancelMessage.data = AgentRuntimeWire.ackBundle(request.runId)
                serviceMessenger.send(cancelMessage)
            }
            return AgentRuntimeWire.RunResult("", false, "", "Agent Runtime wait was interrupted")
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime start request failed: type=${throwable.safeLogType()}")
            return AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = when (throwable) {
                    is AgentRuntimeWire.PayloadTooLargeException -> throwable.message
                    is AgentRuntimeImageTransfer.ImageTransferException -> throwable.message
                    else -> "Failed to send the Agent Runtime request (${throwable.safeLogType()})"
                },
            )
        } finally {
            preparedImagesRef.getAndSet(null)?.close()
            preparedHistoryRef.getAndSet(null)?.close()
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        withRuntimeMessenger(Unit) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
        }
    }

    fun steerRun(runId: String, text: String, requestId: String = "", imagesJson: String = "[]"): Boolean {
        if (runId.isBlank() || text.isBlank() || text.length > 64_000 || requestId.length > 128) return false
        if (runCatching { io.github.mangi.eta.agent.model.AgentSupplementMedia.persistedImages(imagesJson) }.isFailure) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_STEER_RUN)
            msg.data = AgentRuntimeWire.steerBundle(runId, text, requestId, imagesJson)
            serviceMessenger.send(msg)
            true
        }
    }

    fun pauseRun(runId: String) {
        if (runId.isBlank()) return
        withRuntimeMessenger(Unit) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_PAUSE_RUN)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
        }
    }

    fun resumeRun(runId: String) {
        if (runId.isBlank()) return
        withRuntimeMessenger(Unit) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESUME_RUN)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
        }
    }

    fun compactRun(
        runId: String,
        keepRecent: Int,
        compressModelConfig: AgentModelClient.ModelConfig? = null,
    ): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_COMPACT_RUN)
            msg.data = AgentRuntimeWire.compactBundle(runId, keepRecent, compressModelConfig)
            serviceMessenger.send(msg)
            true
        }
    }

    fun ackResult(runId: String): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ACK_RESULT)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
            true
        }
    }

    fun drainCompletedRuns(): List<AgentRuntimeWire.CompletedRun> {
        return when (val query = queryCompletedRuns()) {
            is CompletedRunsQuery.Known -> query.runs
            CompletedRunsQuery.Unavailable -> emptyList()
        }
    }

    fun queryCompletedRuns(): CompletedRunsQuery {
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<List<AgentRuntimeWire.CompletedRun>>(emptyList())
        val clientMessenger = Messenger(
            DrainHandler { results ->
                resultRef.set(results)
                resultLatch.countDown()
            }
        )

        return withRuntimeMessenger<CompletedRunsQuery>(CompletedRunsQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS)
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            if (resultLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                CompletedRunsQuery.Known(resultRef.get())
            } else {
                CompletedRunsQuery.Unavailable
            }
        }
    }

    fun queryActiveRun(): ActiveRunQuery {
        val responseLatch = CountDownLatch(1)
        val runIdsRef = AtomicReference<Set<String>>(emptySet())
        val clientMessenger = Messenger(
            ActiveRunHandler { runIds ->
                runIdsRef.set(runIds)
                responseLatch.countDown()
            }
        )

        return withRuntimeMessenger<ActiveRunQuery>(ActiveRunQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN)
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            if (!responseLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ActiveRunQuery.Unavailable
            } else {
                ActiveRunQuery.Known(runIdsRef.get())
            }
        }
    }

    /** History is delivered to onReplay in one batch, falling back to onEvent when unset. Later events always go to onEvent. */
    fun attachRun(
        runId: String,
        onReplay: ((List<AgentEvent>) -> Unit)? = null,
        onEvent: (AgentEvent) -> Unit,
    ): AttachOutcome {
        if (runId.isBlank()) return AttachOutcome.NotActive
        val terminalLatch = CountDownLatch(1)
        val attachLatch = CountDownLatch(1)
        val attachedRef = AtomicReference<Boolean?>(null)
        val resultRef = AtomicReference<AgentRuntimeWire.RunResult?>()
        val clientMessenger = Messenger(
            AttachHandler(
                onReplay = onReplay,
                onEvent = onEvent,
                onAttachResponse = { attached ->
                    attachedRef.set(attached)
                    attachLatch.countDown()
                    if (!attached) terminalLatch.countDown()
                },
                onResult = { result ->
                    resultRef.set(result)
                    attachLatch.countDown()
                    terminalLatch.countDown()
                },
            )
        )
        val lease = AgentRuntimeConnection.acquire(context, logger)
            ?: return AttachOutcome.Unavailable
        val deathRecipient = IBinder.DeathRecipient {
            attachLatch.countDown()
            terminalLatch.countDown()
        }

        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN)
            msg.replyTo = clientMessenger
            msg.data = AgentRuntimeWire.ackBundle(runId)
            lease.messenger.send(msg)
            if (!attachLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return AttachOutcome.Unavailable
            }
            terminalLatch.await()
            resultRef.get()?.let { return AttachOutcome.Completed(it) }
            return if (attachedRef.get() == false) {
                AttachOutcome.NotActive
            } else {
                AttachOutcome.Unavailable
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return AttachOutcome.Unavailable
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime attach failed: type=${throwable.safeLogType()}")
            return AttachOutcome.Unavailable
        } finally {
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    private fun <T> withRuntimeMessenger(defaultValue: T, block: (Messenger) -> T): T {
        val lease = AgentRuntimeConnection.acquire(context, logger) ?: return defaultValue
        try {
            return block(lease.messenger)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return defaultValue
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime service call failed: type=${throwable.safeLogType()}")
            return defaultValue
        } finally {
            lease.close()
        }
    }

    private class ClientHandler(
        private val onEvent: (AgentEvent) -> Unit,
        private val onResult: (AgentRuntimeWire.RunResult) -> Unit,
        private val onRequestIngested: () -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT -> {
                    AgentRuntimeWire.eventFromBundle(msg.data ?: return)?.let(onEvent)
                }

                AgentRuntimeWire.MSG_RESULT -> {
                    val data = msg.data ?: return
                    val result = runCatching {
                        AgentRuntimeWire.runResultFromBundle(data)
                    }.getOrElse { throwable ->
                        AgentRuntimeWire.RunResult(
                            runId = AgentRuntimeWire.runIdFromBundle(data),
                            ok = false,
                            content = "",
                            error = "Failed to parse the Agent Runtime result (${throwable.javaClass.simpleName})",
                        )
                    }
                    onResult(result)
                }

                AgentRuntimeWire.MSG_REQUEST_INGESTED -> onRequestIngested()
            }
        }
    }

    private class DrainHandler(
        private val onResults: (List<AgentRuntimeWire.CompletedRun>) -> Unit
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE) {
                onResults(AgentRuntimeWire.completedRunsFromBundle(msg.data ?: return))
            }
        }
    }

    private class ActiveRunHandler(
        private val onResponse: (Set<String>) -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE) {
                onResponse(AgentRuntimeWire.runIdsFromBundle(msg.data ?: return))
            }
        }
    }

    private class AttachHandler(
        onReplay: ((List<AgentEvent>) -> Unit)?,
        onEvent: (AgentEvent) -> Unit,
        onAttachResponse: (Boolean) -> Unit,
        onResult: (AgentRuntimeWire.RunResult) -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        private val delivery = AgentRuntimeAttachDelivery(
            onReplay = onReplay,
            onEvent = onEvent,
            onAttachResponse = onAttachResponse,
            onResult = onResult,
        )

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT ->
                    AgentRuntimeWire.eventFromBundle(msg.data ?: return)?.let(delivery::event)
                AgentRuntimeWire.MSG_RESULT -> {
                    val data = msg.data ?: return
                    val result = runCatching {
                        AgentRuntimeWire.runResultFromBundle(data)
                    }.getOrElse { throwable ->
                        AgentRuntimeWire.RunResult(
                            runId = AgentRuntimeWire.runIdFromBundle(data),
                            ok = false,
                            content = "",
                            error = "Failed to parse the Agent Runtime result (${throwable.javaClass.simpleName})",
                        )
                    }
                    delivery.result(result)
                }
                AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE ->
                    delivery.attachResponse(AgentRuntimeWire.attachRunSucceeded(msg.data ?: return))
            }
        }
    }

    private companion object {
        const val RESPONSE_TIMEOUT_SECONDS = 8L
    }
}
