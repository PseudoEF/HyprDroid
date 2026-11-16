package dev.hyprdroid.launcher

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UniversalPanelsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = ShellRepository(context)
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun await(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun seed() = runBlocking {
        dismissBackgroundPixelLauncherAnr()
        repository.setOverlay(OverlaySettings())
        repository.setPanel(PanelSide.LEFT, PanelsSettings().left)
        repository.setPanel(PanelSide.RIGHT, PanelsSettings().right)
        repository.setPresentation(ShellPresentation())
    }
    private fun reset() = runBlocking {
        repository.setOverlay(OverlaySettings())
        context.stopService(Intent(context, OverlaySidebarService::class.java))
        repository.setPanel(PanelSide.LEFT, PanelsSettings().left)
        repository.setPanel(PanelSide.RIGHT, PanelsSettings().right)
    }
    private fun edge(side: PanelSide, y: Int = device.displayHeight / 2) {
        val distance = (90 * context.resources.displayMetrics.density).toInt()
        val start = if (side == PanelSide.LEFT) 2 else device.displayWidth - 2
        device.swipe(start, y, if (side == PanelSide.LEFT) distance else device.displayWidth - distance, y, 25)
    }
    private fun excludes(scenario: ActivityScenario<MainActivity>, x: Int, y: Int): Boolean {
        var found = false
        scenario.onActivity { activity ->
            fun inspect(view: android.view.View) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                if (view.systemGestureExclusionRects.any { android.graphics.Rect(it).apply { offset(location[0], location[1]) }.contains(x, y) }) found = true
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
            }
            inspect(activity.window.decorView)
        }
        return found
    }
    @Test fun homeEdgesWorkInCleanAndClassicAndBackRestoresNormalGestures() {
        seed()
        try {
            for (mode in DesktopMode.entries) {
                runBlocking { repository.setPresentation(ShellPresentation(mode)) }
                val scenario = ActivityScenario.launch(MainActivity::class.java)
                try {
                    await("home-marker-LEFT")
                    compose.waitUntil(5_000) {
                        var focused = false
                        scenario.onActivity { focused = it.hasWindowFocus() }
                        focused
                    }
                    device.waitForIdle()
                    compose.waitUntil(5_000) { excludes(scenario, 2, device.displayHeight / 2) }
                    edge(PanelSide.LEFT)
                    await("left-panel")
                    compose.onNodeWithTag("panel-module-CLOCK").assertExists()
                    compose.onNodeWithTag("panel-module-DATE").assertExists()
                    compose.onNodeWithTag("panel-module-BATTERY").assertExists()
                    compose.onNodeWithTag("sidebar").assertDoesNotExist()
                    captureShellScreen("universal-left-${mode.name.lowercase()}")
                    device.pressBack()
                    gone("left-panel")
                    device.waitForIdle()
                    compose.waitUntil(5_000) { excludes(scenario, device.displayWidth - 2, device.displayHeight / 2) }
                    edge(PanelSide.RIGHT)
                    await("sidebar")
                    compose.onNodeWithTag("sidebar-quick-launch").assertExists()
                    device.pressBack()
                    gone("sidebar")
                    compose.onNodeWithContentDescription("Открыть app drawer").performClick()
                    await("app-drawer")
                    compose.onNodeWithTag("home-marker-LEFT").assertDoesNotExist()
                    compose.onNodeWithTag("home-marker-RIGHT").assertDoesNotExist()
                    assertFalse(excludes(scenario, 2, device.displayHeight / 2))
                    assertFalse(excludes(scenario, device.displayWidth - 2, device.displayHeight / 2))
                    device.pressBack()
                    gone("app-drawer")
                    compose.onNodeWithContentDescription("Меню оболочки").performClick()
                    compose.onNodeWithText("Левая панель", substring = false).performClick()
                    await("left-panel")
                    compose.onNodeWithContentDescription("Закрыть левую панель").performClick()
                    gone("left-panel")
                } finally { scenario.close() }
            }
        } finally { reset() }
    }
    @Test fun panelEditorSavesIndependentGeometryScopeAndModuleOrder() {
        seed()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await("home-pager")
            compose.onNodeWithContentDescription("Меню оболочки").performClick()
            compose.onNodeWithText("Настройки панелей", substring = false).performClick()
            await("overlay-settings")
            compose.onNodeWithTag("panel-scope-APPS").performScrollTo().performClick()
            compose.onNodeWithTag("panel-slider-Ширина сенсорной зоны").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(42f) }
            compose.onNodeWithTag("panel-slider-Ширина маркера").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
            compose.onNodeWithTag("panel-visibility-HIDDEN").performScrollTo().performClick()
            compose.onNodeWithTag("module-down-CLOCK").performScrollTo().performClick()
            compose.onNodeWithTag("save-panel").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { repository.settings.first().panels.left.touchWidthDp == 42 } }
            val saved = runBlocking { repository.settings.first() }
            assertEquals(7, saved.panels.left.markerWidthDp)
            assertEquals(PanelScope.APPS, saved.panels.left.scope)
            assertEquals(MarkerVisibility.HIDDEN, saved.panels.left.visibility)
            assertEquals(listOf("DATE", "CLOCK", "BATTERY"), saved.panels.left.modules)
            assertEquals(PanelsSettings().right, saved.panels.right)
            compose.onNodeWithTag("panel-tab-RIGHT").performScrollTo().performClick()
            compose.onNodeWithTag("panel-scope-EVERYWHERE").performScrollTo().performClick()
            compose.onNodeWithTag("save-panel").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { repository.settings.first().panels.right.scope == PanelScope.EVERYWHERE } }
            captureShellScreen("universal-panel-settings")
            device.pressBack()
            gone("overlay-settings")
            compose.onNodeWithTag("home-marker-LEFT").assertDoesNotExist()
            compose.onNodeWithTag("home-marker-RIGHT").assertExists()
            scenario.recreate()
            await("home-marker-RIGHT")
            assertEquals(saved.panels.left, runBlocking { repository.settings.first().panels.left })
        } finally { reset(); scenario.close() }
    }
    @Test fun overlayScopesAreIndependentAndBothSidesCanOpenOverAnotherApk() {
        seed()
        fun permission(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode")).use { it.readBytes() }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            permission("allow")
            runBlocking {
                repository.setPanel(PanelSide.LEFT, PanelsSettings().left.copy(scope = PanelScope.APPS))
                repository.setOverlay(OverlaySettings(enabled = true))
            }
            scenario.onActivity { assertTrue(OverlaySession.start(it)) }
            context.startActivity(Intent().setClassName(context.packageName + ".test", OverlayTestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val left = device.wait(Until.findObject(By.desc("Открыть глобальную левую панель")), 8_000)
            assertNotNull(left)
            assertFalse(device.hasObject(By.desc("Открыть глобальную Sidebar"))) // Right is Home-only.
            edge(PanelSide.LEFT, left!!.visibleBounds.centerY())
            assertTrue(device.wait(Until.hasObject(By.desc("Закрыть левую панель")), 5_000))
            captureShellScreen("universal-left-overlay")
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("Открыть глобальную левую панель")), 5_000))
            runBlocking {
                repository.setPanel(PanelSide.LEFT, PanelsSettings().left.copy(scope = PanelScope.EVERYWHERE, visibility = MarkerVisibility.HIDDEN,
                    modules = listOf("BATTERY", "DATE", "CLOCK")))
                repository.setPanel(PanelSide.RIGHT, PanelsSettings().right.copy(scope = PanelScope.EVERYWHERE))
            }
            assertTrue(device.wait(Until.hasObject(By.desc("Открыть глобальную Sidebar")), 5_000))
            val hidden = device.findObject(By.desc("Открыть глобальную левую панель"))
            assertNotNull(hidden)
            hidden.click() // Hidden marker retains the explicitly enabled touch zone.
            assertTrue(device.wait(Until.hasObject(By.desc("Закрыть левую панель")), 5_000))
            val battery = device.wait(Until.findObject(By.text("Батарея")), 5_000)
            val date = device.findObject(By.text("Дата"))
            val clock = device.findObject(By.text("Часы"))
            assertTrue(battery!!.visibleBounds.top < date.visibleBounds.top)
            assertTrue(date.visibleBounds.top < clock.visibleBounds.top)
            device.pressBack()
            val right = device.wait(Until.findObject(By.desc("Открыть глобальную Sidebar")), 5_000)
            edge(PanelSide.RIGHT, right!!.visibleBounds.centerY())
            assertTrue(device.wait(Until.hasObject(By.text("Quick Launch")), 5_000))
            device.pressBack()
        } finally { reset(); scenario.close(); permission("default") }
    }

    @Test fun edgeTouchCancellationVerticalMovementAndTapFallbackWorkOnBothSides() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                PanelSide.entries.forEach { side ->
                    var opened = 0
                    val target = OverlayEdgeView(activity, side, PanelSettings()) { opened++ }
                    val start = 200f
                    val distance = 100 * activity.resources.displayMetrics.density * if (side == PanelSide.LEFT) 1 else -1
                    fun event(action: Int, x: Float, y: Float) {
                        val time = android.os.SystemClock.uptimeMillis()
                        val touch = android.view.MotionEvent.obtain(time, time, action, x, y, 0)
                        try { target.onTouchEvent(touch) } finally { touch.recycle() }
                    }
                    event(android.view.MotionEvent.ACTION_DOWN, start, start)
                    event(android.view.MotionEvent.ACTION_MOVE, start + distance, start)
                    event(android.view.MotionEvent.ACTION_CANCEL, start + distance, start)
                    assertEquals(0, opened)
                    event(android.view.MotionEvent.ACTION_DOWN, start, start)
                    event(android.view.MotionEvent.ACTION_MOVE, start, start + kotlin.math.abs(distance))
                    event(android.view.MotionEvent.ACTION_UP, start, start + kotlin.math.abs(distance))
                    assertEquals(0, opened)
                    event(android.view.MotionEvent.ACTION_DOWN, start, start)
                    event(android.view.MotionEvent.ACTION_UP, start, start)
                    assertEquals(1, opened)
                }
            }
        } finally { scenario.close() }
    }

    @Test fun rootBackAndPanelCloseKeepMarkerDimensionsInCleanAndClassic() {
        seed()
        try {
            for (mode in DesktopMode.entries) {
                runBlocking { repository.setPresentation(ShellPresentation(mode)) }
                val scenario = ActivityScenario.launch(MainActivity::class.java)
                try {
                    await("home-marker-LEFT")
                    device.waitForIdle()
                    val before = PanelSide.entries.associateWith { side -> compose.onNodeWithTag("home-marker-${side.name}").fetchSemanticsNode().boundsInRoot }
                    fun unchanged() {
                        await("home-marker-LEFT")
                        scenario.onActivity { assertFalse("Back must not finish HOME", it.isFinishing) }
                        PanelSide.entries.forEach { side ->
                            val actual = compose.onNodeWithTag("home-marker-${side.name}").fetchSemanticsNode().boundsInRoot
                            assertEquals(before.getValue(side).height, actual.height, 1f)
                            assertEquals(before.getValue(side).width, actual.width, 1f)
                        }
                    }
                    // Outside panel activation rectangles these are real system Back gestures.
                    edge(PanelSide.LEFT, (device.displayHeight * .2f).toInt())
                    unchanged()
                    edge(PanelSide.RIGHT, (device.displayHeight * .2f).toInt())
                    unchanged()
                    device.pressBack()
                    unchanged()
                    compose.onNodeWithTag("sidebar-open").performClick()
                    await("sidebar")
                    device.pressBack()
                    gone("sidebar")
                    unchanged()
                    captureShellScreen("home-after-back-${mode.name.lowercase()}")
                } finally { scenario.close() }
            }
        } finally { reset() }
    }
}
