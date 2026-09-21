package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleSessionControllerTest {

    @Test
    fun openFailureLeavesNoSessionBehind() {
        // A supervisor without setsid guarantees process startup failure, without depending on whether BusyBox is installed on the host.
        val controller = ConsoleSessionController(
            logger = NoopLogger,
            processSupervisor = ShellProcessSupervisor(
                allowTreeFallback = false,
                setsidCommand = "eta-test-missing-setsid",
            ),
        )
        try {
            val result = controller.open(
                environment = TerminalEnvironment.ANDROID,
                cols = 80,
                rows = 24,
                onOutput = { _, _ -> },
                onExit = { },
            )

            assertTrue("$result", result is ConsoleSessionController.OpenResult.Failed)
            assertEquals(
                "PROCESS_START_FAILED",
                (result as ConsoleSessionController.OpenResult.Failed).code,
            )
            assertTrue(controller.listSessions().isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedOpenDoesNotConsumeSessionLimit() {
        val controller = ConsoleSessionController(
            logger = NoopLogger,
            processSupervisor = ShellProcessSupervisor(
                allowTreeFallback = false,
                setsidCommand = "eta-test-missing-setsid",
            ),
        )
        try {
            repeat(8) {
                val result = controller.open(
                    environment = TerminalEnvironment.ANDROID,
                    cols = 80,
                    rows = 24,
                    onOutput = { _, _ -> },
                    onExit = { },
                )
                // The failure path must clean up after itself before the limit check; repeated failures must not degrade into SESSION_LIMIT_REACHED.
                assertEquals(
                    "PROCESS_START_FAILED",
                    (result as ConsoleSessionController.OpenResult.Failed).code,
                )
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun closeIsIdempotentAndRejectsFurtherSessions() {
        val supervisor = ShellProcessSupervisor(
            allowTreeFallback = false,
            setsidCommand = "eta-test-missing-setsid",
        )
        val controller = ConsoleSessionController(logger = NoopLogger, processSupervisor = supervisor)

        controller.close()
        controller.close()

        assertTrue(controller.listSessions().isEmpty())
        assertFalse(controller.sessionAlive("missing"))
        // After close, the supervisor is already closing, so any open no longer starts a process.
        val result = controller.open(
            environment = TerminalEnvironment.ANDROID,
            cols = 80,
            rows = 24,
            onOutput = { _, _ -> },
            onExit = { },
        )
        assertTrue(result is ConsoleSessionController.OpenResult.Failed)
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
