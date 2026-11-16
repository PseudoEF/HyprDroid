package dev.hyprdroid.launcher

import android.appwidget.AppWidgetManager
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
import dev.hyprdroid.launcher.widgets.PanelWidgetHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class AndroidWidgetsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val repository get() = ShellRepository(context)
    private val session get() = PanelWidgetHost.get(context)
    private val testPackage get() = context.packageName + ".test"
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.bufferedReader().readText() }
    private fun await(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = compose.waitUntil(8_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun state() = runBlocking { repository.settings.first() }
    private fun openSettings() {
        compose.onNodeWithContentDescription("Меню оболочки").performClick()
        compose.onNodeWithText("Настройки панелей", substring = false).performClick()
        await("overlay-settings")
    }
    private fun choose(configured: Boolean, approveBind: Boolean = true) {
        device.waitForIdle()
        val count = state().panels.left.widgets.size
        compose.onNodeWithTag("add-android-widget").performScrollTo().performClick()
        await("widget-picker")
        val name = if (configured) "HyprDroid configured fixture" else "HyprDroid fixture widget"
        compose.onNodeWithTag("widget-search").performTextReplacement(name)
        val provider = if (configured) ConfiguredFixtureWidgetProvider::class.java.name else FixtureWidgetProvider::class.java.name
        val choice = "widget-provider-$testPackage/$provider"
        await(choice)
        compose.onNodeWithTag(choice).performClick()
        compose.waitUntil(8_000) {
            val saved = state()
            saved.pendingWidget?.stage?.let { it != WidgetBindingStage.ALLOCATED } == true || saved.panels.left.widgets.size > count
        }
        val pending = state().pendingWidget ?: return
        if (pending.stage == WidgetBindingStage.BIND) {
            captureShellScreen("widget-bind-consent")
            if (!approveBind) {
                val negative = device.wait(Until.findObject(By.res("android", "button2")), 8_000)
                assertNotNull(negative); negative!!.click(); return
            }
            val positive = device.wait(Until.findObject(By.res("android", "button1")), 8_000)
                ?: device.findObject(By.text(Pattern.compile("(?i)(create|allow|add)")))
            assertNotNull("Android's binding consent must be available", positive)
            positive!!.click()
        }
    }
    private fun leftPanel() {
        compose.onNodeWithContentDescription("Меню оболочки").performClick()
        compose.onNodeWithText("Левая панель", substring = false).performClick()
        await("left-panel")
    }
    private fun waitText(text: String) {
        val shown = device.wait(Until.hasObject(By.text(text)), 15_000)
        if (!shown) {
            captureShellScreen("widget-text-missing")
            val hierarchy = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(hierarchy)
            android.util.Log.e("WidgetTestWindow", hierarchy.toString("UTF-8"))
        }
        assertTrue("RemoteViews should show: $text", shown)
    }
    @Test fun bindConfigureCancelMultipleResizeReorderUpdatesAndOverlayUseRealRemoteViews() {
        dismissBackgroundPixelLauncherAnr()
        val before = state()
        assumeTrue("Use an isolated emulator, never replace existing widget data", before.panels.left.widgets.isEmpty() && before.panels.right.widgets.isEmpty() && before.pendingWidget == null)
        shell("appwidget revokebind --package ${context.packageName} --user 0")
        shell("pm enable $testPackage/${ConfiguredFixtureWidgetProvider::class.java.name}")
        // Launch the separate fixture APK once so its provider is not in the stopped state.
        context.startActivity(Intent().setClassName(testPackage, OverlayTestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runBlocking {
            repository.setOverlay(OverlaySettings())
            repository.setPanel(PanelSide.LEFT, PanelsSettings().left)
            repository.setPanel(PanelSide.RIGHT, PanelsSettings().right)
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await("home-pager"); openSettings()
            choose(false, approveBind = false)
            compose.waitUntil(8_000) { state().pendingWidget == null }
            assertTrue(state().panels.left.widgets.isEmpty())
            assertTrue(session.host.appWidgetIds.isEmpty())
            choose(true)
            waitText("Fixture widget configuration")
            val cancelled = state().pendingWidget!!.widget.appWidgetId
            device.findObject(By.text("Cancel widget")).click()
            compose.waitUntil(8_000) { state().pendingWidget == null }
            assertFalse(session.host.appWidgetIds.contains(cancelled))
            assertTrue(state().panels.left.widgets.isEmpty())

            choose(true)
            waitText("Fixture widget configuration")
            device.findObject(By.text("Save widget")).click()
            compose.waitUntil(8_000) { state().panels.left.widgets.size == 1 && state().pendingWidget == null }
            val first = state().panels.left.widgets.single()
            choose(false)
            compose.waitUntil(8_000) { state().panels.left.widgets.size == 2 && state().pendingWidget == null }
            val second = state().panels.left.widgets.last()
            assertNotEquals(first.appWidgetId, second.appWidgetId)
            compose.onNodeWithTag("widget-height-${first.appWidgetId}").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(260f) }
            compose.onNodeWithTag("widget-width-${first.appWidgetId}").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
            compose.onNodeWithTag("module-up-${second.moduleId}").performScrollTo().performClick()
            compose.onNodeWithTag("save-panel").performScrollTo().performClick()
            compose.waitUntil(5_000) { state().panels.left.widgets.first().heightDp == 260 }
            assertEquals(listOf(second.moduleId, first.moduleId), state().panels.left.modules.takeLast(2))
            device.pressBack(); gone("overlay-settings")
            leftPanel()
            compose.onNodeWithTag("sidebar-items").performScrollToIndex(3)
            waitText("Fixture #${second.appWidgetId} · 0")
            val text = device.findObject(By.text("Fixture #${second.appWidgetId} · 0"))
            text.parent.findObject(By.res(testPackage, "fixture_click")).click()
            waitText("Fixture #${second.appWidgetId} · 1")
            captureShellScreen("widgets-home")
            assertTrue(session.visibleConsumers > 0)
            device.pressBack(); gone("left-panel")
            compose.waitUntil(5_000) { session.visibleConsumers == 0 }
            shell("am broadcast -n $testPackage/${FixtureWidgetProvider::class.java.name} -a dev.hyprdroid.test.WIDGET_PULSE --ei appWidgetId ${second.appWidgetId}")
            leftPanel(); compose.onNodeWithTag("sidebar-items").performScrollToIndex(3)
            waitText("Fixture #${second.appWidgetId} · 2")
            scenario.recreate(); await("left-panel")
            compose.onNodeWithTag("sidebar-items").performScrollToIndex(3)
            waitText("Fixture #${second.appWidgetId} · 2")
            assertEquals(260, state().panels.left.widgets.first().heightDp)
            assertEquals(60, state().panels.left.widgets.first().widthPercent)

            // Disabling a provider makes its binding unavailable; placement must remain removable data.
            val disabled = shell("pm disable --user 0 $testPackage/${ConfiguredFixtureWidgetProvider::class.java.name}")
            assertTrue("Provider component must actually be disabled: $disabled", disabled.contains("disabled"))
            compose.onNodeWithTag("sidebar-items").performScrollToIndex(4)
            compose.waitUntil(8_000) { session.info(first.appWidgetId) == null }
            compose.onNodeWithText("Виджет недоступен", substring = true).assertExists()
            assertTrue(state().panels.left.widgets.any { it.appWidgetId == first.appWidgetId })
            shell("pm enable $testPackage/${ConfiguredFixtureWidgetProvider::class.java.name}")
            device.pressBack(); gone("left-panel")

            runBlocking {
                repository.setPanel(PanelSide.LEFT, state().panels.left.copy(scope = PanelScope.EVERYWHERE))
                repository.setOverlay(state().overlay.copy(enabled = true))
            }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            scenario.onActivity { assertTrue(OverlaySession.start(it)) }
            context.startActivity(Intent().setClassName(testPackage, OverlayTestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val marker = device.wait(Until.findObject(By.desc("Открыть глобальную левую панель")), 8_000)
            assertNotNull(marker); marker!!.click()
            assertTrue(device.wait(Until.hasObject(By.desc("Закрыть левую панель")), 8_000))
            compose.onNodeWithTag("sidebar-items").performScrollToIndex(3)
            waitText("Fixture #${second.appWidgetId} · 2")
            captureShellScreen("widgets-global-overlay")
            device.pressBack()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("home-pager")
            runBlocking {
                repository.setPanel(PanelSide.LEFT, PanelsSettings().left)
                repository.setOverlay(OverlaySettings())
                session.cleanup(repository)
            }
            assertFalse(session.host.appWidgetIds.contains(second.appWidgetId))
        } finally {
            shell("pm enable $testPackage/${ConfiguredFixtureWidgetProvider::class.java.name}")
            runBlocking {
                repository.settings.first().pendingWidget?.let { repository.cancelPendingWidget(it.widget.appWidgetId) }
                repository.setPanel(PanelSide.LEFT, before.panels.left)
                repository.setPanel(PanelSide.RIGHT, before.panels.right)
                repository.setOverlay(before.overlay)
                session.cleanup(repository)
            }
            context.stopService(Intent(context, OverlaySidebarService::class.java))
            scenario.close()
            shell("appwidget revokebind --package ${context.packageName} --user 0")
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }
}
