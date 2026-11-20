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

    @Test fun splitIsRejectedWhenSecondWindowMinimumDoesNotFit() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(window("w-1", "com.example/.Main", 400, 400))
        val mins = mapOf(WindowId("w-1") to WindowMin(400, 400))
        assertFalse(engine.canSplit(WindowId("w-1"), Orientation.HORIZONTAL, WindowMin(400, 400), LayoutArea(500, 500), mins))
        assertTrue(engine.canSplit(WindowId("w-1"), Orientation.HORIZONTAL, WindowMin(50, 50), LayoutArea(500, 500), mins))
        assertTrue(engine.canSplit(WindowId("w-1"), Orientation.VERTICAL, WindowMin(50, 50), LayoutArea(500, 500), mins))
        val missingTarget = engine.canSplit(WindowId("w-missing"), Orientation.HORIZONTAL, WindowMin(50, 50), LayoutArea(500, 500), mins)
        assertFalse(missingTarget)
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
}
