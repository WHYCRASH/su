package io.github.mangi.eta.agent.device

/** A root process timeout does not prove that input already sent to the system never ran. */
internal object ShellActionOutcomePolicy {
    enum class Outcome {
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
    }

    fun classify(exitCode: Int): Outcome = when (exitCode) {
        0 -> Outcome.SUCCEEDED
        PROCESS_TIMEOUT_EXIT_CODE -> Outcome.TIMED_OUT
        else -> Outcome.FAILED
    }

    const val PROCESS_TIMEOUT_EXIT_CODE = -2
}
