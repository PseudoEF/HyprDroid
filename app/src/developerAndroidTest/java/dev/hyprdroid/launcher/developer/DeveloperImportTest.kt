package dev.hyprdroid.launcher.developer

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.hyprdroid.launcher.modules.CommandPanelModule
import dev.hyprdroid.launcher.modules.CommandPanelState
import dev.hyprdroid.launcher.ui.ShellTheme
import dev.hyprdroid.launcher.workspaceengine.WindowId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeveloperImportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val panel = CommandPanelState(WindowId("developer-ci"))

    @Test fun signedFixtureSurvivesAProcessRestartAndCanBeRevoked() {
        val phase = InstrumentationRegistry.getArguments().getString("developerPhase")
        require(phase == "seed" || phase == "verify")
        ActivityScenario.launch(DeveloperActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    ShellTheme {
                        DeveloperTools { CommandPanelModule.Content(panel, Modifier.fillMaxSize()) }
                    }
                }
            }
            compose.waitForIdle()
            if (phase == "seed") seed() else verify()
        }
    }

    private fun seed() {
        command("hypr dev trust add")
        pick("developer.pub.pem")
        accept("Доверять публичному ключу?")
        awaitOutput("Добавлен ключ Ed25519", substring = true)
        command("hypr dev import")
        accept("Импорт локального пакета")
        pick("my-tile-1.0.0.hyp")
        pick("my-tile.signature.json")
        accept("Импортировать пакет?")
        awaitOutput("Импортирован my-tile. Исполнение отключено.")
        command("hypr dev info my-tile")
        awaitOutput("Imported / Verified / Execution disabled")
    }

    private fun verify() {
        val app = instrumentation.targetContext
        val reopened = DeveloperPackageStore(app.noBackupFilesDir.resolve("developer-packages")) { false }
        val key = reopened.keys().single()
        command("hypr dev trust list")
        awaitOutput(key.fingerprint, substring = true)
        command("hypr dev list")
        awaitOutput("my-tile 1.0.0 · Imported / Verified / Execution disabled")
        command("hypr tile add my-tile")
        awaitOutput("Исполнение локальных пакетов отключено в Developer Edition.")
        command("hypr dev trust remove ${key.fingerprint}")
        awaitOutput("Доверие отозвано.", substring = true)
        command("hypr dev info my-tile")
        awaitOutput("Imported / Trust revoked / Execution disabled")
        command("hypr dev remove my-tile")
        awaitOutput("Локальный пакет удалён.")
        assertNull(reopened.info("my-tile"))
    }

    private fun command(text: String) {
        compose.waitUntil(10_000) { !panel.running }
        compose.runOnIdle { panel.updateInput(text) }
        compose.onNodeWithText("Пуск").performClick()
    }

    private fun accept(title: String) {
        awaitOutput(title)
        compose.onNodeWithText("Подтвердить").performClick()
    }

    private fun awaitOutput(text: String, substring: Boolean = false) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun pick(name: String) {
        val file = By.text(name)
        if (!device.wait(Until.hasObject(file), 4_000)) {
            assertTrue("DocumentsUI roots unavailable", click(By.desc("Show roots")))
            assertTrue("DocumentsUI Downloads unavailable", click(By.text("Downloads")))
        }
        assertTrue("DocumentsUI file unavailable: $name", click(file))
    }

    private fun click(selector: androidx.test.uiautomator.BySelector): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (true) {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) return false
            val objectToClick = device.wait(Until.findObject(selector), remaining) ?: return false
            try {
                objectToClick.click()
                return true
            } catch (_: StaleObjectException) {
                // DocumentsUI may replace the row when a provider finishes scanning.
            }
        }
    }
}
