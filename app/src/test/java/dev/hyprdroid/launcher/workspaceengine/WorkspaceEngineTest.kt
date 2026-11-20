package dev.hyprdroid.launcher.workspaceengine

import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkspaceEngineTest {
    private fun window(windowId: String, moduleId: String = "com.example/.Main", minWidthDp: Int = 48, minHeightDp: Int = 48) = WorkspaceWindow(WindowId(windowId), moduleId, minWidthDp, minHeightDp)

    private fun minsFor(engine: WorkspaceEngine): Map<WindowId, WindowMin> =
        engine.windowsById().keys.associateWith { WindowMin(120, 80) }

    private fun adaptiveEngine(count: Int, area: LayoutArea): WorkspaceEngine {
        val engine = WorkspaceEngine()
        assertTrue(engine.addFirstWindow(window("w-1", "com.example/.Main", 120, 80)))
        for (index in 2..count) {
            val target = engine.focusedWindowId ?: engine.leaves().last()
            assertTrue(engine.addWindowAdaptive(target, SplitId("s${index - 1}"), window("w-$index", "com.example/.W$index", 120, 80), area, minsFor(engine)))
            engine.focusWindow(WindowId("w-$index"))
        }
        return engine
    }

    private fun assertTilingLayout(placed: List<PlacedWindow>, area: LayoutArea) {
        for (item in placed) {
            assertTrue("${item.windowId} width must be positive", item.rect.width > 0)
            assertTrue("${item.windowId} height must be positive", item.rect.height > 0)
            assertTrue("${item.windowId} left must be inside the area", item.rect.x >= 0)
            assertTrue("${item.windowId} top must be inside the area", item.rect.y >= 0)
            assertTrue("${item.windowId} right must be inside the area", item.rect.x + item.rect.width <= area.widthPx)
            assertTrue("${item.windowId} bottom must be inside the area", item.rect.y + item.rect.height <= area.heightPx)
        }
        for (i in placed.indices) {
            for (j in i + 1 until placed.size) {
                val first = placed[i].rect
                val second = placed[j].rect
                val overlaps = first.x < second.x + second.width && second.x < first.x + first.width &&
                    first.y < second.y + second.height && second.y < first.y + first.height
                assertFalse("${placed[i].windowId} and ${placed[j].windowId} overlap", overlaps)
            }
        }
    }

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
        assertFalse(engine.resizeSplit(SplitId("s1"), Float.NaN))
        assertFalse(engine.resizeSplit(SplitId("s1"), Float.POSITIVE_INFINITY))
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

    @Test fun operationsOnEmptyTreeAreRejectedSafely() {
        val engine = WorkspaceEngine()
        assertFalse(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        assertFalse(engine.removeWindow(WindowId("w-1")))
        assertFalse(engine.focusWindow(WindowId("w-1")))
        assertFalse(engine.resizeSplit(SplitId("s1"), 0.5f))
        assertTrue(engine.addFirstWindow(window("w-1")))
    }

    @Test fun nestedLayoutHasNoOverlapsAndStaysInsideArea() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        assertTrue(engine.splitWindow(WindowId("w-2"), Orientation.VERTICAL, SplitId("s2"), window("w-3", "com.example/.Third")))
        assertTrue(engine.splitWindow(WindowId("w-3"), Orientation.HORIZONTAL, SplitId("s3"), window("w-4", "com.example/.Fourth")))
        val placed = engine.calculateLayout(LayoutArea(1000, 1000), mapOf(
            WindowId("w-1") to WindowMin(0, 0),
            WindowId("w-2") to WindowMin(0, 0),
            WindowId("w-3") to WindowMin(0, 0),
            WindowId("w-4") to WindowMin(0, 0),
        ))
        assertEquals(4, placed.size)
        for (item in placed) {
            assertTrue(item.rect.x + item.rect.width <= 1000)
            assertTrue(item.rect.y + item.rect.height <= 1000)
        }
        for (i in placed.indices) {
            for (j in i + 1 until placed.size) {
                val first = placed[i]
                val second = placed[j]
                val overlaps = first.rect.x < second.rect.x + second.rect.width && second.rect.x < first.rect.x + first.rect.width && first.rect.y < second.rect.y + second.rect.height && second.rect.y < first.rect.y + second.rect.height
                assertFalse("Windows ${first.windowId} and ${second.windowId} overlap", overlaps)
            }
        }
    }

    @Test fun layoutRespectsMinimumSizesWhenTheyFit() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        val placed = engine.calculateLayout(LayoutArea(1000, 1000), mapOf(
            WindowId("w-1") to WindowMin(600, 0),
            WindowId("w-2") to WindowMin(100, 0),
        ))
        assertEquals(2, placed.size)
        val byId = placed.associateBy { it.windowId }
        assertEquals(600, byId.getValue(WindowId("w-1")).rect.width)
        assertEquals(400, byId.getValue(WindowId("w-2")).rect.width)
    }

    @Test fun layoutKeepsGeometryWithinAreaWhenMinimumsDoNotFit() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        val placed = engine.calculateLayout(LayoutArea(500, 1000), mapOf(
            WindowId("w-1") to WindowMin(400, 0),
            WindowId("w-2") to WindowMin(400, 0),
        ))
        for (item in placed) {
            assertTrue(item.rect.x + item.rect.width <= 500)
            assertTrue(item.rect.y + item.rect.height <= 1000)
            assertTrue(item.rect.width >= 0)
            assertTrue(item.rect.height >= 0)
        }
        assertTrue(placed.any { it.constrained })
    }

    @Test fun layoutNeverProducesNegativeSizes() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        val placed = engine.calculateLayout(LayoutArea(-100, -50), mapOf(WindowId("w-1") to WindowMin(-20, -10)))
        assertEquals(1, placed.size)
        assertTrue(placed.single().rect.width >= 0)
        assertTrue(placed.single().rect.height >= 0)
    }

    @Test fun roundTripEmptyTreeAndNestedTree() {
        val empty = WorkspaceEngine().deserialize(WorkspaceEngine().serialize())
        assertTrue(empty.root == null)
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        val restored = WorkspaceEngine().deserialize(engine.serialize())
        val placedBefore = engine.calculateLayout(LayoutArea(640, 1280), mapOf(WindowId("w-1") to WindowMin(0, 0), WindowId("w-2") to WindowMin(0, 0)))
        val placedAfter = restored.calculateLayout(LayoutArea(640, 1280), mapOf(WindowId("w-1") to WindowMin(0, 0), WindowId("w-2") to WindowMin(0, 0)))
        assertEquals(placedBefore, placedAfter)
    }

    @Test fun deserializeRejectsMissingMandatoryFields() {
        val missingRoot = JSONObject().put("version", 1).put("windows", JSONArray()).put("focused", JSONObject.NULL).toString()
        try {
            WorkspaceEngine().deserialize(missingRoot)
            fail("Expected IOException for a missing root")
        }
        catch (expected: IOException) {
            // expected
        }
        val missingWindows = JSONObject().put("version", 1).put("root", JSONObject.NULL).put("focused", JSONObject.NULL).toString()
        try {
            WorkspaceEngine().deserialize(missingWindows)
            fail("Expected IOException for missing windows")
        }
        catch (expected: IOException) {
            // expected
        }
        val missingFocused = JSONObject().put("version", 1).put("root", JSONObject.NULL).put("windows", JSONArray()).toString()
        try {
            WorkspaceEngine().deserialize(missingFocused)
            fail("Expected IOException for missing focused")
        }
        catch (expected: IOException) {
            // expected
        }
    }

    @Test fun deserializeRejectsInvalidSplitPayloads() {
        val missingRatio = JSONObject()
            .put("version", 1)
            .put("root", JSONObject().put("id", "s1").put("orientation", "HORIZONTAL").put("first", JSONObject().put("leaf", "w-1")).put("second", JSONObject().put("leaf", "w-2")))
            .put("windows", JSONArray().put(JSONObject().put("windowId", "w-1").put("moduleId", "com.example/.Main")).put(JSONObject().put("windowId", "w-2").put("moduleId", "com.example/.Second")))
            .put("focused", JSONObject.NULL)
            .toString()
        try {
            WorkspaceEngine().deserialize(missingRatio)
            fail("Expected IOException for a split without ratio")
        }
        catch (expected: IOException) {
            // expected
        }
        val badOrientation = JSONObject()
            .put("version", 1)
            .put("root", JSONObject().put("id", "s1").put("orientation", "DIAGONAL").put("ratio", 0.5).put("first", JSONObject().put("leaf", "w-1")).put("second", JSONObject().put("leaf", "w-2")))
            .put("windows", JSONArray().put(JSONObject().put("windowId", "w-1").put("moduleId", "com.example/.Main")).put(JSONObject().put("windowId", "w-2").put("moduleId", "com.example/.Second")))
            .put("focused", JSONObject.NULL)
            .toString()
        try {
            WorkspaceEngine().deserialize(badOrientation)
            fail("Expected IOException for an unknown split orientation")
        }
        catch (expected: IOException) {
            // expected
        }
        val badRatio = JSONObject()
            .put("version", 1)
            .put("root", JSONObject().put("id", "s1").put("orientation", "HORIZONTAL").put("ratio", 1.5).put("first", JSONObject().put("leaf", "w-1")).put("second", JSONObject().put("leaf", "w-2")))
            .put("windows", JSONArray().put(JSONObject().put("windowId", "w-1").put("moduleId", "com.example/.Main")).put(JSONObject().put("windowId", "w-2").put("moduleId", "com.example/.Second")))
            .put("focused", JSONObject.NULL)
            .toString()
        try {
            WorkspaceEngine().deserialize(badRatio)
            fail("Expected IOException for a split ratio out of range")
        }
        catch (expected: IOException) {
            // expected
        }
    }

    @Test fun deserializeRejectsDuplicateIdsAndInvalidFocusedWindow() {
        val duplicateLeaf = JSONObject()
            .put("version", 1)
            .put("root", JSONObject().put("id", "s1").put("orientation", "HORIZONTAL").put("ratio", 0.5).put("first", JSONObject().put("leaf", "w-1")).put("second", JSONObject().put("leaf", "w-1")))
            .put("windows", JSONArray().put(JSONObject().put("windowId", "w-1").put("moduleId", "com.example/.Main")))
            .put("focused", JSONObject.NULL)
            .toString()
        try {
            WorkspaceEngine().deserialize(duplicateLeaf)
            fail("Expected IOException for duplicate window ids")
        }
        catch (expected: IOException) {
            // expected
        }
        val innerSplit = JSONObject()
            .put("id", "s1")
            .put("orientation", "VERTICAL")
            .put("ratio", 0.5)
            .put("first", JSONObject().put("leaf", "w-1"))
            .put("second", JSONObject().put("leaf", "w-2"))
        val duplicateSplit = JSONObject()
            .put("version", 1)
            .put("root", JSONObject()
                .put("id", "s1")
                .put("orientation", "HORIZONTAL")
                .put("ratio", 0.5)
                .put("first", innerSplit)
                .put("second", JSONObject().put("leaf", "w-3")))
            .put("windows", JSONArray()
                .put(JSONObject().put("windowId", "w-1").put("moduleId", "com.example/.Main"))
                .put(JSONObject().put("windowId", "w-2").put("moduleId", "com.example/.Second"))
                .put(JSONObject().put("windowId", "w-3").put("moduleId", "com.example/.Third")))
            .put("focused", JSONObject.NULL)
            .toString()
        try {
            WorkspaceEngine().deserialize(duplicateSplit)
            fail("Expected IOException for duplicate split ids")
        }
        catch (expected: IOException) {
            // expected
        }
        val badFocused = JSONObject()
            .put("version", 1)
            .put("root", JSONObject().put("leaf", "w-1"))
            .put("windows", JSONArray().put(JSONObject().put("windowId", "w-1").put("moduleId", "com.example/.Main")))
            .put("focused", "w-2")
            .toString()
        try {
            WorkspaceEngine().deserialize(badFocused)
            fail("Expected IOException for a focused window outside the tree")
        }
        catch (expected: IOException) {
            // expected
        }
    }

    @Test fun layoutWithUnfittableMinimumsKeepsGeometryAndFlagsConstrained() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        val placed = engine.calculateLayout(LayoutArea(100, 1000), mapOf(
            WindowId("w-1") to WindowMin(200, 0),
            WindowId("w-2") to WindowMin(200, 0),
        ))
        assertEquals(2, placed.size)
        val byId = placed.associateBy { it.windowId }
        val first = byId.getValue(WindowId("w-1"))
        val second = byId.getValue(WindowId("w-2"))
        assertTrue(first.rect.width >= 0)
        assertTrue(second.rect.width >= 0)
        assertEquals(100, first.rect.width + second.rect.width)
        assertTrue(first.constrained)
        assertTrue(second.constrained)
    }

    @Test fun splitWindowRejectsDuplicateSplitIdWithoutStateChange() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        assertFalse(engine.splitWindow(WindowId("w-2"), Orientation.VERTICAL, SplitId("s1"), window("w-3", "com.example/.Third")))
        val placed = engine.calculateLayout(LayoutArea(1000, 1000), mapOf(
            WindowId("w-1") to WindowMin(0, 0),
            WindowId("w-2") to WindowMin(0, 0),
        ))
        assertEquals(2, placed.size)
    }

    @Test fun deserializeRejectsDuplicateWindowEntries() {
        val firstWindow = JSONObject().put("windowId", "w-1").put("moduleId", "com.example/.Main")
        val duplicateWindows = JSONObject()
            .put("version", 1)
            .put("root", JSONObject().put("leaf", "w-1"))
            .put("windows", JSONArray().put(firstWindow).put(firstWindow))
            .put("focused", JSONObject.NULL)
            .toString()
        try {
            WorkspaceEngine().deserialize(duplicateWindows)
            fail("Expected IOException for duplicate window entries")
        }
        catch (expected: IOException) {
            // expected
        }
    }

    @Test fun removeWindowMovesFocusToRemainingWindowOrClearsIt() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        engine.focusWindow(WindowId("w-1"))
        assertTrue(engine.removeWindow(WindowId("w-1")))
        assertEquals(WindowId("w-2"), engine.focusedWindowId)
        val empty = WorkspaceEngine()
        empty.addFirstWindow(window("w-1"))
        assertTrue(empty.removeWindow(WindowId("w-1")))
        assertTrue(empty.focusedWindowId == null)
    }

    @Test fun operationsAfterDeserializationKeepInvariants() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second"))
        val restored = WorkspaceEngine().deserialize(engine.serialize())
        assertTrue(restored.splitWindow(WindowId("w-2"), Orientation.VERTICAL, SplitId("s2"), window("w-3", "com.example/.Third")))
        assertTrue(restored.removeWindow(WindowId("w-2")))
        val placed = restored.calculateLayout(LayoutArea(1000, 1000), mapOf(
            WindowId("w-1") to WindowMin(0, 0),
            WindowId("w-3") to WindowMin(0, 0),
        ))
        assertEquals(2, placed.size)
    }

    @Test fun layoutWithLargeMinimumsUsesLongMathAndClamps() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        assertTrue(engine.splitWindow(WindowId("w-2"), Orientation.VERTICAL, SplitId("s2"), window("w-3", "com.example/.Third")))
        val placed = engine.calculateLayout(LayoutArea(2000, 2000), mapOf(
            WindowId("w-1") to WindowMin(Int.MAX_VALUE, Int.MAX_VALUE),
            WindowId("w-2") to WindowMin(Int.MAX_VALUE, Int.MAX_VALUE),
            WindowId("w-3") to WindowMin(Int.MAX_VALUE, Int.MAX_VALUE),
        ))
        assertEquals(3, placed.size)
        for (item in placed) {
            assertTrue(item.rect.width >= 0)
            assertTrue(item.rect.height >= 0)
            assertTrue(item.rect.x + item.rect.width <= 2000)
            assertTrue(item.rect.y + item.rect.height <= 2000)
        }
    }

    @Test fun layoutDistributesSpaceRespectingSplitMinimums() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        assertTrue(engine.resizeSplit(SplitId("s1"), 0.9f))
        val placed = engine.calculateLayout(LayoutArea(1000, 1000), mapOf(
            WindowId("w-1") to WindowMin(100, 0),
            WindowId("w-2") to WindowMin(600, 0),
        ))
        assertEquals(2, placed.size)
        val byId = placed.associateBy { it.windowId }
        assertEquals(400, byId.getValue(WindowId("w-1")).rect.width)
        assertEquals(600, byId.getValue(WindowId("w-2")).rect.width)
        assertFalse(byId.getValue(WindowId("w-2")).constrained)
    }

    @Test fun snapshotStaysIndependentFromSplitAndResizeMutations() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        val snapshot = engine.snapshot()
        assertTrue(engine.resizeSplit(SplitId("s1"), 0.75f))
        val placedAfterResize = snapshot.calculateLayout(LayoutArea(1000, 1000), mapOf(
            WindowId("w-1") to WindowMin(0, 0),
            WindowId("w-2") to WindowMin(0, 0),
        ))
        assertEquals(2, placedAfterResize.size)
        assertEquals(500, placedAfterResize.first { it.windowId == WindowId("w-1") }.rect.width)
        assertTrue(engine.splitWindow(WindowId("w-2"), Orientation.VERTICAL, SplitId("s2"), window("w-3", "com.example/.Third")))
        val placedAfterSplit = snapshot.calculateLayout(LayoutArea(1000, 1000), mapOf(
            WindowId("w-1") to WindowMin(0, 0),
            WindowId("w-2") to WindowMin(0, 0),
        ))
        assertEquals(2, placedAfterSplit.size)
    }

    @Test fun sequentialAddsExposeAllWindowsUpToSix() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second"))
        engine.splitWindow(WindowId("w-2"), Orientation.VERTICAL, SplitId("s2"), window("w-3", "com.example/.Third"))
        engine.splitWindow(WindowId("w-3"), Orientation.HORIZONTAL, SplitId("s3"), window("w-4", "com.example/.Fourth"))
        engine.splitWindow(WindowId("w-4"), Orientation.VERTICAL, SplitId("s4"), window("w-5", "com.example/.Fifth"))
        engine.splitWindow(WindowId("w-5"), Orientation.HORIZONTAL, SplitId("s5"), window("w-6", "com.example/.Sixth"))
        val mins = (1..6).associate { index -> WindowId("w-$index") to WindowMin(0, 0) }
        val placed = engine.calculateLayout(LayoutArea(1000, 1000), mins)
        assertEquals(6, placed.size)
        for (item in placed) {
            assertTrue("Window ${item.windowId} must have a positive size", item.rect.width > 0)
            assertTrue("Window ${item.windowId} must have a positive size", item.rect.height > 0)
            assertTrue(item.rect.x + item.rect.width <= 1000)
            assertTrue(item.rect.y + item.rect.height <= 1000)
        }
        for (i in placed.indices) {
            for (j in i + 1 until placed.size) {
                val first = placed[i]
                val second = placed[j]
                val overlaps = first.rect.x < second.rect.x + second.rect.width && second.rect.x < first.rect.x + first.rect.width && first.rect.y < second.rect.y + second.rect.height && second.rect.y < first.rect.y + first.rect.height
                assertFalse("Windows ${first.windowId} and ${second.windowId} overlap", overlaps)
            }
        }
    }

    @Test fun nestedSplitsExposeAllWindowsAfterThirdAdd() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s2"), window("w-3", "com.example/.Third")))
        val placed = engine.calculateLayout(LayoutArea(1000, 1000), (1..3).associate { WindowId("w-$it") to WindowMin(0, 0) })
        assertEquals(3, placed.size)
        for (item in placed) {
            assertTrue(item.rect.width > 0)
            assertTrue(item.rect.height > 0)
            assertTrue(item.rect.x + item.rect.width <= 1000)
            assertTrue(item.rect.y + item.rect.height <= 1000)
        }
        for (i in placed.indices) {
            for (j in i + 1 until placed.size) {
                val first = placed[i]
                val second = placed[j]
                val overlaps = first.rect.x < second.rect.x + second.rect.width && second.rect.x < first.rect.x + first.rect.width && first.rect.y < second.rect.y + second.rect.height && second.rect.y < first.rect.y + first.rect.height
                assertFalse("Windows ${first.windowId} and ${second.windowId} overlap", overlaps)
            }
        }
    }

    @Test fun nestedSplitsRespectMinimumsWithoutNegativeSizes() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1", "com.example/.Main", 80, 80))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second", 80, 80)))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s2"), window("w-3", "com.example/.Third", 80, 80)))
        val placed = engine.calculateLayout(LayoutArea(200, 200), mapOf(
            WindowId("w-1") to WindowMin(80, 80),
            WindowId("w-2") to WindowMin(80, 80),
            WindowId("w-3") to WindowMin(80, 80),
        ))
        assertEquals(3, placed.size)
        for (item in placed) {
            assertTrue(item.rect.width >= 0)
            assertTrue(item.rect.height >= 0)
            assertTrue(item.rect.x + item.rect.width <= 200)
            assertTrue(item.rect.y + item.rect.height <= 200)
        }
    }

    @Test fun splitIsAllowedWhileBothChildrenStayVisible() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1", "com.example/.Main", 400, 400))
        assertTrue(engine.canSplit(WindowId("w-1"), Orientation.HORIZONTAL, LayoutArea(500, 500)))
        assertTrue(engine.canSplit(WindowId("w-1"), Orientation.VERTICAL, LayoutArea(500, 500)))
        assertFalse(engine.canSplit(WindowId("w-1"), Orientation.HORIZONTAL, LayoutArea(1, 500)))
        assertFalse(engine.canSplit(WindowId("w-missing"), Orientation.HORIZONTAL, LayoutArea(500, 500)))
    }

    @Test fun oversizedMinimumsStillGiveEveryWindowPositiveSize() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1", "com.example/.Main", 320, 240))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second", 320, 240)))
        assertTrue(engine.splitWindow(WindowId("w-2"), Orientation.HORIZONTAL, SplitId("s2"), window("w-3", "com.example/.Third", 320, 240)))
        val placed = engine.calculateLayout(LayoutArea(1019, 2000), mapOf(
            WindowId("w-1") to WindowMin(840, 630),
            WindowId("w-2") to WindowMin(840, 630),
            WindowId("w-3") to WindowMin(840, 630),
        ))
        assertEquals(3, placed.size)
        for (item in placed) {
            assertTrue("Window ${item.windowId} must stay visible", item.rect.width > 0)
            assertTrue("Window ${item.windowId} must stay visible", item.rect.height > 0)
            assertTrue(item.rect.x + item.rect.width <= 1019)
            assertTrue(item.rect.y + item.rect.height <= 2000)
        }
    }

    @Test fun splitOrientationFollowsTheSelectedLeafRect() {
        assertEquals(Orientation.VERTICAL, orientationFor(LayoutRect(0, 0, 300, 600)))
        assertEquals(Orientation.HORIZONTAL, orientationFor(LayoutRect(0, 0, 600, 300)))
        assertEquals(Orientation.HORIZONTAL, orientationFor(LayoutRect(0, 0, 400, 400)))
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertEquals(Orientation.VERTICAL, engine.splitOrientation(WindowId("w-1"), LayoutArea(400, 800), emptyMap()))
        assertEquals(Orientation.HORIZONTAL, engine.splitOrientation(WindowId("w-1"), LayoutArea(800, 400), emptyMap()))
        assertEquals(null, engine.splitOrientation(WindowId("missing"), LayoutArea(800, 400), emptyMap()))
    }

    @Test fun adaptiveOrientationUsesTheLeafRectNotTheWholeWorkspace() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.addWindowAdaptive(WindowId("w-1"), SplitId("s1"), window("w-2", "com.example/.Second"), LayoutArea(1080, 1920), emptyMap()))
        // The portrait workspace is taller than wide, but each leaf is 1080x960 and therefore wider than tall.
        assertEquals(Orientation.HORIZONTAL, engine.splitOrientation(WindowId("w-2"), LayoutArea(1080, 1920), emptyMap()))
    }

    @Test fun portraitAdaptiveLayoutsCoverTwoToSixWindows() {
        val area = LayoutArea(1080, 1920)
        for (count in 2..6) {
            val engine = adaptiveEngine(count, area)
            val placed = engine.calculateLayout(area, minsFor(engine))
            assertEquals("$count windows", count, placed.size)
            assertTilingLayout(placed, area)
            val root = engine.root as SplitNode
            assertEquals("Portrait root for $count windows", Orientation.VERTICAL, root.orientation)
            val rect = placed.associate { it.windowId to it.rect }
            when (count) {
                2 -> {
                    val first = rect.getValue(WindowId("w-1"))
                    val second = rect.getValue(WindowId("w-2"))
                    assertTrue(second.y >= first.y + first.height)
                    assertEquals(first.x, second.x)
                    assertEquals(first.width, second.width)
                }
                3 -> {
                    assertTrue(root.second is SplitNode)
                    assertEquals(Orientation.HORIZONTAL, (root.second as SplitNode).orientation)
                    val top = rect.getValue(WindowId("w-1"))
                    val left = rect.getValue(WindowId("w-2"))
                    val right = rect.getValue(WindowId("w-3"))
                    assertTrue(left.y >= top.y + top.height)
                    assertEquals(left.y, right.y)
                    assertTrue(left.x < right.x)
                }
                4 -> {
                    val topLeft = rect.getValue(WindowId("w-1"))
                    val topRight = rect.getValue(WindowId("w-2"))
                    val bottomLeft = rect.getValue(WindowId("w-3"))
                    val bottomRight = rect.getValue(WindowId("w-4"))
                    assertEquals(topLeft.y, topRight.y)
                    assertEquals(bottomLeft.y, bottomRight.y)
                    assertTrue(bottomLeft.y >= topLeft.y + topLeft.height)
                    assertEquals(topLeft.x, bottomLeft.x)
                    assertEquals(topRight.x, bottomRight.x)
                    assertTrue(topLeft.x < topRight.x)
                }
                5 -> {
                    val top = (1..2).map { rect.getValue(WindowId("w-$it")) }
                    val bottom = (3..5).map { rect.getValue(WindowId("w-$it")) }
                    assertEquals(top[0].y, top[1].y)
                    assertEquals(bottom[0].y, bottom[1].y)
                    assertEquals(bottom[1].y, bottom[2].y)
                    assertTrue(bottom[0].y >= top[0].y + top[0].height)
                }
                6 -> {
                    for (row in 0 until 3) {
                        val left = rect.getValue(WindowId("w-${row * 2 + 1}"))
                        val right = rect.getValue(WindowId("w-${row * 2 + 2}"))
                        assertEquals(left.y, right.y)
                        assertTrue(left.x < right.x)
                        if (row > 0) {
                            val above = rect.getValue(WindowId("w-${(row - 1) * 2 + 1}"))
                            assertTrue(left.y >= above.y + above.height)
                        }
                    }
                }
            }
        }
    }

    @Test fun landscapeAdaptiveLayoutsCoverTwoToSixWindows() {
        val area = LayoutArea(1920, 1080)
        for (count in 2..6) {
            val engine = adaptiveEngine(count, area)
            val placed = engine.calculateLayout(area, minsFor(engine))
            assertEquals("$count windows", count, placed.size)
            assertTilingLayout(placed, area)
            val root = engine.root as SplitNode
            assertEquals("Landscape root for $count windows", Orientation.HORIZONTAL, root.orientation)
            val rect = placed.associate { it.windowId to it.rect }
            when (count) {
                2 -> {
                    val first = rect.getValue(WindowId("w-1"))
                    val second = rect.getValue(WindowId("w-2"))
                    assertTrue(second.x >= first.x + first.width)
                    assertEquals(first.y, second.y)
                }
                3 -> {
                    assertTrue(root.second is SplitNode)
                    assertEquals(Orientation.VERTICAL, (root.second as SplitNode).orientation)
                    val left = rect.getValue(WindowId("w-1"))
                    val topRight = rect.getValue(WindowId("w-2"))
                    val bottomRight = rect.getValue(WindowId("w-3"))
                    assertTrue(topRight.x >= left.x + left.width)
                    assertEquals(topRight.x, bottomRight.x)
                    assertTrue(topRight.y < bottomRight.y)
                }
                4 -> {
                    val topLeft = rect.getValue(WindowId("w-1"))
                    val bottomLeft = rect.getValue(WindowId("w-2"))
                    val topRight = rect.getValue(WindowId("w-3"))
                    val bottomRight = rect.getValue(WindowId("w-4"))
                    assertEquals(topLeft.x, bottomLeft.x)
                    assertEquals(topRight.x, bottomRight.x)
                    assertTrue(topRight.x >= topLeft.x + topLeft.width)
                    assertEquals(topLeft.y, topRight.y)
                    assertEquals(bottomLeft.y, bottomRight.y)
                    assertTrue(topLeft.y < bottomLeft.y)
                }
            }
        }
    }

    @Test fun reflowPreservesWindowStateRatiosAndAdaptsToRotation() {
        val engine = adaptiveEngine(4, LayoutArea(1080, 1920))
        val rootBefore = engine.root as SplitNode
        assertTrue(engine.resizeSplit(rootBefore.id, 0.7f))
        val windowsBefore = engine.windowsById().toMap()
        val focusedBefore = engine.focusedWindowId
        assertEquals(Orientation.VERTICAL, rootBefore.orientation)

        assertTrue(engine.reflow(LayoutArea(1920, 1080)))
        val rotated = engine.root as SplitNode
        assertEquals(Orientation.HORIZONTAL, rotated.orientation)
        assertEquals(windowsBefore.keys, engine.windowsById().keys)
        assertEquals(focusedBefore, engine.focusedWindowId)
        assertEquals(0.7f, rotated.ratio, 0.0001f)
        assertTilingLayout(engine.calculateLayout(LayoutArea(1920, 1080), minsFor(engine)), LayoutArea(1920, 1080))

        assertFalse(engine.reflow(LayoutArea(1920, 1080)))
        assertTrue(engine.reflow(LayoutArea(1080, 1920)))
        assertEquals(Orientation.VERTICAL, (engine.root as SplitNode).orientation)
    }

    @Test fun canAddWindowKeepsEveryCellVisible() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.canAddWindow(LayoutArea(1080, 1920)))
        assertTrue(engine.canAddWindow(LayoutArea(1, 1920)))
        assertFalse(engine.canAddWindow(LayoutArea(1, 1)))
        assertFalse(engine.canAddWindow(LayoutArea(0, 100)))
        val five = adaptiveEngine(5, LayoutArea(1080, 1920))
        assertFalse(five.canAddWindow(LayoutArea(1, 1920)))
        assertTrue(five.canAddWindow(LayoutArea(1080, 1920)))
        val fiveLandscape = adaptiveEngine(5, LayoutArea(1920, 1080))
        assertFalse(fiveLandscape.canAddWindow(LayoutArea(1920, 2)))
        assertTrue(fiveLandscape.canAddWindow(LayoutArea(1920, 1080)))
    }

    @Test fun extremeRatioStillGivesEveryWindowAtLeastOnePixel() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        assertTrue(engine.resizeSplit(SplitId("s1"), WorkspaceEngine.MIN_RATIO))
        val placed = engine.calculateLayout(LayoutArea(10, 100), emptyMap())
        assertEquals(2, placed.size)
        assertTrue(placed.all { it.rect.width >= 1 })
    }

    @Test fun splitIdsBecomeFreeAgainAfterWindowRemoval() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1"))
        assertTrue(engine.splitWindow(WindowId("w-1"), Orientation.HORIZONTAL, SplitId("s1"), window("w-2", "com.example/.Second")))
        assertTrue(engine.removeWindow(WindowId("w-1")))
        assertTrue(engine.splitWindow(WindowId("w-2"), Orientation.VERTICAL, SplitId("s1"), window("w-3", "com.example/.Third")))
        val placed = engine.calculateLayout(LayoutArea(1000, 1000), mapOf(
            WindowId("w-2") to WindowMin(0, 0),
            WindowId("w-3") to WindowMin(0, 0),
        ))
        assertEquals(2, placed.size)
    }

    @Test fun allocatedWindowAndSplitIdsUseUuidAndNeverCollide() {
        val engine = WorkspaceEngine()
        val id1 = engine.nextWindowId()
        val id2 = engine.nextWindowId()
        val s1 = engine.nextSplitId()
        val s2 = engine.nextSplitId()
        assertFalse("window IDs must be unique", id1 == id2)
        assertFalse("split IDs must be unique", s1 == s2)
        assertTrue(engine.addFirstWindow(window(id1.value)))
        val id3 = engine.nextWindowId()
        assertFalse(id3 == id1)
        assertFalse(id3 == id2)
    }

    @Test fun addThreeWindowsRemoveFirstAndAddAgainRegression() {
        val area = LayoutArea(1080, 1920)
        val engine = WorkspaceEngine()
        val w1 = WindowId(UUID.randomUUID().toString())
        val w2 = WindowId(UUID.randomUUID().toString())
        val w3 = WindowId(UUID.randomUUID().toString())
        val s1 = SplitId(UUID.randomUUID().toString())
        val s2 = SplitId(UUID.randomUUID().toString())

        // 1. Add 3 windows
        assertTrue("first window added", engine.addFirstWindow(window(w1.value)))
        assertTrue("second window added", engine.addWindowAdaptive(w1, s1, window(w2.value, "com.example/.Second"), area, minsFor(engine)))
        assertTrue("third window added", engine.addWindowAdaptive(w2, s2, window(w3.value, "com.example/.Third"), area, minsFor(engine)))
        assertEquals(3, engine.leaves().size)
        assertEquals(setOf(w1, w2, w3), engine.leaves().toSet())

        // 2. Remove first window
        assertTrue("first window removed", engine.removeWindow(w1))
        assertEquals(2, engine.leaves().size)
        assertEquals(setOf(w2, w3), engine.leaves().toSet())

        // 3. Add again (using UUID as in WorkspaceViewModel.addTilingWindow)
        val target = requireNotNull(engine.focusedWindowId) { "focused window must exist" }
        val wNew = WindowId(UUID.randomUUID().toString())
        val sNew = SplitId(UUID.randomUUID().toString())
        assertTrue("re-adding window must succeed", engine.addWindowAdaptive(target, sNew, window(wNew.value, "com.example/.New"), area, minsFor(engine)))

        // Verify state: 3 windows, all IDs unique, first window id not reused
        val leaves = engine.leaves()
        assertEquals(3, leaves.size)
        assertEquals(3, leaves.toSet().size)
        assertTrue("removed first window must not be in active leaves", w1 !in leaves.toSet())
        assertTrue("re-added window must be in active leaves", wNew in leaves.toSet())
        assertEquals(setOf(w2, w3, wNew), leaves.toSet())
        assertTrue(engine.focusWindow(wNew))
        assertEquals(wNew, engine.focusedWindowId)

        val placed = engine.calculateLayout(area, minsFor(engine))
        assertEquals(3, placed.size)
        assertTilingLayout(placed, area)
    }

    @Test fun addThreeWindowsRemoveFirstAndAddAgainLandscapeRegression() {
        val area = LayoutArea(1920, 1080)
        val engine = WorkspaceEngine()
        val w1 = WindowId(UUID.randomUUID().toString())
        val w2 = WindowId(UUID.randomUUID().toString())
        val w3 = WindowId(UUID.randomUUID().toString())
        val s1 = SplitId(UUID.randomUUID().toString())
        val s2 = SplitId(UUID.randomUUID().toString())

        // 1. Add 3 windows
        assertTrue("first window added", engine.addFirstWindow(window(w1.value)))
        assertTrue("second window added", engine.addWindowAdaptive(w1, s1, window(w2.value, "com.example/.Second"), area, minsFor(engine)))
        assertTrue("third window added", engine.addWindowAdaptive(w2, s2, window(w3.value, "com.example/.Third"), area, minsFor(engine)))
        assertEquals(3, engine.leaves().size)
        assertEquals(setOf(w1, w2, w3), engine.leaves().toSet())

        // 2. Remove first window
        assertTrue("first window removed", engine.removeWindow(w1))
        assertEquals(2, engine.leaves().size)
        assertEquals(setOf(w2, w3), engine.leaves().toSet())

        // 3. Add again (using UUID as in WorkspaceViewModel.addTilingWindow)
        val target = requireNotNull(engine.focusedWindowId) { "focused window must exist" }
        val wNew = WindowId(UUID.randomUUID().toString())
        val sNew = SplitId(UUID.randomUUID().toString())
        assertTrue("re-adding window must succeed", engine.addWindowAdaptive(target, sNew, window(wNew.value, "com.example/.New"), area, minsFor(engine)))

        // Verify state: 3 windows, all IDs unique, first window id not reused
        val leaves = engine.leaves()
        assertEquals(3, leaves.size)
        assertEquals(3, leaves.toSet().size)
        assertTrue("removed first window must not be in active leaves", w1 !in leaves.toSet())
        assertTrue("re-added window must be in active leaves", wNew in leaves.toSet())
        assertEquals(setOf(w2, w3, wNew), leaves.toSet())
        assertTrue(engine.focusWindow(wNew))
        assertEquals(wNew, engine.focusedWindowId)

        val placed = engine.calculateLayout(area, minsFor(engine))
        assertEquals(3, placed.size)
        assertTilingLayout(placed, area)
    }

    @Test fun addingAfterRemovingMiddleWindowDoesNotReuseSurvivingId() {
        val area = LayoutArea(1920, 1080)
        val engine = WorkspaceEngine()
        val w1 = WindowId(UUID.randomUUID().toString())
        val w2 = WindowId(UUID.randomUUID().toString())
        val w3 = WindowId(UUID.randomUUID().toString())
        val w4 = WindowId(UUID.randomUUID().toString())
        assertTrue(engine.addFirstWindow(window(w1.value)))
        assertTrue(engine.splitWindow(w1, Orientation.HORIZONTAL, SplitId(UUID.randomUUID().toString()), window(w2.value, "com.example/.Second")))
        assertTrue(engine.splitWindow(w2, Orientation.VERTICAL, SplitId(UUID.randomUUID().toString()), window(w3.value, "com.example/.Third")))
        assertTrue(engine.splitWindow(w3, Orientation.HORIZONTAL, SplitId(UUID.randomUUID().toString()), window(w4.value, "com.example/.Fourth")))
        assertTrue(engine.removeWindow(w2))
        val newWindowId = WindowId(UUID.randomUUID().toString())
        val newSplitId = SplitId(UUID.randomUUID().toString())
        val target = requireNotNull(engine.focusedWindowId) { "focused window must exist" }
        assertTrue(engine.addWindowAdaptive(target, newSplitId, window(newWindowId.value, "com.example/.New"), area, minsFor(engine)))
        assertEquals(4, engine.leaves().size)
        assertEquals(4, engine.leaves().toSet().size)
        assertTrue(engine.focusWindow(newWindowId))
        assertEquals(newWindowId, engine.focusedWindowId)
        assertTrue("removed window must stay removed", w2 !in engine.leaves().toSet())
        val placed = engine.calculateLayout(area, minsFor(engine))
        assertTilingLayout(placed, area)
    }
}
