package dev.hyprdroid.launcher.external

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.MainActivity
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.data.TileStateSlot
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.model.DesktopMode
import dev.hyprdroid.launcher.model.ShellPresentation
import dev.hyprdroid.launcher.packages.AndroidPackages
import dev.hyprdroid.launcher.plugins.TileStateValue
import dev.hyprdroid.launcher.plugins.TileExecutionV2
import dev.hyprdroid.launcher.plugins.TileInteraction
import dev.hyprdroid.launcher.plugins.TileSystemSnapshotV2
import dev.hyprdroid.launcher.workspaceengine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalV2PersistenceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = WorkspaceEngineStore(app)
    private val manager = AndroidPackages.manager(app)
    private val first = WindowId("persistent-clock-a")
    private val second = WindowId("persistent-clock-b")
    private val fresh = WindowId("persistent-clock-new")

    @Test
    fun clockStateAcrossProcess() {
        when (InstrumentationRegistry.getArguments().getString("persistencePhase")) {
            "seed" -> seed()
            "restore" -> restore()
            else -> error("Run this case through scripts/ci-v2-persistence.sh")
        }
        println("Clock persistence phase passed")
    }

    private fun seed() {
        runBlocking {
            manager.install("clock-tile")
            ShellRepository(app).setPresentation(ShellPresentation(DesktopMode.CLEAN))
            WorkspaceRepository(app).selectWorkspace(0)
            val layout = layout(first)
            store.save("0", layout)
            store.clearTileStateV2(slot(first))
            store.clearTileStateV2(slot(second))
            store.setOpen("0", true)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitSwitch(first, true)
            awaitSwitch(second, true)
            clockSwitch(first).assertIsEnabled().performClick()
            awaitSwitch(first, false)
            // Wait across multiple ticks before restarting the Activity and process.
            SystemClock.sleep(2400)
            awaitSwitch(first, false)
            assertState(first, false)
            scenario.recreate()
            awaitSwitch(first, false)
            awaitSwitch(second, true)
            assertState(first, false)
        }
    }

    private fun restore() {
        assertState(first, false)
        runBlocking {
            val snapshot = store.loadTileStateV2(slot(first))
            val request = TileExecutionV2(
                setOf("ui.tile", "state.local"), snapshot, TileSystemSnapshotV2(null, null, null),
                interaction = TileInteraction.Toggle("format24", true),
            )
            val watchdogFixture = "-- hypr-test-force-kernel-timeout".encodeToByteArray()
            assertNull(ExperimentalExternalLua.runV2(app, watchdogFixture, request))
            assertEquals(snapshot, store.loadTileStateV2(slot(first)))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitSwitch(first, false)
            awaitSwitch(second, true)
            runBlocking { manager.remove("clock-tile") }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Lua v2 недоступен").fetchSemanticsNodes().isNotEmpty() }
            assertState(first, false)
            runBlocking { manager.install("clock-tile") }
            awaitSwitch(first, false)
            awaitSwitch(second, true)
        }
        runBlocking {
            store.save("0", layout(fresh), removedTile = first)
            assertEquals(emptyMap<String, TileStateValue>(), store.loadTileStateV2(slot(first)))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitSwitch(fresh, true)
            awaitSwitch(second, true)
        }
    }

    private fun layout(id: WindowId) = WorkspaceEngine().apply {
        assertTrue(addFirstWindow(WorkspaceWindow(id, "clock-tile", 0, 0)))
        assertTrue(splitWindow(id, Orientation.VERTICAL, SplitId("clock-test-split"),
            WorkspaceWindow(second, "clock-tile", 0, 0)))
    }

    private fun slot(id: WindowId) = TileStateSlot("clock-tile", 2, id)

    private fun assertState(id: WindowId, checked: Boolean) = runBlocking {
        assertEquals(TileStateValue.Flag(checked), store.loadTileStateV2(slot(id))["format24"])
    }

    private fun clockSwitch(id: WindowId) =
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("tiling-window-${id.value}")))

    private fun awaitSwitch(id: WindowId, checked: Boolean) {
        compose.waitUntil(20_000) {
            runCatching {
                if (checked) clockSwitch(id).assertIsOn() else clockSwitch(id).assertIsOff()
                clockSwitch(id).assertIsEnabled()
            }.isSuccess
        }
    }
}
