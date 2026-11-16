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
class SidebarInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell get() = ShellRepository(context)
    private val workspace get() = WorkspaceRepository(context)
    private fun await(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun seed(mode: DesktopMode): List<LauncherApp> = runBlocking {
        dismissBackgroundPixelLauncherAnr()
        workspace.settings.first().workspaces.forEach { w -> w.items.forEach { workspace.removeItem(w.id, it.id) } }
        workspace.selectWorkspace(0)
        workspace.setAppearance(0, WorkspaceAppearance())
        shell.settings.first().sidebar.forEach { shell.removeSidebarItem(it.id) }
        shell.setPresentation(ShellPresentation(mode))
        LauncherCatalog(context).load().take(3)
    }

    @Test fun activationCancelPagerAndSystemBackWorkInCleanAndClassic() {
        for (mode in DesktopMode.entries) {
            seed(mode)
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                await("home-pager")
                var inset = 0
                scenario.onActivity {
                    inset = ViewCompat.getRootWindowInsets(it.window.decorView)?.getInsets(
                        WindowInsetsCompat.Type.systemGestures() or WindowInsetsCompat.Type.mandatorySystemGestures())?.right ?: 0
                }
                val reserved = inset + 52 * context.resources.displayMetrics.density
                val pager = compose.onNodeWithTag("home-pager")
                pager.performTouchInput {
                    val start = Offset(width - reserved, height * .5f)
                    down(start); moveTo(start - Offset(200f, 0f), 350); cancel()
                }
                compose.waitForIdle()
                compose.onNodeWithTag("sidebar").assertDoesNotExist()
                assertEquals(0, runBlocking { workspace.settings.first().selectedWorkspace })
                pager.performTouchInput { swipe(Offset(width * .65f, height * .6f), Offset(width * .05f, height * .6f), 350) }
                compose.waitUntil(5_000) { runBlocking { workspace.settings.first().selectedWorkspace == 1 } }
                compose.onNodeWithContentDescription("Рабочее пространство 1").performClick()
                compose.waitUntil(5_000) { runBlocking { workspace.settings.first().selectedWorkspace == 0 } }
                pager.performTouchInput {
                    val start = Offset(width - reserved, height * .5f)
                    swipe(start, start - Offset(220f, 8f), 350)
                }
                await("sidebar")
                assertEquals(0, runBlocking { workspace.settings.first().selectedWorkspace })
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                // A native right-edge Android Back gesture, not an injected Compose swipe.
                device.swipe(device.displayWidth - 2, device.displayHeight / 2, (device.displayWidth * .65f).toInt(), device.displayHeight / 2, 30)
                gone("sidebar")
                compose.onNodeWithContentDescription("Открыть app drawer").performClick()
                await("app-drawer")
                compose.onNodeWithTag("app-drawer").performTouchInput { swipe(Offset(width * .8f, height * .5f), Offset(width * .4f, height * .5f)) }
                compose.onNodeWithTag("sidebar").assertDoesNotExist()
                compose.onNodeWithTag("app-drawer").assertExists()
                compose.onNodeWithContentDescription("Закрыть приложения").performClick()
                gone("app-drawer")
                compose.onNodeWithTag("workspace-canvas-0").performTouchInput { swipe(Offset(width * .4f, height * .2f), Offset(width * .4f, height * .75f)) }
                await("control-center")
                compose.onNodeWithTag("control-center").performTouchInput { swipe(Offset(width * .8f, height * .7f), Offset(width * .4f, height * .7f)) }
                compose.onNodeWithTag("sidebar").assertDoesNotExist()
                device.pressBack()
                gone("control-center")
            } finally { scenario.close() }
        }
    }

    @Test fun compactGridDragMenuAndRecreationKeepAndroidShortcutIdentity() {
        val apps = seed(DesktopMode.CLEAN)
        val items = listOf(WorkspaceItem.AppShortcut(apps[0].componentId),
            WorkspaceItem.AppShortcut(apps[1].componentId, "sidebar-fixture"), WorkspaceItem.AppShortcut(apps[2].componentId))
        runBlocking { items.forEach { shell.setSidebarItem(it, true) } }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await("sidebar-open")
            compose.onNodeWithTag("sidebar-open").performClick()
            await("sidebar")
            val panel = compose.onNodeWithTag("sidebar").fetchSemanticsNode().boundsInRoot
            val screen = compose.onNodeWithTag("sidebar-overlay").fetchSemanticsNode().boundsInRoot
            assertTrue("A small quick-launch collection must wrap its contents", panel.height < screen.height * .65f)
            compose.waitForIdle()
            captureShellScreen("v061-sidebar-compact")
            compose.onNodeWithText("Редактировать", substring = false).performClick()
            val grid = compose.onNodeWithTag("sidebar-items")
            val origin = grid.fetchSemanticsNode().boundsInRoot.topLeft
            val start = compose.onNodeWithTag("sidebar-item-${items[0].id}").fetchSemanticsNode().boundsInRoot.center - origin
            val end = compose.onNodeWithTag("sidebar-item-${items[2].id}").fetchSemanticsNode().boundsInRoot.center - origin
            grid.performTouchInput {
                down(start); advanceEventTime(900)
                for (step in 1..12) moveTo(start + (end - start) * (step / 12f), 30)
                up()
            }
            val expected = listOf(items[1], items[2], items[0])
            compose.waitUntil(5_000) { runBlocking { shell.settings.first().sidebar == expected } }
            compose.onNodeWithTag("sidebar-item-${items[0].id}").performClick()
            compose.onNodeWithText("Выше", substring = false).performClick()
            val alternative = listOf(items[1], items[0], items[2])
            compose.waitUntil(5_000) { runBlocking { shell.settings.first().sidebar == alternative } }
            scenario.recreate()
            await("sidebar")
            assertEquals(alternative, runBlocking { shell.settings.first().sidebar })
            compose.onNodeWithText("Редактировать", substring = false).performClick()
            compose.onNodeWithTag("sidebar-item-${items[1].id}").performClick()
            compose.onNodeWithText("Убрать из Sidebar", substring = false).performClick()
            compose.waitUntil(5_000) { runBlocking { shell.settings.first().sidebar.none { it.id == items[1].id } } }
            assertEquals(2, runBlocking { shell.settings.first().sidebar.size })
        } finally { scenario.close() }
    }

    @Test fun longGridCapsHeightAndScrollsWithoutLosingItems() {
        val apps = seed(DesktopMode.CLASSIC)
        val items = List(36) { WorkspaceItem.AppShortcut(apps[0].componentId, "long-fixture-$it") }
        runBlocking { items.forEach { shell.setSidebarItem(it, true) } }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await("sidebar-open")
            compose.onNodeWithTag("sidebar-open").performClick()
            await("sidebar")
            val panel = compose.onNodeWithTag("sidebar").fetchSemanticsNode().boundsInRoot
            val screen = compose.onNodeWithTag("sidebar-overlay").fetchSemanticsNode().boundsInRoot
            assertTrue("Sidebar must stay within its height cap", panel.height <= screen.height * .81f)
            compose.onNodeWithTag("sidebar-items").performScrollToIndex(37)
            compose.onNodeWithTag("sidebar-item-${items.last().id}").assertExists()
            assertEquals(items, runBlocking { shell.settings.first().sidebar })
            compose.waitForIdle()
            captureShellScreen("v061-sidebar-scroll")
        } finally { scenario.close() }
    }
}
