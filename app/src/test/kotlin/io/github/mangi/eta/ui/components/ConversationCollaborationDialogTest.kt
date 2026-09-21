package io.github.mangi.eta.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationCollaborationDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsRolesAndToggleWithoutObsoleteReadOnlyParagraph() {
        val enabled = mutableStateOf(false)
        val visible = mutableStateOf(true)
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                ConversationCollaborationDialog(visible.value, enabled.value,
                    { enabled.value = it }, { visible.value = false })
            }
        }
        compose.onNodeWithText("In-session collaboration").assertExists()
        listOf("Execution Agent 1", "Execution Agent 2", "Execution Agent 3", "Review/Summary Agent").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("Tap to select model · Long-press to adjust thinking depth", substring = true).assertExists()
        compose.onNodeWithText("Auto delegation").performClick()
        compose.runOnIdle { assertTrue(enabled.value) }
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertFalse(visible.value) }
    }
    @Test fun emptySlotClickOpensModelPickerAndLongPressDoesNotTriggerClick() {
        val slot = 0
        val saved = io.github.mangi.eta.agent.delegation.SubAgentPreferences.selection(slot)
        val savedEffort = io.github.mangi.eta.agent.delegation.SubAgentPreferences.reasoning(slot)
        try {
            io.github.mangi.eta.agent.delegation.SubAgentPreferences.save(slot,
                io.github.mangi.eta.agent.model.ModelFeatureSelection(true, "", ""))
            compose.setContent {
                MiuixTheme(colors = lightColorScheme()) {
                    ConversationCollaborationDialog(true, true, {}, {})
                }
            }
            compose.onNodeWithText("Execution Agent 1").performTouchInput { longClick() }
            compose.onNodeWithText("Select Execution Agent 1 model").assertDoesNotExist()
            compose.onNodeWithText("Execution Agent 1").performTouchInput { click() }
            compose.onNodeWithText("Select Execution Agent 1 model").assertExists()
            compose.onNodeWithText("None").performClick()
            compose.onNodeWithText("In-session collaboration").assertExists()
            compose.onNodeWithText("Execution Agent 1").performTouchInput { longClick() }
            compose.onNodeWithText("Only affects this sub-agent slot").assertDoesNotExist()
            compose.onNodeWithText("Select Execution Agent 1 model").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(io.github.mangi.eta.agent.delegation.SubAgentPreferences.selection(slot).modelId.isBlank())
            }
        } finally {
            io.github.mangi.eta.agent.delegation.SubAgentPreferences.save(slot, saved)
            io.github.mangi.eta.agent.delegation.SubAgentPreferences.saveReasoning(slot, savedEffort)
        }
    }

    @Test fun runningTaskDisablesAllControlsAndClosesOpenPicker() {
        val running = mutableStateOf(false)
        var changes = 0
        var dismissals = 0
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                ConversationCollaborationDialog(true, true, { changes++ }, { dismissals++ }, taskRunning = running.value)
            }
        }
        compose.onNodeWithText("Execution Agent 1").performClick()
        compose.onNodeWithText("Select Execution Agent 1 model").assertExists()
        compose.runOnIdle { running.value = true }
        compose.onNodeWithText("Select Execution Agent 1 model").assertDoesNotExist()
        compose.onNodeWithText("Done").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Execution Agent 1").assertIsNotEnabled()
        compose.onNodeWithText("Execution Agent 1").performTouchInput { click(); longClick() }
        compose.runOnIdle { org.junit.Assert.assertEquals("disabled model row must not dismiss", 0, dismissals) }
        compose.onNodeWithText("Auto delegation").performTouchInput { click() }
        compose.runOnIdle { org.junit.Assert.assertEquals("disabled toggle must not dismiss", 0, dismissals) }
        compose.onNodeWithText("Done").performTouchInput { click() }
        compose.runOnIdle {
            org.junit.Assert.assertEquals(0, changes)
            org.junit.Assert.assertEquals(0, dismissals)
            running.value = false
        }
        compose.onNodeWithText("Execution Agent 1").assertIsEnabled()
        compose.onNodeWithText("Done").assertIsEnabled().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, dismissals) }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun lockedDialogKeepsDisabledDoneButtonInsideSmallViewport() {
        var dismissals = 0
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                ConversationCollaborationDialog(true, true, {}, { dismissals++ }, taskRunning = true)
            }
        }
        compose.onNodeWithText("Done").assertIsDisplayed().assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { org.junit.Assert.assertEquals(0, dismissals) }
    }

}
