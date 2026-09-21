package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Screen and shared browser are mutually exclusive per "current message / this run".
 *
 * Once a conversation starts operating the screen, it holds it until the end of this run; other conversations queue before truly executing screen tools,
 * waiting for the whole task to finish before starting, instead of interleaving per single tap / observe.
 */
internal object ForegroundExclusiveGate {
    private val lock = ReentrantLock()
    private val ownerChanged = lock.newCondition()
    private val waiters = ArrayDeque<String>()
    private var ownerRunId: String? = null

    fun shouldSerialize(toolName: String): Boolean {
        val name = toolName.trim()
        return name.equals("browser_use", ignoreCase = true) ||
            AgentOverlayVisibilityPolicy.isForegroundOperationTool(name)
    }

    fun acquire(runId: String, isClosed: () -> Boolean = { false }): Boolean {
        val id = runId.trim()
        if (id.isEmpty()) return !isClosed()
        lock.lockInterruptibly()
        try {
            if (isClosed()) return false
            if (ownerRunId == id) return true
            if (!waiters.contains(id)) waiters.addLast(id)
            while (true) {
                if (isClosed()) {
                    waiters.remove(id)
                    return false
                }
                if (ownerRunId == id) return true
                if (ownerRunId == null && waiters.firstOrNull() == id) {
                    waiters.removeFirst()
                    ownerRunId = id
                    return true
                }
                ownerChanged.await()
            }
        } catch (_: InterruptedException) {
            waiters.remove(id)
            Thread.currentThread().interrupt()
            return false
        } finally {
            lock.unlock()
        }
    }

    fun release(runId: String) {
        val id = runId.trim()
        if (id.isEmpty()) return
        lock.withLock {
            waiters.remove(id)
            if (ownerRunId == id) ownerRunId = null
            ownerChanged.signalAll()
        }
    }

    internal fun resetForTests() {
        lock.withLock {
            waiters.clear()
            ownerRunId = null
            ownerChanged.signalAll()
        }
    }

    internal fun ownerForTests(): String? = lock.withLock { ownerRunId }

    internal fun waitersForTests(): List<String> = lock.withLock { waiters.toList() }
}
