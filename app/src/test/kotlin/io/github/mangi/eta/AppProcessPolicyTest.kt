package io.github.mangi.eta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `onlyMainProcessInitializesFullRuntimeDependencies`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.mangi.eta", "io.github.mangi.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.mangi.eta:voice", "io.github.mangi.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.mangi.eta:voice_session", "io.github.mangi.eta"))
    }
}
