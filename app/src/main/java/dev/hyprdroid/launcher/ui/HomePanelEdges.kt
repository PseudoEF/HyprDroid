package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.model.*

internal fun panelZone(width: Float, height: Float, density: Float, side: PanelSide, value: PanelSettings): SidebarActivationZone {
    val h = (value.heightDp * density).coerceAtMost(height * .5f)
    val top = ((height - h) * value.positionPercent / 100).coerceIn(0f, (height - h).coerceAtLeast(0f))
    val x = if (side == PanelSide.LEFT) value.insetDp * density else width - (value.insetDp + value.touchWidthDp) * density
    return SidebarActivationZone(x, x + value.touchWidthDp * density, top, top + h)
}

/** Parent observes unconsumed child input, so icons/buttons keep taps and long presses. */
@Composable
internal fun Modifier.homePanelGesture(side: PanelSide, value: PanelSettings, enabled: Boolean, open: () -> Unit): Modifier {
    val density = LocalDensity.current.density
    val zone = { w: Float, h: Float -> panelZone(w, h, density, side, value) }
    val input = sidebarSwipeGesture(enabled, if (side == PanelSide.LEFT) 1f else -1f, zone, open,
        thresholdDp = value.swipeThresholdDp, tapEnabled = value.activation == PanelActivation.TAP_SWIPE)
    return if (!enabled) input else input.systemGestureExclusion { coordinates ->
        val area = zone(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
        Rect(area.left, area.top, area.right, area.bottom)
    }
}

@Composable
internal fun HomePanelMarkers(panels: PanelsSettings) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        PanelSide.entries.forEach { side ->
            val value = panels[side]
            if (value.scope.home && value.visibility != MarkerVisibility.HIDDEN) {
                val height = minOf(value.heightDp.dp, maxHeight * .5f)
                val top = (maxHeight - height) * (value.positionPercent / 100f)
                Box(Modifier.align(if (side == PanelSide.LEFT) AbsoluteAlignment.TopLeft else AbsoluteAlignment.TopRight)
                    .absoluteOffset(x = (if (side == PanelSide.LEFT) value.insetDp else -value.insetDp).dp, y = top)
                    .requiredWidth(value.markerWidthDp.dp).requiredHeight(height)
                    .background(Mocha.Mauve.copy(alpha = value.visibility.alpha / 255f), RoundedCornerShape(8.dp))
                    .testTag("home-marker-${side.name}"))
            }
        }
    }
}
