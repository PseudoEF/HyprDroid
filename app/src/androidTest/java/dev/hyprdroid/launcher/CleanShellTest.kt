package dev.hyprdroid.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanShellTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell get() = ShellRepository(context)
    private val workspace get() = WorkspaceRepository(context)
    private fun await(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun settings() {
        compose.onNodeWithContentDescription("Меню оболочки").performClick()
        compose.onNodeWithText("Рабочий стол и панели").performClick()
        await("shell-appearance-panel")
    }
    private fun applySettings() {
        compose.onNodeWithTag("apply-shell-appearance").performScrollTo().performClick()
        gone("shell-appearance-panel")
    }
    private fun seed(): String = runBlocking {
        dismissBackgroundPixelLauncherAnr()
        workspace.settings.first().workspaces.forEach { w -> w.items.forEach { workspace.removeItem(w.id, it.id) } }
        workspace.selectWorkspace(0)
        val app = LauncherCatalog(context).load().first().componentId
        workspace.setPinned(0, app, true)
        shell.setFavorite(app, true)
        shell.setSidebarItem(WorkspaceItem.AppShortcut(app), true)
        shell.setPresentation(ShellPresentation())
        app
    }

    @Test fun cleanClassicPreviewRollbackDockAndReverseGesturesPreservePins() {
        val app = seed()
        val before = runBlocking { workspace.settings.first().workspaces }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await("clean-workspace-0")
            compose.onNodeWithTag("workspace-app-$app").assertDoesNotExist()
            captureShellScreen("v06-clean")
            settings()
            compose.onNodeWithTag("desktop-mode-CLASSIC").performClick()
            assertEquals(DesktopMode.CLEAN, runBlocking { shell.settings.first().presentation.desktopMode })
            compose.waitForIdle()
            captureShellScreen("v06-preview")
            applySettings()
            await("workspace-app-$app")
            settings()
            compose.onNodeWithTag("desktop-mode-CLEAN").performClick()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            gone("shell-appearance-panel")
            compose.onNodeWithTag("workspace-app-$app").assertExists()
            assertEquals(DesktopMode.CLASSIC, runBlocking { shell.settings.first().presentation.desktopMode })
            settings()
            compose.onNodeWithTag("desktop-mode-CLEAN").performClick()
            compose.onNodeWithTag("dock-visibility-HIDDEN").performScrollTo().performClick()
            applySettings()
            await("clean-workspace-0")
            gone("favorite-dock")
            compose.waitForIdle()
            captureShellScreen("v06-clean-hidden-dock")
            compose.onNodeWithContentDescription("Меню оболочки").performClick()
            compose.onNodeWithText("Все приложения", substring = false).performClick()
            await("app-drawer")
            compose.onNodeWithTag("drawer-dismiss-header").performTouchInput { swipe(center, center + Offset(0f, 300f)) }
            gone("app-drawer")
            compose.onNodeWithTag("clean-workspace-0").performTouchInput { swipe(Offset(width * .4f, height * .25f), Offset(width * .4f, height * .75f)) }
            await("control-center")
            compose.onNodeWithTag("control-dismiss-header").performTouchInput { swipe(center, center - Offset(0f, height * .65f)) }
            gone("control-center")
            settings()
            compose.onNodeWithTag("dock-visibility-AUTO").performScrollTo().performClick()
            applySettings()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(4_000)
            gone("favorite-dock")
            compose.onNodeWithTag("clean-workspace-0").performTouchInput { click(Offset(width * .3f, height * .3f)) }
            await("favorite-dock")
            scenario.recreate()
            await("clean-workspace-0")
            assertEquals(before, runBlocking { workspace.settings.first().workspaces })
            assertTrue(app in runBlocking { shell.settings.first().favorites })
            assertTrue(runBlocking { shell.settings.first().sidebar.any { it.id == "app:$app" } })
        } finally { scenario.close() }
    }

    @Test fun sidebarHasNoHandleOpensInsideSafeBandAndPreservesHiddenModuleItems() {
        val app = seed()
        runBlocking { shell.setPanel(PanelSide.RIGHT, shell.settings.first().panels.right.copy(scope = PanelScope.HOME, insetDp = 48)) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await("clean-workspace-0")
            compose.onNodeWithTag("sidebar-handle").assertDoesNotExist()
            val reserved = 60 * context.resources.displayMetrics.density
            compose.onNodeWithTag("home-panel-root").performTouchInput {
                val start = Offset(width - reserved, height * .5f)
                swipe(start, start - Offset(220f, 0f))
            }
            await("sidebar")
            compose.onNodeWithTag("sidebar-system-status").assertExists()
            compose.onNodeWithTag("sidebar-quick-launch").assertExists()
            val width = compose.onNodeWithTag("sidebar").fetchSemanticsNode().boundsInRoot.width
            val total = compose.onNodeWithTag("sidebar-overlay").fetchSemanticsNode().boundsInRoot.width
            assertTrue("Sidebar should occupy about 80% of a phone", width / total in 0.70f..0.85f)
            compose.waitForIdle()
            captureShellScreen("v06-sidebar")
            compose.onNodeWithTag("sidebar-scrim").performTouchInput { click(Offset(width * .04f, height * .5f)) }
            gone("sidebar")
            settings()
            compose.onNodeWithTag("sidebar-module-QUICK_LAUNCH").performScrollTo().performClick()
            applySettings()
            compose.onNodeWithContentDescription("Открыть Sidebar").performClick()
            await("sidebar")
            compose.onNodeWithTag("sidebar-quick-launch").assertDoesNotExist()
            compose.onNodeWithTag("sidebar-system-status").assertExists()
            assertTrue(runBlocking { shell.settings.first().sidebar.any { it.id == "app:$app" } })
            compose.onNodeWithContentDescription("Закрыть Sidebar").performClick()
            gone("sidebar")
        } finally { scenario.close() }
    }
}
