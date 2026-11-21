package dev.hyprdroid.launcher.ui

import dev.hyprdroid.launcher.workspaceengine.LayoutArea
import dev.hyprdroid.launcher.workspaceengine.LayoutRect
import dev.hyprdroid.launcher.workspaceengine.Orientation
import dev.hyprdroid.launcher.workspaceengine.PlacedWindow
import dev.hyprdroid.launcher.workspaceengine.SplitId
import dev.hyprdroid.launcher.workspaceengine.WindowId
import dev.hyprdroid.launcher.workspaceengine.WindowMin
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import kotlin.math.abs

private const val RATIO_EPSILON = 0.0001f

/** One split selected for a resize gesture together with its persisted base ratio and size limits. */
internal data class TilingResizeHandle(
    val splitId: SplitId,
    val orientation: Orientation,
    val inFirst: Boolean,
    val extentPx: Int,
    val baseRatio: Float,
    val minRatio: Float,
    val maxRatio: Float,
)

/** Absolute ratio that should be persisted for one split when the gesture ends. */
internal data class TilingResizeRatio(val splitId: SplitId, val ratio: Float)

/**
 * Transient state of one two-finger resize. The main engine is never mutated: [preview] owns an
 * independent snapshot, and every update derives ratios from the base ratios and the total finger
 * travel since the gesture started.
 */
internal class TilingResizePreview(
    val windowId: WindowId,
    val vertical: TilingResizeHandle?,
    val horizontal: TilingResizeHandle?,
    private val initialSpanX: Float,
    private val initialSpanY: Float,
    private val preview: WorkspaceEngine,
) {
    /** Ratios for the current spans; horizontal participates only after a diagonal gesture is active. */
    fun ratios(spanX: Float, spanY: Float, horizontalActive: Boolean): List<TilingResizeRatio> = buildList {
        vertical?.let { add(TilingResizeRatio(it.splitId, tilingResizeRatio(it, initialSpanY, spanY))) }
        if (horizontalActive) horizontal?.let { add(TilingResizeRatio(it.splitId, tilingResizeRatio(it, initialSpanX, spanX))) }
    }

    /** Applies the absolute ratios to the preview clone and returns a fresh Compose snapshot. */
    fun update(spanX: Float, spanY: Float, horizontalActive: Boolean): WorkspaceEngine {
        ratios(spanX, spanY, horizontalActive).forEach { preview.resizeSplit(it.splitId, it.ratio) }
        return preview.snapshot()
    }

    /** Ratios that actually changed and therefore have to be persisted through the ViewModel. */
    fun changedRatios(spanX: Float, spanY: Float, horizontalActive: Boolean): List<TilingResizeRatio> {
        val handles = listOfNotNull(vertical, horizontal).associateBy { it.splitId }
        return ratios(spanX, spanY, horizontalActive).filter { ratio ->
            val handle = handles[ratio.splitId]
            handle != null && abs(ratio.ratio - handle.baseRatio) > RATIO_EPSILON
        }
    }
}

/**
 * Captures one resize gesture: target window, both axis handles, the initial finger spans and an
 * independent engine snapshot. Handles are resolved once here and never re-selected while moving.
 */
internal fun beginTilingResize(
    engine: WorkspaceEngine,
    windowId: WindowId,
    area: LayoutArea,
    mins: Map<WindowId, WindowMin>,
    initialSpanX: Float,
    initialSpanY: Float,
    placed: List<PlacedWindow> = engine.calculateLayout(area, mins),
    splits: List<WorkspaceEngine.SplitPlacement> = engine.splitsWithRects(
        engine.root,
        LayoutRect(x = 0, y = 0, width = area.widthPx, height = area.heightPx),
        mins,
    ),
): TilingResizePreview = TilingResizePreview(
    windowId = windowId,
    vertical = selectTilingResizeHandle(engine, windowId, Orientation.VERTICAL, placed, splits, mins),
    horizontal = selectTilingResizeHandle(engine, windowId, Orientation.HORIZONTAL, placed, splits, mins),
    initialSpanX = initialSpanX,
    initialSpanY = initialSpanY,
    preview = engine.snapshot(),
)

/**
 * Picks the smallest split of [orientation] whose first or second rect contains the centre of the
 * target window. The same deterministic rule is used once at gesture start for each axis.
 */
internal fun selectTilingResizeHandle(
    engine: WorkspaceEngine,
    windowId: WindowId,
    orientation: Orientation,
    placed: List<PlacedWindow>,
    splits: List<WorkspaceEngine.SplitPlacement>,
    mins: Map<WindowId, WindowMin>,
): TilingResizeHandle? {
    val target = placed.firstOrNull { it.windowId == windowId }?.rect ?: return null
    val centerX = target.x + target.width / 2f
    val centerY = target.y + target.height / 2f

    fun contains(rect: LayoutRect): Boolean =
        centerX >= rect.x && centerX < rect.x + rect.width &&
            centerY >= rect.y && centerY < rect.y + rect.height

    data class Candidate(val placement: WorkspaceEngine.SplitPlacement, val inFirst: Boolean, val extent: Int)

    val candidate = splits.asSequence()
        .filter { it.split.orientation == orientation }
        .mapNotNull { placement ->
            val inFirst = contains(placement.firstRect)
            val inSecond = contains(placement.secondRect)
            if (!inFirst && !inSecond) return@mapNotNull null
            val extent = if (orientation == Orientation.HORIZONTAL) {
                placement.firstRect.width + placement.secondRect.width
            } else {
                placement.firstRect.height + placement.secondRect.height
            }
            if (extent <= 0) return@mapNotNull null
            Candidate(placement, inFirst, extent)
        }
        .minByOrNull { it.extent }
        ?: return null

    val bounds = engine.splitRatioBounds(candidate.placement, mins)
        ?: (WorkspaceEngine.MIN_RATIO..WorkspaceEngine.MAX_RATIO)
    return TilingResizeHandle(
        splitId = candidate.placement.split.id,
        orientation = orientation,
        inFirst = candidate.inFirst,
        extentPx = candidate.extent,
        baseRatio = candidate.placement.split.ratio,
        minRatio = bounds.start,
        maxRatio = bounds.endInclusive,
    )
}

/**
 * Absolute ratio for the current finger span. It is always based on [TilingResizeHandle.baseRatio]
 * plus the total travel divided by the split extent, so repeated pointer events never accumulate error.
 */
internal fun tilingResizeRatio(handle: TilingResizeHandle, initialSpanPx: Float, currentSpanPx: Float): Float {
    val direction = if (handle.inFirst) 1f else -1f
    val raw = handle.baseRatio + direction * (currentSpanPx - initialSpanPx) / handle.extentPx
    return raw.coerceIn(handle.minRatio, handle.maxRatio)
}
