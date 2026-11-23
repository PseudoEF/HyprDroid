package dev.hyprdroid.launcher.external

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.packages.AndroidPackages
import dev.hyprdroid.launcher.packages.PackageException
import dev.hyprdroid.launcher.plugins.TileClockV2
import dev.hyprdroid.launcher.plugins.TileExecutionV2
import dev.hyprdroid.launcher.plugins.TileInteraction
import dev.hyprdroid.launcher.plugins.TileStateValue
import dev.hyprdroid.launcher.plugins.TileSystemSnapshotV2
import dev.hyprdroid.launcher.plugins.TileTimer
import dev.hyprdroid.launcher.plugins.TileUiCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalV2WorkerTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedSignedClockAndBatteryExecuteThroughIsolatedWorker(): Unit = runBlocking {
        assumeTrue(BuildConfig.V2_FIXTURE_LUA)
        val manager = AndroidPackages.manager(app)
        manager.install("clock-tile")
        manager.install("battery-tile")
        val snapshot = TileSystemSnapshotV2(TileClockV2("2026-11-23", "15:24:00", "3:24:00 PM"), 64, false)
        val (clock, source) = manager.verifiedV2Fixture("clock-tile")
        val initial = TileExecutionV2(clock.permissions, emptyMap(), snapshot)
        val clockFrame = requireNotNull(ExperimentalExternalLua.runV2(app, source, initial))
        assertTrue("15:24:00" in TileUiCodec.encode(clockFrame.tree, 2))
        assertEquals(listOf(TileTimer("clock", 1000)), clockFrame.timers)
        val updated = requireNotNull(ExperimentalExternalLua.runV2(app, source,
            initial.copy(interaction = TileInteraction.Toggle("format24", false))))
        assertEquals(mapOf("format24" to TileStateValue.Flag(false)), updated.state)
        assertTrue("3:24:00 PM" in TileUiCodec.encode(updated.tree, 2))
        val restored = requireNotNull(ExperimentalExternalLua.runV2(app, source, initial.copy(state = updated.state)))
        assertTrue("3:24:00 PM" in TileUiCodec.encode(restored.tree, 2))

        val (battery, batterySource) = manager.verifiedV2Fixture("battery-tile")
        val batteryFrame = requireNotNull(ExperimentalExternalLua.runV2(app, batterySource,
            TileExecutionV2(battery.permissions, emptyMap(), snapshot, TileInteraction.Click("refresh"))))
        assertTrue("64%" in TileUiCodec.encode(batteryFrame.tree, 2))
        assertTrue("Not charging" in TileUiCodec.encode(batteryFrame.tree, 2))
        manager.remove("clock-tile")
        assertThrows(PackageException::class.java) { manager.verifiedV2Fixture("clock-tile") }
        manager.install("clock-tile")
        assertArrayEquals(source, manager.verifiedV2Fixture("clock-tile").second)
    }

    @Test
    fun workerRejectsInvalidTimersAndDoesNotExposeUngrantedSnapshot(): Unit = runBlocking {
        assumeTrue(BuildConfig.V2_FIXTURE_LUA)
        val snapshot = TileSystemSnapshotV2(TileClockV2("date", "time24", "time12"), 55, true)
        val request = TileExecutionV2(setOf("ui.tile"), emptyMap(), snapshot)
        val source = """
            if system.get("device.time") ~= nil or system.get("battery.level") ~= nil then
                state.set("unexpected", true)
            end
            ui.render({type = "text", value = "no data"})
        """.trimIndent().encodeToByteArray()
        val frame = requireNotNull(ExperimentalExternalLua.runV2(app, source, request))
        assertEquals(listOf("text", "no data"), TileUiCodec.encode(frame.tree, 2))
        val invalid = """timer.every("fast", 999); ui.render({type = "text", value = "invalid"})"""
        assertNull(ExperimentalExternalLua.runV2(app, invalid.encodeToByteArray(), request))
        val denied = """state.set("private", true); ui.render({type = "text", value = "invalid"})"""
        assertNull(ExperimentalExternalLua.runV2(app, denied.encodeToByteArray(), request))
    }
}
