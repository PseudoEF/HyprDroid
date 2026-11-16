package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** No overlay hit target: children receive down first. Only a blank internal band can open Sidebar. */
@Composable
internal fun Modifier.sidebarOpeningGesture(enabled: Boolean, onOpen: () -> Unit): Modifier {
    val active by rememberUpdatedState(enabled)
    val open by rememberUpdatedState(onOpen)
    val density = LocalDensity.current
    val inset = WindowInsets.systemGestures.getRight(density, LocalLayoutDirection.current)
    val right = inset + with(density) { 32.dp.toPx() }
    val band = with(density) { 24.dp.toPx() }
    val threshold = with(density) { 48.dp.toPx() }
    return pointerInput(right, band, threshold) {
        awaitEachGesture {
            val down = awaitFirstDown(false, PointerEventPass.Final)
            if (!active || down.isConsumed || down.position.x !in (size.width - right - band)..(size.width - right)) return@awaitEachGesture
            var travel = Offset.Zero
            var claimed = false
            var valid = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.find { it.id == down.id }
                if (!active || event.changes.size != 1 || change == null || change.isConsumed) { valid = false; break }
                travel = change.position - down.position
                if (!change.pressed) { if (claimed) change.consume(); break }
                if (!claimed && abs(travel.y) > viewConfiguration.touchSlop && abs(travel.y) >= abs(travel.x)) { valid = false; break }
                if (!claimed && abs(travel.x) > viewConfiguration.touchSlop) {
                    if (travel.x >= 0 || abs(travel.x) <= abs(travel.y) * 1.3f) { valid = false; break }
                    claimed = true
                }
                if (claimed) change.consume()
                else if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) { valid = false; break }
            }
            if (valid && claimed && -travel.x >= threshold) open()
        }
    }
}
