package dev.hyprdroid.launcher.developer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.dismissBackgroundPixelLauncherAnr
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeveloperWorkspaceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun firstWindowOpensFromTheWorkspaceMenu() {
        dismissBackgroundPixelLauncherAnr()
        val classic = InstrumentationRegistry.getArguments().getString("workspaceMode") == "classic"
        ActivityScenario.launch(DeveloperActivity::class.java).use {
            await("clean-workspace-0")
            if (classic) {
                menu("Рабочий стол и панели")
                await("shell-appearance-panel")
                compose.onNodeWithTag("desktop-mode-CLASSIC").performClick()
                compose.onNodeWithTag("apply-shell-appearance").performScrollTo().performClick()
                compose.waitUntil(15_000) { !exists("shell-appearance-panel") && !exists("clean-workspace-0") }
            }
            menu("Оконный тайл")
            await("module-picker-tile-1")
        }
    }

    private fun menu(label: String) {
        compose.onNodeWithContentDescription("Меню оболочки").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText(label).assertIsEnabled() }.isSuccess }
        compose.onNodeWithText(label).performClick()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun await(tag: String) = compose.waitUntil(15_000) { exists(tag) }
}
