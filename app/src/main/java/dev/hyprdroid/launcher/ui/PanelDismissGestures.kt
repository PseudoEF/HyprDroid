package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal enum class PanelDismissDirection(val sign: Float) { UP(-1f), DOWN(1f) }

/** A scroll can become a dismiss pull only if it starts at the content boundary. */
internal class PanelPull(private val sign: Float, private val threshold: Float) {
    private var eligible: Boolean? = null
    private var distance = 0f
    fun reset() { eligible = null; distance = 0f }
    fun scroll(delta: Float, atBoundary: Boolean): Float {
        if (eligible == null) eligible = atBoundary && delta * sign > 0
        if (eligible != true || !atBoundary) return 0f
        val next = (distance + delta * sign).coerceAtLeast(0f)
        val consumed = (next - distance) * sign
        distance = next
        return consumed
    }
    fun finish(atBoundary: Boolean): Boolean {
        val dismiss = eligible == true && atBoundary && distance >= threshold
        reset()
        return dismiss
    }
    val pulling: Boolean get() = distance > 0
}

/** Blank areas use pointer arbitration; scrollable children hand off a boundary pull via nested scroll. */
@Composable
internal fun Modifier.panelDismissGestures(
    direction: PanelDismissDirection,
    canDismiss: () -> Boolean = { true },
    onDismiss: () -> Unit,
    header: Boolean = false,
): Modifier {
    val allowed by rememberUpdatedState(canDismiss)
    val dismiss by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    // An upward header swipe must fit above the title even on phones without a cutout.
    val threshold = with(density) { (if (header && direction == PanelDismissDirection.UP) 24.dp else 64.dp).toPx() }
    val edge = with(density) { 16.dp.toPx() }
    val pull = remember(direction, threshold) { PanelPull(direction.sign, threshold) }
    val connection = remember(pull) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            return Offset(0f, pull.scroll(available.y, allowed()))
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            val wasPulling = pull.pulling
            if (pull.finish(allowed())) dismiss()
            return if (wasPulling) available else Velocity.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            pull.reset()
            return Velocity.Zero
        }
    } }
    return nestedScroll(connection).pointerInput(pull, edge, header) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            pull.reset()
            if (down.isConsumed || !allowed() || down.position.x !in edge..(size.width - edge) ||
                down.position.y !in edge..(size.height - edge)) return@awaitEachGesture
            if (header) down.consume()
            var travel = Offset.Zero
            var claimed = false
            var cancelled = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.find { it.id == down.id }
                if (event.changes.size != 1 || change == null || change.isConsumed || (!header && down.isConsumed)) { cancelled = true; break }
                travel = change.position - down.position
                if (!change.pressed) {
                    if (claimed) change.consume()
                    break
                }
                if (!claimed) {
                    if (abs(travel.x) > viewConfiguration.touchSlop && abs(travel.x) >= abs(travel.y)) { cancelled = true; break }
                    if (abs(travel.y) > viewConfiguration.touchSlop) {
                        if (travel.y * direction.sign <= 0 || abs(travel.y) <= abs(travel.x) * 1.3f) { cancelled = true; break }
                        claimed = true
                    }
                }
                if (claimed) change.consume()
                else if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) { cancelled = true; break }
            }
            if (claimed && !cancelled && allowed() && travel.y * direction.sign >= threshold && abs(travel.y) > abs(travel.x) * 1.3f) dismiss()
        }
    }
}
