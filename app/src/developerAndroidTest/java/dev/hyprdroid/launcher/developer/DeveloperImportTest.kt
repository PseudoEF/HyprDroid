package dev.hyprdroid.launcher.developer

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    private var previousPickerWindow: Int? = null
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
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val foreground = root?.packageName?.toString()
            if (root != null && foreground?.endsWith(".documentsui") == true &&
                root.windowId != previousPickerWindow
            ) {
                if (!pickerSeen) Log.i("HyprDeveloperSaf", "picker foreground window=${root.windowId} file=$name")
                pickerSeen = true
                // Look only in the new active picker. The outgoing picker can still
                // display the next filename before Android starts its next window.
                val file = root.exactText(name)
                if (file != null && clickInActiveWindow(file)) {
                    previousPickerWindow = root.windowId
                    Log.i("HyprDeveloperSaf", "selected file=$name window=${root.windowId} elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    return
                }
                val roots = root.findAccessibilityNodeInfosByViewId("$foreground:id/roots_list")
                    .firstOrNull { it.isVisibleToUser }
                if (!downloadsRequested && roots != null) {
                    val downloads = roots.exactText("Downloads")
                    if (downloads != null && clickInActiveWindow(downloads)) {
                        downloadsRequested = true
                        Log.i("HyprDeveloperSaf", "selected Downloads file=$name")
                    }
                } else if (!rootsRequested && !downloadsRequested) {
                    val toolbar = root.findAccessibilityNodeInfosByViewId("$foreground:id/toolbar").firstOrNull()
                    val alreadyInDownloads = toolbar?.exactText("Downloads") != null
                    val showRoots = if (alreadyInDownloads) null else root.findDescription("Show roots")
                    if (showRoots != null && clickInActiveWindow(showRoots)) {
                        rootsRequested = true
                        Log.i("HyprDeveloperSaf", "opened roots file=$name")
                    }
                }
            }
            SystemClock.sleep(100)
        }
        val snapshot = instrumentation.targetContext.cacheDir.resolve("developer-picker-window.xml")
        runCatching { device.dumpWindowHierarchy(snapshot) }
        fail("DocumentsUI selection timed out for $name (foreground=${device.currentPackageName}, " +
            "pickerSeen=$pickerSeen, rootsRequested=$rootsRequested, downloadsRequested=$downloadsRequested)")
    }

    private fun AccessibilityNodeInfo.exactText(text: String): AccessibilityNodeInfo? =
        findAccessibilityNodeInfosByText(text).firstOrNull { it.isVisibleToUser && it.text?.toString() == text }

    private fun AccessibilityNodeInfo.findDescription(description: String): AccessibilityNodeInfo? {
        if (isVisibleToUser && contentDescription?.toString() == description) return this
        for (index in 0 until childCount) {
            getChild(index)?.findDescription(description)?.let { return it }
        }
        return null
    }

    private fun clickInActiveWindow(node: AccessibilityNodeInfo): Boolean {
        val expectedWindow = node.windowId
        var target: AccessibilityNodeInfo? = node
        while (target != null && target.windowId == expectedWindow) {
            if (target.isVisibleToUser && target.isEnabled && target.isClickable) {
                return instrumentation.uiAutomation.rootInActiveWindow?.windowId == expectedWindow &&
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target = target.parent
        }
        return false
    }
}
