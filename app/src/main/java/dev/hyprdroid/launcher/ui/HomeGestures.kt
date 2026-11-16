package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private enum class BlankStart { CANCEL, VERTICAL }
private data class BlankActions(val enabled: Boolean, val swipes: Boolean, val edit: () -> Unit, val up: () -> Unit, val down: () -> Unit, val tap: () -> Unit)

/** Observe child consumption before claiming a gesture; never install on the drawer or dock. */
@Composable
fun Modifier.homeBlankGestures(enabled: Boolean, swipes: Boolean, onEdit: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onTap: () -> Unit = {}): Modifier {
    val actions by rememberUpdatedState(BlankActions(enabled, swipes, onEdit, onUp, onDown, onTap))
    val density = LocalDensity.current
    val edge = with(density) { 16.dp.toPx() }
    val distance = with(density) { 64.dp.toPx() }
    return pointerInput(edge, distance) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (down.isConsumed || !actions.enabled) return@awaitEachGesture
            val safeSwipeOrigin = down.position.x in edge..(size.width - edge) && down.position.y in edge..(size.height - edge)
            var travel = Offset.Zero
            val start = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.find { it.id == down.id }
                    if (event.changes.size != 1 || change == null || !actions.enabled) return@withTimeoutOrNull BlankStart.CANCEL
                    if (!change.pressed) {
                        if (!change.isConsumed && (change.position - down.position).getDistance() < viewConfiguration.touchSlop) actions.tap()
                        return@withTimeoutOrNull BlankStart.CANCEL
                    }
                    travel = change.position - down.position
                    val x = abs(travel.x)
                    val y = abs(travel.y)
                    if (x > viewConfiguration.touchSlop && x >= y) return@withTimeoutOrNull BlankStart.CANCEL
                    if (y > viewConfiguration.touchSlop && y > x * 1.3f) {
                        if (!actions.swipes || !safeSwipeOrigin) return@withTimeoutOrNull BlankStart.CANCEL
                        change.consume()
                        return@withTimeoutOrNull BlankStart.VERTICAL
                    }
                    // The grid/pager may take an ambiguous or already-consumed gesture.
                    if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) return@withTimeoutOrNull BlankStart.CANCEL
                }
                @Suppress("UNREACHABLE_CODE") BlankStart.CANCEL
            }
            if (start == null) {
                actions.edit()
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } else if (start == BlankStart.VERTICAL) {
                var singlePointer = true
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.find { it.id == down.id }
                    if (event.changes.size != 1 || change == null) singlePointer = false
                    if (change != null) travel = change.position - down.position
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                if (singlePointer && actions.enabled && abs(travel.y) >= distance && abs(travel.y) > abs(travel.x) * 1.3f) {
                    if (travel.y < 0) actions.up() else actions.down()
                }
            }
        }
    }
}

/** Catch otherwise unhandled taps so a full-screen in-app panel cannot hit the home behind it. */
fun Modifier.blockHomeBehind(): Modifier = pointerInput(Unit) { detectTapGestures(onTap = {}) }
