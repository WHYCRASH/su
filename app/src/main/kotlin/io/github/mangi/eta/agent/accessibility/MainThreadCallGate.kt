package io.github.mangi.eta.agent.accessibility

import java.util.concurrent.atomic.AtomicReference

/** Keeps a UI action that never started from running late after the sync bridge already timed out. */
internal class MainThreadCallGate {
    enum class State {
        PENDING,
        RUNNING,
        FINISHED,
        CANCELLED,
    }

    private val state = AtomicReference(State.PENDING)

    fun tryStart(): Boolean = state.compareAndSet(State.PENDING, State.RUNNING)

    fun finish() {
        state.compareAndSet(State.RUNNING, State.FINISHED)
    }

    fun cancelIfPending(): Boolean = state.compareAndSet(State.PENDING, State.CANCELLED)

    fun currentState(): State = state.get()
}
