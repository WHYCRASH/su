package io.github.mangi.eta.agent.runtime

import java.util.UUID

/** Records the checkpoint author's process, for diagnostics only; restore state is decided by runtime reconciliation. */
internal object AgentRuntimeProcessIdentity {
    val id: String = UUID.randomUUID().toString()
}
