package dev.hyprdroid.launcher.developer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.hyprdroid.launcher.dismissBackgroundPixelLauncherAnr
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.modules.BuiltInModules
import dev.hyprdroid.launcher.modules.CommandPanelModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                selectClassic()
            }
            menu("Оконный тайл")
            await("module-picker-tile-1")
        }
    }

    @Test fun createsTerminalAndMultipleTilesThroughTheRealWorkspace() {
        dismissBackgroundPixelLauncherAnr()
        ActivityScenario.launch(DeveloperActivity::class.java).use {
            await("clean-workspace-0")
            selectClassic()
            menu("Терминал")
            await("module-command-input-tile-1")
            closeFocused()
            compose.waitUntil(15_000) { !exists("tiling-surface") }
            menu("Оконный тайл")
            await("module-picker-tile-1")
            compose.onNodeWithTag("tiling-module-pick-hyprdroid.apps").performClick()
            await("module-apps-grid-tile-1")

            // Verify every shipped module can be selected through the window's UI.
            for (module in BuiltInModules.registry.modules) {
                compose.onNodeWithTag("tiling-module-menu-tile-1").performClick()
                compose.onNodeWithTag("tiling-module-option-${module.id}").performClick()
                compose.onNode(hasTestTag("tiling-module-menu-tile-1") and hasText(module.title)).assertExists()
            }
            compose.onNodeWithTag("tiling-add-window").performClick()
            awaitWindows(2)
            closeFocused()
            awaitWindows(1)
            closeFocused()
            awaitWindows(0)

            menu("Терминал")
            await("module-command-input-tile-1")
            compose.onNodeWithTag("tiling-add-window").performClick()
            awaitWindows(2)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.waitUntil(15_000) {
                runBlocking {
                    val store = WorkspaceEngineStore(context)
                    store.load()["0"]?.windowsById()?.size == 2 && store.isOpen("0")
                }
            }
        }
    }

    @Test fun restoresTilesAfterProcessStop() {
        ActivityScenario.launch(DeveloperActivity::class.java).use {
            await("module-command-input-tile-1")
            awaitWindows(2)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val ids = runBlocking { WorkspaceEngineStore(context).load().getValue("0").windowsById().values.map { it.moduleId } }
            assertEquals(1, ids.count { it == CommandPanelModule.id })
        }
    }

    @Test fun developerCommandRunsInTheRestoredTerminal() {
        ActivityScenario.launch(DeveloperActivity::class.java).use { scenario ->
            await("module-command-input-tile-1")
            // Drive the real field without Compose's idle barrier while the caret animates.
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val field = checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000))
            field.click()
            device.executeShellCommand("input text hypr%sdev%slist")
            assertTrue("Command text did not reach the field", device.wait(Until.hasObject(By.text("hypr dev list")), 10_000))
            device.pressEnter()
            assertTrue("Terminal did not echo the submitted command",
                device.wait(Until.hasObject(By.text("› hypr dev list")), 10_000))
            scenario.onActivity { activity ->
                androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            val printed = device.wait(Until.hasObject(By.textContains("Нет локальных пакетов.")), 15_000)
            if (!printed) {
                val hierarchy = java.io.ByteArrayOutputStream()
                device.dumpWindowHierarchy(hierarchy)
                android.util.Log.e("DeveloperWorkspaceTest", hierarchy.toString(Charsets.UTF_8.name()))
            }
            assertTrue("Developer command output was not displayed", printed)
        }
    }

    private fun selectClassic() {
        menu("Рабочий стол и панели")
        await("shell-appearance-panel")
        compose.onNodeWithTag("desktop-mode-CLASSIC").performClick()
        compose.onNodeWithTag("apply-shell-appearance").performScrollTo().performClick()
        compose.waitUntil(15_000) { !exists("shell-appearance-panel") && !exists("clean-workspace-0") }
    }

    private fun closeFocused() {
        compose.onNode(tagPrefix("tiling-close-window-"), useUnmergedTree = true).performClick()
    }

    private fun awaitWindows(count: Int) {
        compose.waitUntil(15_000) { compose.onAllNodes(tagPrefix("tiling-window-"), useUnmergedTree = true)
            .fetchSemanticsNodes().size == count }
    }

    private fun tagPrefix(prefix: String) = SemanticsMatcher("tag starting with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun menu(label: String) {
        compose.onNodeWithContentDescription("Меню оболочки").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText(label).assertIsEnabled() }.isSuccess }
        compose.onNodeWithText(label).performClick()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun await(tag: String) = compose.waitUntil(15_000) { exists(tag) }
}
