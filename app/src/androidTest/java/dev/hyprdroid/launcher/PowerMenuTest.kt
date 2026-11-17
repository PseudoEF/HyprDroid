package dev.hyprdroid.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.model.DesktopMode
import dev.hyprdroid.launcher.power.PowerDialogService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PowerMenuTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun powerEntryExplainsOptionalAccessAndBackClosesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = ShellRepository(context)
        val previous = runBlocking { repo.settings.first().presentation }
        assertFalse("Run on an isolated emulator without the optional power service", PowerDialogService.available.value)
        try {
            runBlocking { repo.setPresentation(previous.copy(desktopMode = DesktopMode.CLEAN)) }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Меню оболочки").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Меню оболочки").performClick()
                compose.onNodeWithText("Quick Settings", substring = false).performScrollTo().performClick()
                compose.onNodeWithTag("power-open").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("power-menu").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("power-system-action").assertIsNotEnabled()
                compose.onNodeWithText("Настроить доступ").assertExists()
                captureShellScreen("v09-power-no-access")
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("power-menu").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("power-menu").assertDoesNotExist()
            }
        } finally { runBlocking { repo.setPresentation(previous) } }
    }
}
