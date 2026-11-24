package dev.hyprdroid.launcher.external

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.MainActivity
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.data.TileStateSlot
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.model.ShellPresentation
import dev.hyprdroid.launcher.packages.AndroidPackages
import dev.hyprdroid.launcher.plugins.TileStateValue
import dev.hyprdroid.launcher.workspaceengine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalLuaSustainedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val packages = AndroidPackages.manager(app)
    private val store = WorkspaceEngineStore(app)
    private val first = WindowId("sustained-clock-a")
    private val second = WindowId("sustained-clock-b")

    @Test fun activeClockTilesUnderLoadForTenMinutes() {
        runBlocking {
            packages.install("clock-tile")
            ShellRepository(app).setPresentation(ShellPresentation())
            WorkspaceRepository(app).selectWorkspace(0)
            val engine = WorkspaceEngine().apply {
                check(addFirstWindow(WorkspaceWindow(first, "clock-tile", 0, 0)))
                check(splitWindow(first, Orientation.VERTICAL, SplitId("sustained-clocks"),
                    WorkspaceWindow(second, "clock-tile", 0, 0)))
            }
            store.save("0", engine, open = true)
        }
        val delays = mutableListOf<Long>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitState(first, true)
            awaitState(second, true)
            val start = SystemClock.elapsedRealtime()
            var nextLifecycle = start + 60_000
            var nextMemory = start
            var cycles = 0
            var userRetries = 0
            try {
                while (SystemClock.elapsedRealtime() - start < 600_000) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextMemory) {
                        Log.i("HyprLuaSustained", "sample elapsedMs=${now - start} corePssKiB=${Debug.getPss()}")
                        nextMemory += 30_000
                    }
                    if (now >= nextLifecycle) {
                        compose.onNodeWithContentDescription("Рабочее пространство 2").performClick()
                        compose.waitUntil(15_000) { compose.onAllNodesWithTag("clean-workspace-1").fetchSemanticsNodes()
                            .any { it.boundsInRoot.width > 0 } }
                        scenario.moveToState(Lifecycle.State.CREATED)
                        SystemClock.sleep(1_500)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        compose.onNodeWithContentDescription("Рабочее пространство 1").performClick()
                        awaitState(first, cycles % 2 == 0)
                        nextLifecycle += 60_000
                    }
                    val before = SystemClock.elapsedRealtime()
                    val expected = cycles % 2 != 0
                    // A fresh timer frame can invalidate a captured click. Count
                    // visible retries and require the requested state to commit.
                    var matched = false
                    repeat(5) {
                        if (!matched) {
                            if (!hasState(first, expected)) toggle(first).performClick()
                            try {
                                compose.waitUntil(3_000) { hasState(first, expected) }
                                matched = true
                            } catch (_: ComposeTimeoutException) {
                                userRetries++
                            }
                        }
                    }
                    assertTrue("Clock did not commit a user change after retries", matched)
                    delays += SystemClock.elapsedRealtime() - before
                    cycles++
                    awaitState(second, true)
                    SystemClock.sleep(4_000)
                }
                val saved = runBlocking { store.loadTileStateV2(TileStateSlot("clock-tile", 2, first)) }
                assertEquals(TileStateValue.Flag(cycles % 2 == 0), saved["format24"])
                runBlocking { packages.remove("clock-tile") }
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText("Lua v2 недоступен").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(saved, runBlocking { store.loadTileStateV2(TileStateSlot("clock-tile", 2, first)) })
                runBlocking { packages.install("clock-tile") }
                awaitState(first, cycles % 2 == 0)
                awaitState(second, true)
                assertTrue(delays.size > 50)
            } finally {
                val sorted = delays.sorted()
                val p95 = sorted.getOrNull(((sorted.size * 95 + 99) / 100) - 1)
                Log.i("HyprLuaSustained", "elapsedMs=${SystemClock.elapsedRealtime() - start} " +
                    "validatedChanges=${delays.size} userRetries=$userRetries eventP95Ms=$p95 eventMaxMs=${sorted.lastOrNull()}")
            }
        }
    }

    private fun toggle(id: WindowId) =
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("tiling-window-${id.value}")))

    private fun hasState(id: WindowId, checked: Boolean): Boolean = runCatching {
        if (checked) toggle(id).assertIsOn() else toggle(id).assertIsOff()
        toggle(id).assertIsEnabled()
    }.isSuccess

    private fun awaitState(id: WindowId, checked: Boolean) {
        compose.waitUntil(20_000) { hasState(id, checked) }
    }
}
