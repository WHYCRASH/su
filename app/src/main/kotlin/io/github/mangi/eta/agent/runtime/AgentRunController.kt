package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient


import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController {
    private val resources = CopyOnWriteArraySet<CancellableResource>()

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    data class SteeringInput(val text: String, val imagesJson: String = "[]")
    private val steeringMessages = ArrayDeque<SteeringInput>()
    private var acceptingSteering = true
    private var stoppedSteering = emptyList<SteeringInput>()

    fun takeStoppedSteering(): List<SteeringInput> = lock.withLock {
        stoppedSteering.also { stoppedSteering = emptyList() }
    }
    @Volatile
    private var paused = false
    @Volatile
    private var pausedInterrupt = false
    private var pendingCompact: CompactRequest? = null

    data class CompactRequest(
        val keepRecentMessages: Int? = null,
        val compressModelConfig: AgentModelClient.ModelConfig? = null,
    )

    fun cancel() {
        lock.withLock {
            if (!cancelled) stoppedSteering = steeringMessages.toList()
            cancelled = true
            acceptingSteering = false
            steeringMessages.clear()
            pendingCompact = null
            paused = false
            pauseCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /**
     * Queue a follow-up instruction for the next model request; it still belongs to the current logical turn.
     *
     * A streaming model request is interrupted (the registered EventSource is cancelled); body text already
     * written is kept by AgentLoop, which injects steering immediately after. The tool batch still runs
     * to completion and is not cancelled here.
     */
    fun steer(text: String): Boolean = steer(SteeringInput(text))

    fun steer(input: SteeringInput): Boolean {
        val interrupt = enqueueSteering(input) ?: return false
        interruptSteering(interrupt)
        if (!interrupt) {
            // The stream is already torn down while paused. Wake the loop after enqueueing so appended
            // steering from Binder/voice entry points does not sit queued while suspended.
            resume()
        }
        return true
    }

    /** Session records its accepted event before interrupting the provider/allowing completion. */
    internal fun enqueueSteering(input: SteeringInput): Boolean? = lock.withLock {
        if (input.text.isBlank() || cancelled || !acceptingSteering) return null
        steeringMessages.addLast(input.copy(text = input.text.trim()))
        !paused
    }

    internal fun interruptSteering(interrupt: Boolean) {
        if (interrupt) interruptCurrentRequest()
    }

    /**
     * Queue compaction to run at the safe boundary after the response and tool batch complete; never
     * interrupts the current SSE. The tool batch is not cancelled. A compaction request unpauses and
     * keeps running at the safe boundary.
     */
    fun requestCompact(
        keepRecentMessages: Int? = null,
        compressModelConfig: AgentModelClient.ModelConfig? = null,
    ): Boolean {
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            pendingCompact = CompactRequest(
                keepRecentMessages = keepRecentMessages,
                compressModelConfig = compressModelConfig,
            )
            paused = false
            pauseCondition.signalAll()
        }
        return true
    }

    val hasPendingCompact: Boolean
        get() = lock.withLock { pendingCompact != null }

    fun takePendingCompact(): CompactRequest? =
        lock.withLock {
            val request = pendingCompact
            pendingCompact = null
            request
        }

    /**
     * Cancel only the resources held by the current request (SSE); do not mark the whole run cancelled.
     * Steering issued while paused does not interrupt again: the generation stream was already torn
     * down in pause().
     */
    private fun interruptCurrentRequest() {
        val interruptibles = resources.filter { it.interruptible }
        if (paused && interruptibles.isNotEmpty()) {
            pausedInterrupt = true
        }
        interruptibles.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /** Consume one at a time by default, so a later follow-up cannot overtake an earlier model turn. */
    fun pollSteeringMessage(): String? = pollSteeringInput()?.text

    fun pollSteeringInput(): SteeringInput? = lock.withLock { steeringMessages.pollFirst() }

    /**
     * Atomically consume the last steering entry before natural completion; an empty queue permanently
     * seals this run's intake. This keeps the Service from reporting a follow-up as accepted after the
     * loop has already returned.
     */
    fun pollSteeringOrSeal(): String? = pollSteeringInputOrSeal()?.text

    fun pollSteeringInputOrSeal(): SteeringInput? =
        lock.withLock {
            steeringMessages.pollFirst()?.let { return it }
            if (pendingCompact != null) return null
            acceptingSteering = false
            null
        }

    val hasPendingSteering: Boolean
        get() = lock.withLock { steeringMessages.isNotEmpty() }

    /**
     * Pause execution: later [throwIfCancelled] calls block until [resume] or [cancel].
     * Call on worker-thread checkpoints; never blocks the calling thread.
     */
    val isPaused: Boolean
        get() = paused

    val hasPausedInterrupt: Boolean
        get() = pausedInterrupt

    fun consumePausedInterrupt(): Boolean = lock.withLock {
        val value = pausedInterrupt
        pausedInterrupt = false
        value
    }

    fun pause() {
        lock.withLock { paused = true }
        interruptCurrentRequest()
    }

    /**
     * Resume execution: wake worker threads blocked in [throwIfCancelled] so they continue.
     */
    fun resume() {
        lock.withLock {
            paused = false
            pauseCondition.signalAll()
        }
    }

    /**
     * Checkpoint: throw if cancelled; if paused, block until resumed or cancelled.
     * Call every round/step of the agent loop: pause is resumable, cancel is terminal.
     */
    fun throwIfCancelled() {
        lock.withLock {
            while (paused && !cancelled) {
                try {
                    pauseCondition.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelled = true
                }
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    fun awaitRetryDelay(delayMs: Long) {
        throwIfCancelled()
        val cancelledLatch = CountDownLatch(1)
        val binding = register { cancelledLatch.countDown() }
        try {
            cancelledLatch.await(delayMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            binding.close()
        }
        throwIfCancelled()
    }

    /**
     * @param interruptible true means steering may interrupt it (the current model SSE).
     * Long-lived resources such as the tool executor must stay false, or a follow-up would
     * shut down the whole tool batch with it.
     */
    fun register(interruptible: Boolean = false, cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel, interruptible)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(
        private val cancelBlock: () -> Unit,
        val interruptible: Boolean,
    ) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancelBlock()
        }
    }
}

internal class AgentRunCancelledException(
    val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
    val reasoningContent: String = "",
) : RuntimeException("Agent run cancelled")
