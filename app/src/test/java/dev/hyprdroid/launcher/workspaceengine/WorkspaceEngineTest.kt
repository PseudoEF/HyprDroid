package dev.hyprdroid.launcher.workspaceengine

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkspaceEngineTest {
    private fun window(windowId: String, moduleId: String = "com.example/.Main", minWidthDp: Int = 48, minHeightDp: Int = 48) = WorkspaceWindow(WindowId(windowId), moduleId, minWidthDp, minHeightDp)

    @Test fun firstWindowLaysOutWholeArea() {
        val engine = WorkspaceEngine()
        assertTrue(engine.addFirstWindow(window("w-1")))
        val placed = engine.calculateLayout(LayoutArea(800, 1600), mapOf(WindowId("w-1") to WindowMin(0, 0)))
        assertEquals(1, placed.size)
        assertEquals(WindowId("w-1"), placed.single().windowId)
        assertEquals(0, placed.single().rect.x)
        assertEquals(0, placed.single().rect.y)
        assertEquals(800, placed.single().rect.width)
        assertEquals(1600, placed.single().rect.height)
        assertFalse(placed.single().constrained)
    }

    @Test fun sequentialSplitsTileTheArea() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        assertTrue(engine.splitWindow(WindowId("w-2"), Orientation.VERTICAL, SplitId("s2"), window("w-3", "com.example/.Third")))
        val placed = engine.calculateLayout(LayoutArea(1000, 1000), mapOf(WindowId("w-1") to WindowMin(0, 0), WindowId("w-2") to WindowMin(0, 0), WindowId("w-3") to WindowMin(0, 0)))
        assertEquals(3, placed.size)
        val byId = placed.associateBy { it.windowId }
        assertEquals(500, byId.getValue(WindowId("w-1")).rect.width)
        assertEquals(500, byId.getValue(WindowId("w-2")).rect.width)
        assertEquals(500, byId.getValue(WindowId("w-2")).rect.height)
        assertEquals(500, byId.getValue(WindowId("w-3")).rect.height)
        for (item in placed) {
            assertTrue(item.rect.x + item.rect.width <= 1000)
            assertTrue(item.rect.y + item.rect.height <= 1000)
        }
    }

    @Test fun removeMiddleWindowCollapsesSplit() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second"))
        engine.splitWindow(WindowId("w-2"), Orientation.VERTICAL, SplitId("s2"), window("w-3", "com.example/.Third"))
        assertTrue(engine.removeWindow(WindowId("w-2")))
        val placed = engine.calculateLayout(LayoutArea(1000, 1000), emptyMap())
        assertEquals(2, placed.size)
        assertEquals(setOf(WindowId("w-1"), WindowId("w-3")), placed.map { it.windowId }.toSet())
    }

    @Test fun removeFirstOfTwoKeepsTheSecondWindow() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        assertTrue(engine.removeWindow(WindowId("w-1")))
        val placed = engine.calculateLayout(LayoutArea(400, 800), mapOf(WindowId("w-2") to WindowMin(0, 0)))
        assertEquals(1, placed.size)
        assertEquals(WindowId("w-2"), placed.single().windowId)
    }

    @Test fun treeRestoresAfterWindowsAreRemoved() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        engine.removeWindow(WindowId("w-1"))
        assertTrue(engine.addFirstWindow(window("w-2", "com.example/.Second")))
        val placed = engine.calculateLayout(LayoutArea(100, 100), mapOf(WindowId("w-2") to WindowMin(0, 0)))
        assertEquals(1, placed.size)
        assertEquals(WindowId("w-2"), placed.single().windowId)
    }

    @Test fun splitWithExistingWindowIsRejectedWithoutStateChange() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertFalse(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-1", "com.example/.Duplicate")))
        assertEquals(1, engine.calculateLayout(LayoutArea(100, 100), emptyMap()).size)
    }

    @Test fun focusUpdatesFocusedWindow() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.focusWindow(WindowId("w-1")))
        assertEquals(WindowId("w-1"), engine.focusedWindowId)
        assertFalse(engine.focusWindow(WindowId("missing")))
    }

    @Test fun resizeSplitValidatesRatioAndUpdatesLayout() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        assertFalse(engine.resizeSplit(SplitId("s1"), 0.04f))
        assertFalse(engine.resizeSplit(SplitId("s1"), 0.96f))
        assertTrue(engine.resizeSplit(SplitId("s1"), 0.9f))
        val placed = engine.calculateLayout(LayoutArea(1000, 1000), mapOf(WindowId("w-1") to WindowMin(0, 0), WindowId("w-2") to WindowMin(0, 0)))
        assertEquals(900, placed.first { it.windowId == WindowId("w-1") }.rect.width)
    }

    @Test fun smallScreenReportsConstraintViolationWithoutNegativeSizes() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1", "com.example/.Main", 80, 80))
        val placed = engine.calculateLayout(LayoutArea(50, 50), mapOf(WindowId("w-1") to WindowMin(80, 80)))
        assertEquals(1, placed.size)
        assertTrue(placed.single().constrained)
        assertEquals(50, placed.single().rect.width)
        assertEquals(50, placed.single().rect.height)
    }

    @Test fun rotationChangesOnlyLayoutNotTree() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second"))
        val before = engine.serialize()
        val first = engine.calculateLayout(LayoutArea(800, 1600), emptyMap())
        val second = engine.calculateLayout(LayoutArea(400, 1200), emptyMap())
        assertEquals(before, engine.serialize())
        assertEquals(2, first.size)
        assertEquals(2, second.size)
    }

    @Test fun serializeDeserializeRoundTrip() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second"))
        engine.focusWindow(WindowId("w-2"))
        val restored = WorkspaceEngine().deserialize(engine.serialize())
        val placed = restored.calculateLayout(LayoutArea(640, 1280), mapOf(WindowId("w-1") to WindowMin(0, 0), WindowId("w-2") to WindowMin(0, 0)))
        assertEquals(2, placed.size)
        assertEquals(WindowId("w-2"), restored.focusedWindowId)
    }

    @Test fun deserializeRejectsUnknownVersionAndCorruptJson() {
        val unknownVersion = JSONObject().put("version", 2).put("root", JSONObject.NULL).put("windows", JSONArray()).put("focused", JSONObject.NULL).toString()
        try {
            WorkspaceEngine().deserialize(unknownVersion)
            fail("Expected IOException for an unknown engine schema version")
        }
        catch (expected: IOException) {
            // expected
        }
        try {
            WorkspaceEngine().deserialize("{not-json")
            fail("Expected IOException for corrupt engine state JSON")
        }
        catch (expected: IOException) {
            // expected
        }
    }

    @Test fun deserializeRejectsDamagedTree() {
        val damaged = JSONObject().put("version", 1).put("root", JSONObject().put("leaf", "w-ghost")).put("windows", JSONArray()).put("focused", JSONObject.NULL).toString()
        try {
            WorkspaceEngine().deserialize(damaged)
            fail("Expected IOException for a damaged engine state tree")
        }
        catch (expected: IOException) {
            // expected
        }
    }
}
