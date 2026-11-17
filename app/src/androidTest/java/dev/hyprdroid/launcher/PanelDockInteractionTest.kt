package dev.hyprdroid.launcher

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.overlay.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PanelDockInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private fun awaitTag(tag: String) = compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = compose.waitUntil(8_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    @Test fun dockAddsDragsRestoresAndRemovesWithoutLaunchingDuringDrag() {
        dismissBackgroundPixelLauncherAnr()
        val repo = ShellRepository(context)
        val original = runBlocking { repo.settings.first() }
        val apps = LauncherCatalog(context).load().take(3).map { it.componentId }
        assertEquals(3, apps.size)
        runBlocking {
            original.favorites.forEach { repo.setFavorite(it, false) }
            repo.setPresentation(original.presentation.copy(desktopMode = DesktopMode.CLEAN, dockVisibility = DockVisibility.ALWAYS, drawerDock = ShellButtonVisibility.SHOWN))
            apps.take(2).forEach { repo.setFavorite(it, true) }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitTag("dock-grid")
            compose.onNodeWithContentDescription("Меню оболочки").performClick()
            compose.onNodeWithText("Настроить dock").performScrollTo().performClick()
            awaitTag("dock-editor")
            compose.onNodeWithTag("dock-add").performScrollTo().performClick()
            compose.onNodeWithTag("dock-editor-content").performScrollToNode(hasTestTag("dock-pin-${apps[2]}"))
            compose.onNodeWithTag("dock-pin-${apps[2]}").performClick()
            compose.waitUntil(5_000) { runBlocking { repo.settings.first().dockComponents.size == 3 } }
            compose.onNodeWithContentDescription("Закрыть настройки dock").performClick()
            gone("dock-editor")
            val grid = compose.onNodeWithTag("dock-grid")
            val origin = grid.fetchSemanticsNode().boundsInRoot.topLeft
            val start = compose.onNodeWithTag("dock-app-${apps[0]}").fetchSemanticsNode().boundsInRoot.center - origin
            val end = compose.onNodeWithTag("dock-app-${apps[2]}").fetchSemanticsNode().boundsInRoot.center - origin
            grid.performTouchInput {
                down(start); advanceEventTime(900)
                for (step in 1..12) moveTo(start + (end - start) * (step / 12f), 30)
                up()
            }
            val expected = listOf(apps[1], apps[2], apps[0])
            compose.waitUntil(8_000) { runBlocking { repo.settings.first().dockComponents == expected } }
            assertEquals(context.packageName, device.currentPackageName)
            // A cancelled drag keeps the last committed order.
            grid.performTouchInput { down(Offset(26f, 26f)); advanceEventTime(900); moveBy(Offset(40f, 0f)); cancel() }
            assertEquals(expected, runBlocking { repo.settings.first().dockComponents })
            scenario.recreate(); awaitTag("dock-grid")
            assertEquals(expected, runBlocking { repo.settings.first().dockComponents })
            captureShellScreen("dock-reordered")
            compose.onNodeWithContentDescription("Меню оболочки").performClick()
            compose.onNodeWithText("Настроить dock").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Убрать из dock: ${apps[2]}").performClick()
            compose.waitUntil(5_000) { runBlocking { apps[2] !in repo.settings.first().favorites } }
        } finally {
            scenario.close()
            runBlocking {
                repo.settings.first().favorites.forEach { repo.setFavorite(it, false) }
                original.dockComponents.forEach { repo.setFavorite(it, true) }
                repo.setPresentation(original.presentation)
            }
        }
    }

    @Test fun swipeOnlyIgnoresTapsAndShortPullsWithOptionalTapInOverlayAndHome() {
        dismissBackgroundPixelLauncherAnr()
        val repo = ShellRepository(context)
        val original = runBlocking { repo.settings.first() }
        val density = context.resources.displayMetrics.density
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        runBlocking {
            repo.setPanel(PanelSide.RIGHT, original.panels.right.copy(scope = PanelScope.EVERYWHERE, insetDp = 24,
                touchWidthDp = 24, activation = PanelActivation.SWIPE_ONLY, swipeThresholdDp = 64))
            repo.setOverlay(original.overlay.copy(enabled = true))
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitTag("home-pager")
            scenario.onActivity { assertTrue(OverlaySession.start(it)) }
            context.startActivity(Intent().setClassName(context.packageName + ".test", OverlayTestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            fun marker() = device.wait(Until.findObject(By.desc("Открыть глобальную Sidebar")), 8_000)!!.visibleBounds
            var bounds = marker()
            device.click(bounds.centerX(), bounds.centerY()); SystemClock.sleep(350)
            assertFalse(device.hasObject(By.desc("Закрыть Sidebar")))
            device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX() - (32 * density).toInt(), bounds.centerY(), 20)
            assertFalse(device.hasObject(By.desc("Закрыть Sidebar")))
            device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX() - (100 * density).toInt(), bounds.centerY(), 30)
            assertTrue(device.wait(Until.hasObject(By.desc("Закрыть Sidebar")), 8_000))
            device.pressBack(); assertTrue(device.wait(Until.gone(By.desc("Закрыть Sidebar")), 8_000))
            runBlocking { repo.setPanel(PanelSide.RIGHT, repo.settings.first().panels.right.copy(activation = PanelActivation.TAP_SWIPE)) }
            SystemClock.sleep(500); bounds = marker()
            device.click(bounds.centerX(), bounds.centerY())
            assertTrue(device.wait(Until.hasObject(By.desc("Закрыть Sidebar")), 8_000))
            device.pressBack(); assertTrue(device.wait(Until.gone(By.desc("Закрыть Sidebar")), 8_000))
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runBlocking { repo.setPanel(PanelSide.RIGHT, repo.settings.first().panels.right.copy(activation = PanelActivation.SWIPE_ONLY)) }
            for (mode in DesktopMode.entries) {
                runBlocking { repo.setPresentation(original.presentation.copy(desktopMode = mode)) }
                awaitTag("home-marker-RIGHT")
                compose.onNodeWithTag("home-marker-RIGHT").performTouchInput { click(center) }
                compose.onNodeWithTag("sidebar").assertDoesNotExist()
                compose.onNodeWithTag("home-marker-RIGHT").performTouchInput { swipe(center, center - Offset(100 * density, 0f), 350) }
                awaitTag("sidebar")
                device.pressBack(); gone("sidebar")
            }
        } finally {
            context.stopService(Intent(context, OverlaySidebarService::class.java))
            scenario.close()
            runBlocking { repo.setPanel(PanelSide.RIGHT, original.panels.right); repo.setOverlay(original.overlay); repo.setPresentation(original.presentation) }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }
}
