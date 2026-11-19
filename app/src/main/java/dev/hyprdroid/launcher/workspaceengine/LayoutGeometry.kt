package dev.hyprdroid.launcher.workspaceengine

/** Available area in pixels for one layout pass. */
internal data class LayoutArea(val widthPx: Int, val heightPx: Int)

/** Minimum size of one window in pixels, prepared by the UI layer from dp. */
internal data class WindowMin(val widthPx: Int, val heightPx: Int)

/** Pixel rectangle inside the available area. */
internal data class LayoutRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** Placed window with the constraint information produced by the layout. */
internal data class PlacedWindow(
    val windowId: WindowId,
    val rect: LayoutRect,
    val minWidthPx: Int,
    val minHeightPx: Int,
    val constrained: Boolean,
)
