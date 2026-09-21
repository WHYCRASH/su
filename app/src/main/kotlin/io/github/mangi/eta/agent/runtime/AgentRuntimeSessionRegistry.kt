package io.github.mangi.eta.agent.runtime

/** Concurrently live runtime runs. Replacement only happens on the same runId and never cancels other sessions. */
internal class AgentRuntimeSessionRegistry {
    private val lock = Any()
    private val sessions = linkedMapOf<String, AgentRuntimeSession>()

    fun put(session: AgentRuntimeSession): AgentRuntimeSession? = synchronized(lock) {
        sessions.put(session.runId, session)
    }

    fun get(runId: String): AgentRuntimeSession? = synchronized(lock) {
        sessions[runId]
    }

    fun contains(session: AgentRuntimeSession): Boolean = synchronized(lock) {
        sessions[session.runId] === session
    }

    fun remove(session: AgentRuntimeSession): Boolean = synchronized(lock) {
        if (sessions[session.runId] !== session) return false
        sessions.remove(session.runId)
        true
    }

    fun isEmpty(): Boolean = synchronized(lock) { sessions.isEmpty() }

    fun snapshot(): List<AgentRuntimeSession> = synchronized(lock) { sessions.values.toList() }

    fun activeRunIds(): List<String> = synchronized(lock) {
        sessions.values.filterNot { it.isTerminal }.map { it.runId }
    }

    fun anyNonTerminal(): Boolean = synchronized(lock) {
        sessions.values.any { !it.isTerminal }
    }

    fun cancelAll(reason: String) {
        snapshot().forEach { session -> session.cancel(reason) }
    }
}
