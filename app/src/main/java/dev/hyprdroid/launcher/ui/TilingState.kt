package dev.hyprdroid.launcher.ui

import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import dev.hyprdroid.launcher.workspaceengine.WorkspaceWindow
import dev.hyprdroid.launcher.workspaceengine.WindowId
import dev.hyprdroid.launcher.workspaceengine.WindowMin

/**
 * Compose-facing view of one internal tiling session. The engine instance is an immutable snapshot
 * published by the ViewModel; the surface never mutates it.
 */
internal data class TilingState(
    val open: Boolean = false,
    val workspaceId: Int = 0,
    val focusedWindowId: WindowId? = null,
    val engine: WorkspaceEngine = WorkspaceEngine(),
)

/** Normalized divider ratio derived from the first-child size and the split area; clamped to the engine range. */
internal fun tilingRatio(availablePx: Int, firstSizePx: Int): Float {
    val available = availablePx.coerceAtLeast(0)
    if (available == 0) return WorkspaceEngine.MIN_RATIO
    val ratio = firstSizePx.coerceIn(0, available).toFloat() / available
    return ratio.coerceIn(WorkspaceEngine.MIN_RATIO, WorkspaceEngine.MAX_RATIO)
}

/** Minimum pixel sizes for one internal window derived from its dp manifest at the given density scale. */
internal fun tilingWindowMin(window: WorkspaceWindow, densityScale: Float): WindowMin = WindowMin(
    widthPx = (window.minWidthDp * densityScale).toInt(),
    heightPx = (window.minHeightDp * densityScale).toInt(),
)
