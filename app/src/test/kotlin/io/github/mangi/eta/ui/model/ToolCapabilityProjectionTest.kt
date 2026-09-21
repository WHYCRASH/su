package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCapabilityProjectionTest {
    private fun card(id: String) = ToolItemUi(id, id, id)

    @Test fun currentDeviceRetainsPartialCapabilitiesAndHidesRootOnlyTools() {
        val groups = listOf(ToolGroupUi("device", "Device", listOf(card("terminal"), card("wifi_credentials"), card("observe_screen"))))
        assertEquals(listOf("terminal", "observe_screen"),
            projectToolGroups(groups, showAll = false, rootGranted = false).single().tools.map { it.id })
        assertEquals(groups, projectToolGroups(groups, showAll = true, rootGranted = false))
        assertEquals(groups, projectToolGroups(groups, showAll = false, rootGranted = true))
    }

    @Test fun viewingAllDoesNotMutateSourceOrChangeToolOrder() {
        val groups = listOf(ToolGroupUi("root", "Enhancements", listOf(card("wifi_credentials"))))
        assertTrue(projectToolGroups(groups, showAll = false, rootGranted = false).isEmpty())
        assertEquals(groups, projectToolGroups(groups, showAll = true, rootGranted = false))
        assertTrue(projectToolGroups(groups, showAll = false, rootGranted = false).isEmpty())
    }

    @Test fun browserCardsReferToTheRealBrowserTool() {
        listOf("browser_use", "browser_read", "browser_interact", "browser_screenshot").forEach { id ->
            assertEquals("browser_use", actualToolName(id))
            assertEquals(AgentToolRequirements.find("browser_use"), toolCardRequirement(id))
        }
    }

    @Test fun ordinaryPermissionsDoNotHideDiscoverableTools() {
        assertTrue(visibleOnCurrentDevice("observe_screen", rootGranted = false))
        assertTrue(visibleOnCurrentDevice("search_notification_history", rootGranted = false))
        assertEquals(RootRequirement.PARTIAL, toolCardRequirement("terminal").rootRequirement)
        assertFalse(visibleOnCurrentDevice("get_health_summary", rootGranted = false))
    }

    @Test fun missingOrdinaryPermissionYieldsNoCardAction() {
        val capabilities = AgentToolCapabilities(rootAvailable = false, notificationsAllowed = false)
        assertEquals(null, toolCardAction("recent_notifications", capabilities))
        assertEquals(AgentToolsAction.OpenEnhancements, toolCardAction("terminal", capabilities))
        assertEquals(AgentToolsAction.OpenBrowser, toolCardAction("browser_read", capabilities))
        assertEquals(AgentToolsAction.OpenEnhancements,
            toolCardAction("wifi_credentials", capabilities))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownCardCannotSilentlyAcquireCapabilityDefaults() {
        toolCardRequirement("unknown")
    }
}
