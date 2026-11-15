package dev.hyprdroid.launcher

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorSidebarTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun dragPersistsAndSidebarEditsClosesAndRestores() {
        dismissBackgroundPixelLauncherAnr()
        val apps = LauncherCatalog(context).load().take(3)
        assertEquals(3, apps.size)
        val repo = WorkspaceRepository(context)
        val shell = ShellRepository(context)
        runBlocking {
            repo.settings.first().workspaces.forEach { w -> w.items.forEach { repo.removeItem(w.id, it.id) } }
            repo.selectWorkspace(0)
            repo.setAppearance(0, WorkspaceAppearance())
            apps.forEach { repo.setPinned(0, it.componentId, true) }
            shell.settings.first().sidebar.forEach { shell.removeSidebarItem(it.id) }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitTag("workspace-app-${apps[0].componentId}")
            compose.onNodeWithContentDescription("Меню оболочки").performClick()
            compose.onNodeWithText("Редактировать пространство").performClick()
            val canvas = compose.onNodeWithTag("workspace-canvas-0")
            val origin = canvas.fetchSemanticsNode().boundsInRoot.topLeft
            val start = compose.onNodeWithTag("workspace-app-${apps[0].componentId}").fetchSemanticsNode().boundsInRoot.center - origin
            val end = compose.onNodeWithTag("workspace-app-${apps[2].componentId}").fetchSemanticsNode().boundsInRoot.center - origin
            canvas.performTouchInput {
                down(start); advanceEventTime(900)
                for (step in 1..12) moveTo(start + (end - start) * (step / 12f), 30)
                up()
            }
            val expected = listOf(apps[1], apps[2], apps[0]).map { "app:${it.componentId}" }
            compose.waitUntil(5_000) { runBlocking { repo.settings.first().workspaces[0].items.map { it.id } == expected } }
            captureShellScreen("v05-editor")
            compose.onNodeWithContentDescription("Готово, завершить редактирование").performClick()
            scenario.recreate()
            awaitTag("workspace-canvas-0")
            assertEquals(expected, runBlocking { repo.settings.first().workspaces[0].items.map { it.id } })
            // Opening begins well inside the display, away from Android's Back edge.
            compose.onNodeWithTag("sidebar-handle").performTouchInput { swipe(center, center - Offset(200f, 0f)) }
            awaitTag("sidebar")
            compose.onNodeWithText("Панель пуста", substring = true).assertExists()
            compose.onNodeWithText("Редактировать", substring = false).performClick()
            compose.onNodeWithText("Добавить ярлык").performClick()
            for (app in apps.take(2)) compose.onNodeWithTag("sidebar-choice-app:${app.componentId}").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { shell.settings.first().sidebar.size == 2 } }
            compose.onNodeWithText("К панели").performClick()
            val second = "app:${apps[1].componentId}"
            compose.onNodeWithTag("sidebar-earlier-$second").performClick()
            compose.waitUntil(5_000) { runBlocking { shell.settings.first().sidebar.firstOrNull()?.id == second } }
            compose.onNodeWithContentDescription("Закрыть Sidebar").performClick()
            compose.onNodeWithTag("sidebar-handle").performClick()
            awaitTag("sidebar")
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("sidebar").fetchSemanticsNodes().isEmpty() }
            scenario.recreate()
            awaitTag("sidebar-handle")
            compose.onNodeWithTag("sidebar-handle").performClick()
            awaitTag("sidebar")
            compose.onNodeWithTag("sidebar-item-$second").assertExists()
            captureShellScreen("v05-sidebar")
            compose.onNodeWithText("Редактировать", substring = false).performClick()
            for (app in apps.take(2)) compose.onNodeWithTag("sidebar-remove-app:${app.componentId}").performClick()
            compose.waitUntil(5_000) { runBlocking { shell.settings.first().sidebar.isEmpty() } }
            compose.onNodeWithText("Панель пуста", substring = true).assertExists()
            compose.onNodeWithTag("sidebar-header").performTouchInput { swipe(center, center + Offset(250f, 0f)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("sidebar").fetchSemanticsNodes().isEmpty() }
            // Horizontal paging still works outside editor drags and the handle.
            compose.onNodeWithTag("workspace-canvas-0").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { runBlocking { repo.settings.first().selectedWorkspace == 1 } }
        } finally { scenario.close() }
    }

    @Test fun longCanvasAutoScrollsAndCancelledDragDoesNotCommit() {
        dismissBackgroundPixelLauncherAnr()
        val icon = LauncherCatalog(context).load().first().icon
        val apps = List(80) { LauncherApp("example.app$it/.Main", "App $it", icon) }
        val initial = Workspace(0, apps.map { WorkspaceItem.AppShortcut(it.componentId) }, WorkspaceAppearance(gridLayout = false))
        var saved by mutableStateOf(initial)
        var drops = 0
        lateinit var grid: LazyGridState
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.setContent { ShellTheme {
                grid = rememberLazyGridState()
                WorkspaceCanvas(saved, apps.associateBy { app -> app.componentId }, true, true, {}, {}, {}, {}, {}, { emptyList() },
                    Modifier.fillMaxSize(), onDrop = { id, before -> drops++; saved = saved.copy(items = placeItemBefore(saved.items, id, before)) }, gridState = grid)
            } } }
            awaitTag("workspace-app-${apps.first().componentId}")
            val canvas = compose.onNodeWithTag("workspace-canvas-0")
            val node = compose.onNodeWithTag("workspace-app-${apps.first().componentId}")
            val start = node.fetchSemanticsNode().boundsInRoot.center - canvas.fetchSemanticsNode().boundsInRoot.topLeft
            compose.mainClock.autoAdvance = false
            canvas.performTouchInput { down(start) }
            compose.mainClock.advanceTimeBy(1_000)
            canvas.performTouchInput { moveTo(Offset(start.x, height - 60f), 500) }
            // Leave the real pointer stationary while frame-driven autoscroll advances.
            compose.mainClock.advanceTimeBy(2_500)
            compose.runOnIdle { assertTrue("Auto-scroll must advance the viewport", grid.firstVisibleItemIndex > 0) }
            canvas.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals(0, drops)
            assertEquals(initial, saved)
            // Later rows were reached without releasing/restarting the drag.
        } finally { scenario.close() }
    }
}
