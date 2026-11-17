package dev.hyprdroid.launcher

import androidx.compose.ui.test.*
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
    @Test fun topbarEditorReordersTilesAndUnsavedBackKeepsStoredConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = ShellRepository(context)
        val original = runBlocking { repo.quickSettings.first() }
        val shell = runBlocking { repo.settings.first() }
        val initial = QuickSettingsConfig(listOf("QS_WIFI", "QS_VOLUME"))
        try {
            runBlocking { repo.setQuickSettings(initial) }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(20_000) { compose.onAllNodesWithTag("quick-topbar-open").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("quick-topbar-open").performClick()
                compose.onNodeWithTag("quick-tile-WIFI").assertExists()
                compose.onNodeWithTag("quick-tile-VOLUME").performScrollTo().performClick()
                compose.onNodeWithTag("quick-slider-VOLUME").assertExists()
                compose.onNodeWithText("Закрыть", substring = false).performClick()
                compose.onNodeWithContentDescription("Меню оболочки").performClick()
                compose.onNodeWithText("Quick Settings · редактор").performScrollTo().performClick()
                compose.onNodeWithTag("module-select-QS_VOLUME").performScrollTo().performClick()
                compose.onNodeWithTag("module-up-QS_VOLUME").performScrollTo().performClick()
                compose.onNodeWithTag("quick-settings-save").performClick()
                compose.waitUntil(10_000) { runBlocking { repo.quickSettings.first().topbar == listOf("QS_VOLUME", "QS_WIFI") } }
                compose.onNodeWithTag("module-select-QS_WIFI").performScrollTo().performClick()
                compose.onNodeWithTag("module-remove-QS_WIFI").performScrollTo().performClick()
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
