package dev.hyprdroid.launcher.model

/** Provider constraints in dp. No scaling of RemoteViews or changes to stored user preferences. */
data class PanelWidgetLimits(val minWidth: Float, val minHeight: Float, val maxWidth: Float = 0f,
    val maxHeight: Float = 0f, val horizontal: Boolean = true, val vertical: Boolean = true)
data class PanelWidgetSize(val width: Float, val height: Float, val tooNarrow: Boolean)
fun fitPanelWidget(widget: PanelWidget, availableWidth: Float, limits: PanelWidgetLimits): PanelWidgetSize {
    val available = availableWidth.coerceAtLeast(1f)
    val minWidth = limits.minWidth.coerceAtLeast(1f)
    val maxWidth = if (limits.horizontal && limits.maxWidth > 0) limits.maxWidth.coerceAtLeast(minWidth) else available
    val width = (if (limits.horizontal) available * widget.widthPercent / 100 else minWidth)
        .coerceAtLeast(minWidth).coerceAtMost(maxWidth).coerceAtMost(available)
    val minHeight = limits.minHeight.coerceIn(1f, 600f)
    val maxHeight = if (limits.vertical && limits.maxHeight > 0) limits.maxHeight.coerceIn(minHeight, 600f) else 600f
    val height = (if (limits.vertical) widget.heightDp.toFloat() else minHeight).coerceIn(minHeight, maxHeight)
    return PanelWidgetSize(width, height, minWidth > available)
}
