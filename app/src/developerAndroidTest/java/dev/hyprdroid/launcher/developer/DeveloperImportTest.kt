package dev.hyprdroid.launcher.developer

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
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
    private val fingerprint = requireNotNull(InstrumentationRegistry.getArguments().getString("developerFingerprint"))
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
        importKey()
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
        assertEquals(fingerprint, key.fingerprint)
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
        assertFalse(reopened.trusted(requireNotNull(reopened.info("my-tile"))))
        importKey()
        val reimported = reopened.keys().single()
        assertEquals(key.fingerprint, reimported.fingerprint)
        assertNotEquals(key.grant, reimported.grant)
        assertFalse(reopened.trusted(requireNotNull(reopened.info("my-tile"))))
        command("clear")
        command("hypr dev info my-tile")
        awaitOutput("Imported / Trust revoked / Execution disabled")
        command("hypr tile add my-tile")
        awaitOutput("Исполнение локальных пакетов отключено в Developer Edition.")
        command("hypr dev remove my-tile")
        awaitOutput("Локальный пакет удалён.")
        assertNull(reopened.info("my-tile"))
    }

    private fun importKey() {
        command("hypr dev trust add")
        pick("developer.pub.pem")
        awaitOutput("SHA-256 fingerprint: $fingerprint", substring = true)
        accept("Доверять публичному ключу?")
        awaitOutput("Добавлен ключ Ed25519 $fingerprint", substring = true)
    }

    private fun command(text: String) {
        compose.waitUntil(10_000) { !panel.running }
        compose.runOnIdle { panel.updateInput(text) }
        compose.onNodeWithText("Пуск").performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun accept(title: String) {
        awaitOutput(title)
        compose.onNodeWithText("Подтвердить").performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun awaitOutput(text: String, substring: Boolean = false) {
        compose.waitUntil(15_000) {
            // DocumentsUI can still own the resumed window immediately after a file tap.
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun pick(name: String) {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 20_000
        var rootsRequested = false
        var downloadsRequested = false
        var pickerSeen = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val foreground = device.currentPackageName
            if (foreground?.endsWith(".documentsui") == true) {
                if (!pickerSeen) Log.i("HyprDeveloperSaf", "picker foreground file=$name")
                pickerSeen = true
                try {
                    // A provider may finish loading at any point, including while navigation is offered.
                    val file = device.findObject(By.pkg(foreground).text(name))
                    if (file != null) {
                        file.click()
                        Log.i("HyprDeveloperSaf", "selected file=$name elapsedMs=${SystemClock.elapsedRealtime() - started}")
                        return
                    }
                    val roots = device.findObject(By.res(foreground, "roots_list"))
                    if (!downloadsRequested && roots != null) {
                        val downloads = roots.findObject(By.text("Downloads"))
                        if (downloads != null) {
                            downloads.click()
                            downloadsRequested = true
                            Log.i("HyprDeveloperSaf", "selected Downloads file=$name")
                        }
                    } else if (!rootsRequested && !downloadsRequested) {
                        val toolbar = device.findObject(By.res(foreground, "toolbar"))
                        val alreadyInDownloads = toolbar?.findObject(By.text("Downloads")) != null
                        val showRoots = if (alreadyInDownloads) null else device.findObject(By.pkg(foreground).desc("Show roots"))
                        if (showRoots != null) {
                            showRoots.click()
                            rootsRequested = true
                            Log.i("HyprDeveloperSaf", "opened roots file=$name")
                        }
                    }
                } catch (_: StaleObjectException) {
                    // Re-evaluate the current window after the provider replaces its rows.
                }
            }
            SystemClock.sleep(100)
        }
        val snapshot = instrumentation.targetContext.cacheDir.resolve("developer-picker-window.xml")
        runCatching { device.dumpWindowHierarchy(snapshot) }
        fail("DocumentsUI selection timed out for $name (foreground=${device.currentPackageName}, " +
            "pickerSeen=$pickerSeen, rootsRequested=$rootsRequested, downloadsRequested=$downloadsRequested)")
    }
}
