package dev.hyprdroid.launcher

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.data.LauncherCatalog
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.model.WorkspaceAppearance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun homeDrawerEditingContextMenuAndLauncherExit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val apps = LauncherCatalog(context).load()
        assertTrue("Emulator must provide at least three launchable apps", apps.size >= 3)
        val first = apps[0].componentId
        val second = apps[1].componentId
        val third = apps[2].componentId
        runBlocking {
            val workspace = WorkspaceRepository(context)
            workspace.settings.first().workspaces.forEach { existing ->
                existing.items.forEach { workspace.removeItem(existing.id, it.id) }
                workspace.setAppearance(existing.id, WorkspaceAppearance())
            }
            workspace.selectWorkspace(0)
            workspace.setGridLayout(true)
            workspace.setPinned(0, first, true)
            workspace.setPinned(0, second, true)
            workspace.setPinned(1, third, true)
            ShellRepository(context).setFavorite(second, true)
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("workspace-app-$first").fetchSemanticsNodes().isNotEmpty() }
            awaitText(apps[0].label)
            compose.onNodeWithText("Добавить приложения").assertDoesNotExist()
            compose.onNodeWithText("Убрать", substring = false).assertDoesNotExist()
            screenshot("home")

            compose.onNodeWithContentDescription("Все приложения").performClick()
            compose.onNodeWithTag("app-search").performTextInput(apps[2].label)
            compose.onNodeWithTag("drawer-app-$third").assertExists()
            screenshot("drawer-search")
            compose.onNodeWithContentDescription("Очистить поиск").performClick()
            compose.onNodeWithTag("app-search").performImeAction()
            compose.waitUntil(10_000) {
                var imeVisible = true
                scenario.onActivity { activity ->
                    imeVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                !imeVisible
            }
            screenshot("drawer")
            compose.onNodeWithContentDescription("Закрыть приложения").performClick()

            compose.onNodeWithTag("workspace-canvas-0").performTouchInput { longClick(Offset(12f, height - 16f)) }
            compose.onNodeWithText("ПРОСТРАНСТВО 01").assertExists()
            screenshot("edit")
            compose.onNodeWithText("Раскладка: сетка").performClick()
            awaitText("Раскладка: список")
            compose.onNodeWithText("Приложения", substring = false).performClick()
            compose.onNodeWithTag("drawer-app-$third").performTouchInput { click() }
            compose.onNodeWithContentDescription("Готово, вернуться к пространству").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("workspace-app-$third").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Готово, завершить редактирование").performClick()

            compose.onNodeWithTag("workspace-app-$first").performTouchInput { longClick() }
            compose.onNodeWithText("Убрать из пространства").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("workspace-app-$first").fetchSemanticsNodes().isEmpty() }

            compose.onNodeWithTag("workspace-app-$third").performTouchInput { longClick() }
            compose.onNodeWithText("Добавить в dock").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("dock-app-$third").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("dock-app-$third").performTouchInput { longClick() }
            compose.onNodeWithText("Убрать из dock").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("dock-app-$third").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("workspace-app-$third").assertExists()

            compose.onNodeWithContentDescription("Рабочее пространство 2").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("workspace-canvas-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("workspace-app-$third").assertExists()
            compose.onNodeWithContentDescription("Рабочее пространство 1").performClick()
            compose.onNodeWithTag("workspace-canvas-0").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("workspace-canvas-1").assertExists()
            compose.onNodeWithContentDescription("Рабочее пространство 1").performClick()

            compose.onNodeWithContentDescription("Меню оболочки").performClick()
            compose.onNodeWithText("Другой лаунчер").performClick()
            compose.waitUntil(10_000) {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in setOf(
                    "com.android.settings", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
                )
            }
            screenshot("home-settings")
        } finally { scenario.close() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Semantics can settle before SurfaceFlinger presents the last icon/text frame.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(350)
        // Test-only shell capture survives AGP uninstalling the instrumented package.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("mkdir -p /sdcard/Download/hyprdroid-shell", "screencap -p /sdcard/Download/hyprdroid-shell/$name.png")) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
