package dev.hyprdroid.launcher

import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuickSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun homeShadeHasTwoColumnsSeparateControlsAndClosesWithoutStealingNavigation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ShellRepository(context)
        val original = runBlocking { repository.quickSettings.first() }
        val shell = runBlocking { repository.settings.first() }
        val page = runBlocking { WorkspaceRepository(context).settings.first().selectedWorkspace }
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun await(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
        fun gone(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() } }
        fun open() {
            await("clean-workspace-$page")
            compose.onNodeWithTag("clean-workspace-$page").performTouchInput { swipe(Offset(width * .5f, height * .3f), Offset(width * .5f, height * .75f), 350) }
            await("control-dismiss-header")
        }
        try {
            runBlocking {
                repository.setQuickSettings(QuickSettingsConfig())
                repository.setPresentation(shell.presentation.copy(desktopMode = DesktopMode.CLEAN))
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                await("topbar-settings")
                compose.onNodeWithTag("topbar-settings").performClick()
                await("shell-appearance-panel")
                compose.onNodeWithTag("control-center").assertDoesNotExist()
                device.pressBack(); gone("shell-appearance-panel")
                open()
                compose.onNodeWithTag("quick-time").assertExists()
                compose.onNodeWithTag("quick-date").assertExists()
                compose.onAllNodesWithTag("quick-slider-BRIGHTNESS").assertCountEquals(1)
                compose.onAllNodesWithTag("quick-slider-VOLUME").assertCountEquals(1)
                captureShellScreen("v08-unified-shade")
                compose.onNodeWithTag("quick-tile-WIFI").performScrollTo()
                val wifi = compose.onNodeWithTag("quick-tile-WIFI").fetchSemanticsNode().boundsInRoot
                val bluetooth = compose.onNodeWithTag("quick-tile-BLUETOOTH").fetchSemanticsNode().boundsInRoot
                assertTrue(wifi.right <= bluetooth.left)
                assertEquals(wifi.top, bluetooth.top, 2f)
                val shade = compose.onNodeWithTag("quick-shade-surface").fetchSemanticsNode().boundsInRoot
                val root = compose.onNodeWithTag("control-center").fetchSemanticsNode().boundsInRoot
                assertTrue(shade.height < root.height * .95f)
                device.pressBack(); gone("control-center")
                open()
                compose.onNodeWithTag("control-scrim").performTouchInput { click(Offset(width * .5f, height * .93f)) }
                gone("control-center")
                open()
                compose.onNodeWithTag("control-dismiss-header").performTouchInput { swipe(center, center - Offset(0f, height * .65f)) }
                gone("control-center")
                compose.onNodeWithContentDescription("Меню оболочки").assertExists()
            }
        } finally { runBlocking { repository.setQuickSettings(original); repository.setPresentation(shell.presentation) } }
    }
    @Test fun topbarEditorReordersTilesAndUnsavedBackKeepsStoredConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = ShellRepository(context)
        val original = runBlocking { repo.quickSettings.first() }
        val shell = runBlocking { repo.settings.first() }
        val initial = QuickSettingsConfig(listOf("QS_WIFI", "QS_VOLUME"))
        try {
            runBlocking { repo.setQuickSettings(initial) }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Меню оболочки").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Меню оболочки").performClick()
                compose.onNodeWithText("Quick Settings", substring = false).performScrollTo().performClick()
                compose.onNodeWithTag("quick-tile-WIFI").assertExists()
                compose.onNodeWithTag("quick-tile-VOLUME").performScrollTo()
                compose.onNodeWithTag("quick-slider-VOLUME").assertExists()
                compose.onNodeWithContentDescription("Закрыть Control Center").performClick()
                compose.onNodeWithContentDescription("Меню оболочки").performClick()
                compose.onNodeWithText("Quick Settings · редактор").performScrollTo().performClick()
                compose.onNodeWithTag("module-up-QS_VOLUME").performScrollTo().performClick()
                compose.onNodeWithTag("quick-settings-save").performClick()
                compose.waitUntil(10_000) { runBlocking { repo.quickSettings.first().topbar == listOf("QS_VOLUME", "QS_WIFI") } }
                compose.onNodeWithTag("quick-enable-QS_WIFI").performScrollTo().performClick()
                compose.onNodeWithText("Закрыть", substring = false).performClick()
                compose.onNodeWithText("Продолжить редактирование").performClick()
                assertEquals(listOf("QS_VOLUME", "QS_WIFI"), runBlocking { repo.quickSettings.first().topbar })
                compose.onNodeWithText("Закрыть", substring = false).performClick()
                compose.onNodeWithText("Отменить изменения", substring = false).performClick()
                compose.onNodeWithTag("quick-settings-editor").assertDoesNotExist()
            }
            assertEquals(shell.panels, runBlocking { repo.settings.first().panels })
            assertEquals(shell.wallpaperUri, runBlocking { repo.settings.first().wallpaperUri })
        } finally { runBlocking { repo.setQuickSettings(original) } }
    }
}
