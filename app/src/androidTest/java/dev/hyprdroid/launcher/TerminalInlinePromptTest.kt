package dev.hyprdroid.launcher

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hyprdroid.launcher.modules.CommandEvent
import dev.hyprdroid.launcher.modules.CommandPanelModule
import dev.hyprdroid.launcher.modules.CommandPanelState
import dev.hyprdroid.launcher.ui.ShellTheme
import dev.hyprdroid.launcher.workspaceengine.WindowId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalInlinePromptTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val panel = CommandPanelState(WindowId("inline-prompt"))
    private val logTag = "module-command-log-inline-prompt"
    private val promptTag = "module-command-prompt-inline-prompt"
    private val suggestionsTag = "module-command-suggestions-inline-prompt"

    @Test
    fun promptFollowsOutputAndSuggestionsFollowPromptInNarrowTile() {
        output("Последняя строка")
        panel.updateInput("hypr ins")

        showTerminal {
            compose.waitUntil(10_000) { compose.onNodeWithTag(suggestionsTag).isDisplayed() }
            val output = compose.onNodeWithText("Последняя строка").fetchSemanticsNode().boundsInRoot
            val prompt = bounds(promptTag)
            val suggestions = bounds(suggestionsTag)
            assertTrue(prompt.top >= output.bottom - 1f)
            assertTrue("Gap before prompt: ${prompt.top - output.bottom}", prompt.top - output.bottom < output.height / 2)
            assertTrue(suggestions.top >= prompt.bottom - 1f)
            assertTrue(prompt.bottom < bounds(logTag).bottom)
        }
    }

    @Test
    fun incomingOutputDoesNotPullManualScrollBackToThePrompt() {
        output(*Array(80) { "Строка журнала $it" })
        showTerminal {
            compose.onNodeWithTag(promptTag).assertIsDisplayed()
            compose.onNodeWithTag(logTag).performTouchInput { swipeDown() }
            compose.onNodeWithText("К концу").assertIsDisplayed()
            compose.runOnIdle { output("Новый вывод") }
            assertTrue(!compose.onNodeWithText("Новый вывод").isDisplayed())
            assertTrue(!compose.onNodeWithTag(promptTag).isDisplayed())
            compose.onNodeWithText("К концу").performClick()
            compose.onNodeWithTag(promptTag).assertIsDisplayed()
            compose.onNodeWithText("Новый вывод").assertIsDisplayed()
        }
    }

    private fun output(vararg lines: String) = runBlocking {
        panel.play(lines.map { CommandEvent.Line(it) })
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun showTerminal(test: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    ShellTheme {
                        Box(Modifier.size(width = 220.dp, height = 250.dp)) {
                            CommandPanelModule.Content(panel, Modifier)
                        }
                    }
                }
            }
            compose.waitForIdle()
            test()
        }
    }
}
