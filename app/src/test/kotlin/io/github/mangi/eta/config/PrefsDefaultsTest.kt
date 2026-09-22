package io.github.mangi.eta.config

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsDefaultsTest {
    @Test
    fun defaultsMatchRecommendedInitialSettings() {
        assertEquals(
            mapOf(
                Prefs.Keys.AGENT_TERMINAL_TOOLS to true,
                Prefs.Keys.AGENT_BROWSER_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
                Prefs.Keys.AGENT_THINKING_ENABLED to true,
                Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED to false,
                Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED to false,
                Prefs.Keys.HAPTIC_TOUCH_FEEDBACK to true,
                Prefs.Keys.HAPTIC_MESSAGE_GENERATION to true,
            ),
            Prefs.Keys.BOOLEAN_DEFAULTS,
        )
    }

    @Test
    fun undeclaredSwitchKeysAreTreatedAsOn() {
        assertEquals(true, Prefs.isEnabled("no_such_switch_key"))
    }
}
