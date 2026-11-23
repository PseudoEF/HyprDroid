package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.packages.PackageException
import dev.hyprdroid.launcher.packages.PackageManifest
import dev.hyprdroid.launcher.plugins.TileInteraction
import dev.hyprdroid.launcher.plugins.TileEventGateV2
import dev.hyprdroid.launcher.plugins.TileBatteryV2
import dev.hyprdroid.launcher.plugins.TileScheduleV2
import dev.hyprdroid.launcher.plugins.TileSignal
import dev.hyprdroid.launcher.plugins.TileSystemSnapshotV2
import dev.hyprdroid.launcher.plugins.TileTimer
import dev.hyprdroid.launcher.plugins.tileSystemV2
import dev.hyprdroid.launcher.plugins.TileStateV2
import dev.hyprdroid.launcher.plugins.TileStateValue
import dev.hyprdroid.launcher.plugins.TileUiCodec
import dev.hyprdroid.launcher.plugins.accepts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginApiV2Test {
    @Test
    fun interactiveElementsValidateTheirPayloadAndRemainV2Only() {
        val tokens = listOf("column", "5", "divider", "switch", "mode", "false",
            "textInput", "search", "initial", "icon", "CLOCK", "list", "1", "button", "go", "Go")
        val tree = TileUiCodec.decode(tokens, 2)
        assertEquals(tokens, TileUiCodec.encode(tree, 2))
        assertTrue(tree.accepts(TileInteraction.Toggle("mode", true)))
        assertTrue(tree.accepts(TileInteraction.Submit("search", "query")))
        assertTrue(tree.accepts(TileInteraction.Click("go")))
        assertFalse(tree.accepts(TileInteraction.Click("mode")))
        assertFalse(tree.accepts(TileInteraction.Change("search", "я".repeat(65))))
        assertThrows(IllegalArgumentException::class.java) { TileUiCodec.decode(tokens) }
    }

    @Test
    fun eventGateRejectsReplayStaleFramesAndRapidEvents() {
        val gate = TileEventGateV2()
        val button = TileUiCodec.decode(listOf("button", "go", "Go"), 2)
        val click = TileInteraction.Click("go")
        val revision = gate.publish(button)
        assertTrue(gate.accept(revision, 1, click, 0))
        assertFalse(gate.accept(revision, 1, click, 500))
        assertFalse(gate.accept(revision, 2, click, 200))
        val next = gate.publish(button)
        assertFalse(gate.accept(revision, 2, click, 500))
        assertTrue(gate.accept(next, 2, click, 500))
    }

    @Test
    fun rejectsInvalidTrees() {
        val invalid = listOf(
            listOf("row", "2", "switch", "same", "true", "button", "same", "Duplicate"),
            listOf("switch", "mode", "1"),
            listOf("list", "13") + List(13) { "divider" },
            List(7) { listOf("column", "1") }.flatten() + "divider",
            listOf("icon", "arbitrary-resource"),
        )
        invalid.forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { TileUiCodec.decode(input, 2) }
        }
    }

    @Test
    fun boundedStateRoundTripsTypedScalars() {
        val values = mapOf("count" to TileStateValue.Number(4.0), "flag" to TileStateValue.Flag(true),
            "message" to TileStateValue.Text("test"))
        assertEquals(values, TileStateV2.decode(TileStateV2.encode(values)))
        assertThrows(IllegalArgumentException::class.java) { TileStateV2.decode("""{"count":[]}""") }
        assertThrows(IllegalArgumentException::class.java) {
            TileStateV2.encode(mapOf("count" to TileStateValue.Number(Double.NaN)))
        }
    }

    @Test
    fun timersPauseAndNeverReplayMissedTicks() {
        val schedule = TileScheduleV2()
        assertThrows(IllegalArgumentException::class.java) { schedule.configure(listOf(TileTimer("fast", 1)), 0) }
        schedule.configure(listOf(TileTimer("clock", 1_000)), 0)
        assertEquals(TileSignal.Resume, schedule.setActive(true, 0))
        assertEquals(null, schedule.setActive(true, 10))
        assertTrue(schedule.poll(999).isEmpty())
        assertEquals(listOf(TileSignal.Timer("clock")), schedule.poll(60_000))
        assertTrue(schedule.poll(60_001).isEmpty())
        schedule.setActive(false, 60_002)
        assertTrue(schedule.poll(120_000).isEmpty())
        assertEquals(TileSignal.Resume, schedule.setActive(true, 121_000))
        assertTrue(schedule.poll(121_999).isEmpty())
    }

    @Test
    fun systemSnapshotReadsOnlyGrantedFields() {
        val none = tileSystemV2(emptySet(), { error("time exposed") }, { error("battery exposed") })
        assertEquals(TileSystemSnapshotV2(null, null, null), none)
        val battery = tileSystemV2(setOf("system.battery"), { error("time exposed") }, { TileBatteryV2(101, true) })
        assertEquals(TileSystemSnapshotV2(null, null, true), battery)
    }

    @Test
    fun manifestV2RejectsUnlistedAndDuplicateCapabilities() {
        fun manifest(api: Int, vararg permissions: String) = JSONObject()
            .put("id", "clock-tile").put("version", "1.0.0").put("apiVersion", api)
            .put("runtime", "lua").put("entry", "main.lua").put("dependencies", JSONObject())
            .put("permissions", JSONArray(permissions.toList()))
        assertEquals(2, PackageManifest.decode(manifest(2, "ui.tile", "system.time", "state.local")).apiVersion)
        for (denied in listOf(manifest(1, "system.time"), manifest(2, "ui.tile", "ui.tile"),
            manifest(2, "ui.tile", "system.device"), manifest(2, "state.local"))) {
            assertThrows(PackageException::class.java) { PackageManifest.decode(denied) }
        }
    }
}
