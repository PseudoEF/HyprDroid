package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.sidebarClosingGesture(enabled: Boolean = true, sign: Float = 1f, onClose: () -> Unit): Modifier =
    sidebarSwipeGesture(enabled, sign, { width, height -> SidebarActivationZone(0f, width, 0f, height) }, onClose)

@Composable
internal fun Modifier.sidebarSwipeGesture(enabled: Boolean, sign: Float, zone: (Float, Float) -> SidebarActivationZone, action: () -> Unit,
    thresholdDp: Int = 48, tapEnabled: Boolean = false): Modifier {
    val active by rememberUpdatedState(enabled)
    val bounds by rememberUpdatedState(zone)
    val complete by rememberUpdatedState(action)
    val tap by rememberUpdatedState(tapEnabled)
    val density = LocalDensity.current
    val threshold = with(density) { thresholdDp.dp.toPx() }
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
                        val released = event.type == PointerEventType.Release
                        val isTap = !policy.owned && released && tap && travel.getDistance() < viewConfiguration.touchSlop
                        if (policy.finish(released) || isTap) complete()
                        break
                    }
                    if (policy.owned) change.consume()
                    else if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) break
                }
            } finally { policy.cancel() }
        }
    }
}
