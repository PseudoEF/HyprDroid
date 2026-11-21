package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.modules.ModuleRegistry
import dev.hyprdroid.launcher.ui.TilingResizeHandle
import dev.hyprdroid.launcher.ui.beginTilingResize
import dev.hyprdroid.launcher.ui.selectTilingResizeHandle
import dev.hyprdroid.launcher.ui.tilingResizeRatio
import dev.hyprdroid.launcher.workspaceengine.LayoutArea
import dev.hyprdroid.launcher.workspaceengine.LayoutRect
import dev.hyprdroid.launcher.workspaceengine.Orientation
import dev.hyprdroid.launcher.workspaceengine.SplitId
import dev.hyprdroid.launcher.workspaceengine.WindowId
import dev.hyprdroid.launcher.workspaceengine.WindowMin
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import dev.hyprdroid.launcher.workspaceengine.WorkspaceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure coverage for live two-finger resize: split selection, absolute ratios and preview isolation. */
class TilingResizeTest {
    private fun window(id: String, minWidthDp: Int = 0, minHeightDp: Int = 0) =
        WorkspaceWindow(WindowId(id), ModuleRegistry.UNASSIGNED, minWidthDp, minHeightDp)

    private fun simpleEngine(firstMinHeightDp: Int = 0, secondMinHeightDp: Int = 0): WorkspaceEngine {
        val engine = WorkspaceEngine()
        assertTrue(engine.addFirstWindow(window("first", minHeightDp = firstMinHeightDp)))
        assertTrue(
            engine.splitWindow(
                WindowId("first"),
                Orientation.VERTICAL,
                SplitId("vertical"),
                window("second", minHeightDp = secondMinHeightDp),
            ),
        )
        engine.focusWindow(WindowId("second"))
        return engine
    }

    /** Left window plus a right column: a horizontal root split and a vertical column split. */
    private fun branchingEngine(): WorkspaceEngine {
        val engine = WorkspaceEngine()
        assertTrue(engine.addFirstWindow(window("left")))
        assertTrue(engine.splitWindow(WindowId("left"), Orientation.HORIZONTAL, SplitId("root"), window("top-right")))
        assertTrue(engine.splitWindow(WindowId("top-right"), Orientation.VERTICAL, SplitId("column"), window("bottom-right")))
        engine.focusWindow(WindowId("top-right"))
        return engine
    }

    private fun minsFor(engine: WorkspaceEngine): Map<WindowId, WindowMin> =
        engine.windowsById().mapValues { (_, window) -> WindowMin(window.minWidthDp, window.minHeightDp) }

    private fun placed(engine: WorkspaceEngine, area: LayoutArea) = engine.calculateLayout(area, minsFor(engine))

    private fun splits(engine: WorkspaceEngine, area: LayoutArea) = engine.splitsWithRects(
        engine.root,
        LayoutRect(x = 0, y = 0, width = area.widthPx, height = area.heightPx),
        minsFor(engine),
    )

    private fun ratioOf(engine: WorkspaceEngine, splitId: SplitId, area: LayoutArea): Float =
        splits(engine, area).first { it.split.id == splitId }.split.ratio

    @Test
    fun selectsAStableHandleForEachAxis() {
        val engine = branchingEngine()
        val area = LayoutArea(800, 600)
        val placed = placed(engine, area)
        val splits = splits(engine, area)
        val mins = minsFor(engine)

        val vertical = selectTilingResizeHandle(engine, WindowId("top-right"), Orientation.VERTICAL, placed, splits, mins)
        val horizontal = selectTilingResizeHandle(engine, WindowId("top-right"), Orientation.HORIZONTAL, placed, splits, mins)

        assertNotNull(vertical)
        assertEquals(SplitId("column"), vertical!!.splitId)
        assertTrue("top-right is the first child of the column split", vertical.inFirst)
        assertEquals(600, vertical.extentPx)

        assertNotNull(horizontal)
        assertEquals(SplitId("root"), horizontal!!.splitId)
        assertFalse("top-right is the second child of the root split", horizontal.inFirst)
        assertEquals(800, horizontal.extentPx)
    }

    @Test
    fun ratiosFollowTheTotalSpanWithoutAccumulatingDeltas() {
        val handle = TilingResizeHandle(
            splitId = SplitId("s"),
            orientation = Orientation.VERTICAL,
            inFirst = true,
            extentPx = 400,
            baseRatio = 0.5f,
            minRatio = 0.1f,
            maxRatio = 0.9f,
        )

        assertEquals(0.625f, tilingResizeRatio(handle, 100f, 150f), 0.0001f)
        assertEquals(0.55f, tilingResizeRatio(handle, 100f, 120f), 0.0001f)
        // Repeating or reversing movement is compared with the base span, never with the previous frame.
        assertEquals(0.55f, tilingResizeRatio(handle, 100f, 120f), 0.0001f)
        assertEquals(0.45f, tilingResizeRatio(handle, 100f, 80f), 0.0001f)
        assertEquals(0.5f, tilingResizeRatio(handle, 100f, 100f), 0.0001f)

        val secondChild = handle.copy(inFirst = false)
        assertEquals(0.375f, tilingResizeRatio(secondChild, 100f, 150f), 0.0001f)
    }

    @Test
    fun fastMovementAndDirectionChangesStayInsideTheRatioLimits() {
        val handle = TilingResizeHandle(
            splitId = SplitId("s"),
            orientation = Orientation.VERTICAL,
            inFirst = true,
            extentPx = 300,
            baseRatio = 0.5f,
            minRatio = 0.2f,
            maxRatio = 0.8f,
        )

        assertEquals(0.8f, tilingResizeRatio(handle, 100f, 10_000f), 0.0001f)
        assertEquals(0.2f, tilingResizeRatio(handle, 100f, -10_000f), 0.0001f)
        assertEquals(0.5f, tilingResizeRatio(handle, 500f, 500f), 0.0001f)
        assertEquals(0.6f, tilingResizeRatio(handle, 500f, 530f), 0.0001f)
        assertEquals(0.4f, tilingResizeRatio(handle, 500f, 470f), 0.0001f)
        // A fast pass and return to the same span yields the original ratio, not a drifting value.
        assertEquals(0.5f, tilingResizeRatio(handle, 500f, 500f), 0.0001f)
    }

    @Test
    fun minimumWindowSizesLimitTheHandleRange() {
        val engine = simpleEngine(firstMinHeightDp = 100, secondMinHeightDp = 100)
        val area = LayoutArea(400, 400)
        val mins = minsFor(engine)
        val handle = selectTilingResizeHandle(
            engine, WindowId("first"), Orientation.VERTICAL,
            engine.calculateLayout(area, mins), splits(engine, area), mins,
        )

        assertNotNull(handle)
        assertEquals(0.25f, handle!!.minRatio, 0.0001f)
        assertEquals(0.75f, handle.maxRatio, 0.0001f)
        assertEquals(0.25f, tilingResizeRatio(handle, 100f, -5_000f), 0.0001f)
        assertEquals(0.75f, tilingResizeRatio(handle, 100f, 5_000f), 0.0001f)
    }

    @Test
    fun previewNeverMutatesThePersistedEngineAndCanBeDiscarded() {
        val engine = simpleEngine()
        val area = LayoutArea(400, 400)
        val mins = minsFor(engine)
        val before = engine.serialize()

        val preview = beginTilingResize(engine, WindowId("first"), area, mins, 100f, 100f)
        val shown = preview.update(100f, 180f, horizontalActive = false)

        assertEquals(before, engine.serialize())
        assertNotEquals(before, shown.serialize())
        assertEquals(0.7f, preview.changedRatios(100f, 180f, horizontalActive = false).single().ratio, 0.0001f)
        assertEquals(WindowId("second"), shown.focusedWindowId)

        // Cancelling drops the preview: a new gesture starts from the persisted ratio again.
        val fresh = beginTilingResize(engine, WindowId("first"), area, mins, 100f, 100f)
        assertEquals(0.5f, tilingResizeRatio(fresh.vertical!!, 100f, 100f), 0.0001f)
    }

    @Test
    fun sequentialResizesStartFromTheCommittedRatio() {
        val engine = simpleEngine()
        val area = LayoutArea(400, 400)
        val mins = minsFor(engine)

        val first = beginTilingResize(engine, WindowId("first"), area, mins, 100f, 100f)
        val firstChange = first.changedRatios(100f, 180f, horizontalActive = false).single()
        assertTrue(engine.resizeSplit(firstChange.splitId, firstChange.ratio))
        assertEquals(0.7f, ratioOf(engine, SplitId("vertical"), area), 0.0001f)

        val second = beginTilingResize(engine, WindowId("first"), area, mins, 180f, 180f)
        val secondChange = second.changedRatios(180f, 220f, horizontalActive = false).single()
        assertTrue(engine.resizeSplit(secondChange.splitId, secondChange.ratio))
        assertEquals(0.8f, ratioOf(engine, SplitId("vertical"), area), 0.0001f)
    }

    @Test
    fun diagonalPreviewUpdatesBothAvailableAxes() {
        val engine = branchingEngine()
        val area = LayoutArea(800, 600)
        val mins = minsFor(engine)
        val preview = beginTilingResize(engine, WindowId("top-right"), area, mins, 100f, 100f)

        preview.update(180f, 160f, horizontalActive = true)

        val ratios = preview.changedRatios(180f, 160f, horizontalActive = true).associate { it.splitId to it.ratio }
        assertEquals(0.6f, ratios.getValue(SplitId("column")), 0.0001f)
        assertEquals(0.4f, ratios.getValue(SplitId("root")), 0.0001f)

        // A vertical-only gesture changes the column but leaves the root split untouched.
        val verticalOnly = preview.changedRatios(180f, 160f, horizontalActive = false)
        assertEquals(listOf(SplitId("column")), verticalOnly.map { it.splitId })
    }
}
