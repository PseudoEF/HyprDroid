package dev.hyprdroid.launcher

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.data.LauncherCatalog
import dev.hyprdroid.launcher.model.WorkspaceSettings
import dev.hyprdroid.launcher.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PanelScrollDismissTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun drawerScrollsBeforeDismissAndHeaderAlwaysReturnsBack() {
        dismissBackgroundPixelLauncherAnr()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = LauncherCatalog(context).load().first().icon
        val apps = List(100) { LauncherApp("example.app$it/.Main", "Application ${it.toString().padStart(3, '0')}", icon) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var launches = 0
        var closed by mutableStateOf(false)
        try {
            scenario.onActivity { activity -> activity.setContent {
                ShellTheme {
                    if (closed) Text("Drawer dismissed") else AppDrawer(
                        WorkspaceUiState(settings = WorkspaceSettings(), apps = apps, loadingApps = false), null,
                        onClose = { closed = true }, onLaunch = { launches++ }, onPin = { _, _ -> }, menu = { emptyList() }, onRetry = {},
                    )
                }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("drawer-grid").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("drawer-grid").performScrollToIndex(60)
            compose.onNodeWithTag("drawer-grid").performTouchInput { swipeDown() }
            compose.onNodeWithTag("app-drawer").assertExists()
            compose.onNodeWithText("Drawer dismissed").assertDoesNotExist()
            assertEquals(0, launches)

            compose.onNodeWithTag("drawer-grid").performScrollToIndex(0)
            compose.onNodeWithTag("drawer-grid").performTouchInput { swipeDown() }
            compose.waitUntil(5_000) { closed }
            compose.onNodeWithText("Drawer dismissed").assertExists()
            assertEquals(0, launches)

            compose.runOnIdle { closed = false }
            compose.onNodeWithTag("drawer-grid").performScrollToIndex(60)
            compose.onNodeWithTag("drawer-dismiss-header").performTouchInput { swipe(center, center + Offset(0f, 300f)) }
            compose.waitUntil(5_000) { closed }
            compose.onNodeWithText("Drawer dismissed").assertExists()
        } finally { scenario.close() }
    }
}
