package dev.hyprdroid.launcher

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.hyprdroid.launcher.data.LauncherCatalog
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureWorkspaceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun blankGesturesOrderMoveAppearanceAndRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        dismissBackgroundPixelLauncherAnr()
        val context = instrumentation.targetContext
        val apps = LauncherCatalog(context).load()
        assertTrue(apps.size >= 3)
        val a = apps[0].componentId
        val b = apps[1].componentId
        val c = apps[2].componentId
        val repo = WorkspaceRepository(context)
        runBlocking {
            ShellRepository(context).setPresentation(ShellPresentation(DesktopMode.CLASSIC))
            repo.settings.first().workspaces.forEach { workspace ->
                workspace.items.forEach { repo.removeItem(workspace.id, it.id) }
                repo.setAppearance(workspace.id, WorkspaceAppearance())
            }
            repo.selectWorkspace(0)
            for (app in listOf(a, b, c)) repo.setPinned(0, app, true)
            repo.setPinned(1, b, true)
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun order(id: Int) = runBlocking { repo.settings.first().workspaces[id].items.map { it.id } }
        fun openMenu(text: String) {
            compose.onNodeWithContentDescription("Меню оболочки").performClick()
            compose.onNodeWithText(text).performClick()
        }
        try {
            awaitTag("workspace-app-$a")
            compose.onNodeWithTag("workspace-canvas-0").performTouchInput { swipe(Offset(width * .7f, height * .85f), Offset(width * .7f, height * .3f)) }
            awaitTag("app-drawer")
            // Start in the empty lower area, not in the search field near the top.
            compose.onNodeWithTag("app-drawer").performTouchInput { swipe(Offset(width * .7f, height * .65f), Offset(width * .7f, height * .9f)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("app-drawer").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("control-center").assertDoesNotExist()
            compose.onNodeWithContentDescription("Все приложения").performClick()
            awaitTag("drawer-dismiss-header")
            compose.onNodeWithTag("drawer-dismiss-header").performTouchInput { swipe(center, center + Offset(0f, 300f)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("app-drawer").fetchSemanticsNodes().isEmpty() }

            compose.onNodeWithTag("workspace-canvas-0").performTouchInput { swipe(Offset(width * .7f, height * .3f), Offset(width * .7f, height * .8f)) }
            awaitTag("control-center")
            compose.onNodeWithText("Батарея", substring = true).assertExists()
            screenshot("v04-control-center")
            // Stay clear of the system-navigation edge excluded by shell gesture policy.
            compose.onNodeWithTag("control-center").performTouchInput { swipe(Offset(width * .7f, height * .85f), Offset(width * .7f, height * .35f)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("control-center").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("workspace-canvas-0").performTouchInput { swipe(Offset(width * .7f, height * .3f), Offset(width * .7f, height * .8f)) }
            awaitTag("control-dismiss-header")
            compose.onNodeWithTag("control-dismiss-header").performTouchInput { swipe(center, center - Offset(0f, height * .65f)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("control-center").fetchSemanticsNodes().isEmpty() }

            compose.onNodeWithTag("workspace-canvas-0").performTouchInput { swipe(Offset(width * .6f, height * .5f), Offset(width * .1f, height * .5f), 350) }
            compose.waitUntil(10_000) { runBlocking { repo.settings.first().selectedWorkspace == 1 } }
            compose.onNodeWithTag("workspace-canvas-1").performTouchInput { swipe(Offset(width * .1f, height * .5f), Offset(width * .6f, height * .5f), 350) }
            compose.waitUntil(10_000) { runBlocking { repo.settings.first().selectedWorkspace == 0 } }

            compose.onNodeWithTag("workspace-app-$a").performTouchInput { swipe(center, center + Offset(0f, 300f)) }
            compose.onNodeWithTag("app-drawer").assertDoesNotExist()
            compose.onNodeWithTag("control-center").assertDoesNotExist()
            compose.onNodeWithTag("workspace-app-$a").performTouchInput { longClick() }
            compose.onNodeWithText("Позже", substring = false).performClick()
            compose.waitUntil(5_000) { order(0) == listOf("app:$b", "app:$a", "app:$c") }
            openMenu("Порядок элементов")
            awaitTag("workspace-order")
            compose.onNodeWithTag("earlier-app:$c").performClick()
            compose.waitUntil(5_000) { order(0) == listOf("app:$b", "app:$c", "app:$a") }
            screenshot("v04-order")
            compose.onNodeWithContentDescription("Закрыть настройки пространства").performClick()

            compose.onNodeWithTag("workspace-app-$a").performTouchInput { longClick() }
            compose.onNodeWithText("Переместить в пространство 02").performClick()
            compose.waitUntil(5_000) { order(0) == listOf("app:$b", "app:$c") && order(1) == listOf("app:$b", "app:$a") }
            compose.onNodeWithTag("workspace-app-$b").performTouchInput { longClick() }
            compose.onNodeWithText("Переместить в пространство 02").performClick()
            compose.waitUntil(5_000) { order(0) == listOf("app:$c") && order(1) == listOf("app:$b", "app:$a") }

            openMenu("Вид пространства")
            awaitTag("workspace-appearance")
            compose.onNodeWithTag("icon-size-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
            compose.onNodeWithText("Свободно").performClick()
            compose.onNodeWithTag("spacing-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
            screenshot("v04-appearance")
            compose.onNodeWithText("Применить").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { repo.settings.first().workspaces[0].appearance.iconSizeDp == 60 } }
            val saved = runBlocking { repo.settings.first() }
            assertEquals(GridDensity.RELAXED, saved.workspaces[0].appearance.density)
            assertEquals(20, saved.workspaces[0].appearance.spacingDp)
            assertEquals(WorkspaceAppearance(), saved.workspaces[1].appearance)
            scenario.recreate()
            awaitTag("workspace-app-$c")
            assertEquals(saved, runBlocking { repo.settings.first() })
            screenshot("v04-home")

            // A normal icon tap must still launch a real activity after all gestures and editing.
            val settingsApp = apps.first { it.componentId.substringBefore('/') == "com.android.settings" }
            runBlocking { repo.setPinned(0, settingsApp.componentId, true) }
            awaitTag("workspace-app-${settingsApp.componentId}")
            compose.onNodeWithTag("workspace-app-${settingsApp.componentId}").performTouchInput { click() }
            dismissBackgroundPixelLauncherAnr()
            assertTrue("Settings must open after a regular icon tap",
                UiDevice.getInstance(instrumentation).wait(Until.hasObject(By.pkg("com.android.settings")), 15_000))
        } finally { scenario.close() }
    }

    private fun awaitTag(tag: String) { compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        dismissBackgroundPixelLauncherAnr()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(350)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("mkdir -p /sdcard/Download/hyprdroid-shell", "screencap -p /sdcard/Download/hyprdroid-shell/$name.png")) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }
}
