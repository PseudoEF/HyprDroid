package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/** A wider internal band, separated from both Android Back and the page boundary. No overlay hit target. */
@Composable
internal fun Modifier.sidebarOpeningGesture(enabled: Boolean, onOpen: () -> Unit): Modifier {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val inset = maxOf(WindowInsets.systemGestures.getRight(density, direction), WindowInsets.mandatorySystemGestures.getRight(density, direction))
    val right = inset + with(density) { 32.dp.toPx() }
    val band = with(density) { 40.dp.toPx() }
    val edge = with(density) { 24.dp.toPx() }
    return sidebarSwipeGesture(enabled, -1f, { width, height -> SidebarActivationZone(width - right - band, width - right, edge, height - edge) }, onOpen)
}

@Composable
internal fun Modifier.sidebarClosingGesture(enabled: Boolean = true, sign: Float = 1f, onClose: () -> Unit): Modifier =
    sidebarSwipeGesture(enabled, sign, { width, height -> SidebarActivationZone(0f, width, 0f, height) }, onClose)

@Composable
internal fun Modifier.sidebarSwipeGesture(enabled: Boolean, sign: Float, zone: (Float, Float) -> SidebarActivationZone, action: () -> Unit): Modifier {
    val active by rememberUpdatedState(enabled)
    val bounds by rememberUpdatedState(zone)
    val complete by rememberUpdatedState(action)
    val density = LocalDensity.current
    val threshold = with(density) { 48.dp.toPx() }
    val minimumLock = with(density) { 4.dp.toPx() }
    return pointerInput(sign, threshold, minimumLock) {
        awaitEachGesture {
            val down = awaitFirstDown(false, PointerEventPass.Final)
            if (!active || down.isConsumed || !bounds(size.width.toFloat(), size.height.toFloat()).contains(down.position.x, down.position.y)) return@awaitEachGesture
            // Lock a clear horizontal intention before pager reaches its full touch slop.
            // Pending/vertical streams remain unconsumed for home gestures and child scrolling.
            val policy = SidebarSwipePolicy(sign, maxOf(minimumLock, viewConfiguration.touchSlop * .5f), threshold)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.find { it.id == down.id }
                    if (!active || event.changes.size != 1 || change == null || change.isConsumed) break
                    val travel = change.position - down.position
                    policy.move(travel.x, travel.y)
                    if (policy.rejected) break
                    if (!change.pressed) {
                        if (policy.owned) change.consume()
                        if (policy.finish(event.type == PointerEventType.Release)) complete()
                        break
                    }
                    if (policy.owned) change.consume()
                    else if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) break
                }
            } finally { policy.cancel() }
        }
    }
}
