package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.IntOffset
import dev.hyprdroid.launcher.model.WorkspaceItem
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Draft order belongs to this canvas; only a completed drag writes to DataStore. */
@Stable
internal class WorkspaceDragState(val grid: LazyGridState, private val reserveRemoveTarget: Boolean = true) {
    var items by mutableStateOf(emptyList<WorkspaceItem>())
    var draggedId by mutableStateOf<String?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    private var grab = Offset.Zero
    private var original = emptyList<WorkspaceItem>()
    var onActive: (Boolean) -> Unit = {}
    var onDrop: (String, String?) -> Unit = { _, _ -> }

    fun start(position: Offset) {
        val hit = grid.layoutInfo.visibleItemsInfo.firstOrNull {
            position.x >= it.offset.x && position.x < it.offset.x + it.size.width &&
                position.y >= it.offset.y && position.y < it.offset.y + it.size.height
        } ?: return
        // Reserve the remove button's touch target.
        if (reserveRemoveTarget && position.x > hit.offset.x + hit.size.width - 48 * gridDensity && position.y < hit.offset.y + 48 * gridDensity) return
        val item = items.find { it.id == hit.key } ?: return
        if (item !is WorkspaceItem.AppShortcut) return
        original = items
        pointer = position
        grab = position - Offset(hit.offset.x.toFloat(), hit.offset.y.toFloat())
        draggedId = item.id
        onActive(true)
    }

    var gridDensity = 1f
    fun move(delta: Offset) { if (draggedId != null) { pointer += delta; retarget() } }
    fun retarget() {
        val id = draggedId ?: return
        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != id && pointer.x >= it.offset.x && pointer.x < it.offset.x + it.size.width &&
                pointer.y >= it.offset.y && pointer.y < it.offset.y + it.size.height
        } ?: return
        val from = items.indexOfFirst { it.id == id }
        val to = items.indexOfFirst { it.id == target.key }
        if (from >= 0 && to >= 0) {
            // Keep the viewport fixed instead of following the first visible key as it moves.
            grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
            items = items.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    fun translation(id: String): IntOffset {
        if (draggedId != id) return IntOffset.Zero
        val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return IntOffset.Zero
        return IntOffset((pointer.x - grab.x - info.offset.x).roundToInt(), (pointer.y - grab.y - info.offset.y).roundToInt())
    }

    fun finish(commit: Boolean) {
        val id = draggedId ?: return
        if (commit && original.map { it.id } != items.map { it.id }) {
            val index = items.indexOfFirst { it.id == id }
            onDrop(id, items.getOrNull(index + 1)?.id)
        }
        if (!commit) items = original
        draggedId = null
        onActive(false)
    }
}

internal fun Modifier.workspaceDrag(state: WorkspaceDragState, enabled: Boolean): Modifier =
    if (!enabled) this else pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            state.start(held.position)
            if (state.draggedId == null) return@awaitEachGesture
            held.consume()
            try {
                while (true) {
                    // Once held, claim movement before the grid's ordinary scrolling sees it.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.find { it.id == held.id }
                    if (event.changes.size != 1 || change == null || change.isConsumed) break
                    if (!change.pressed) { change.consume(); state.finish(true); break }
                    state.move(change.position - change.previousPosition)
                    change.consume()
                }
            } finally { state.finish(false) }
        }
    }

@Composable
internal fun DragAutoScroll(state: WorkspaceDragState, edgePx: Float) {
    LaunchedEffect(state.draggedId) {
        if (state.draggedId == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
            previous = now
            val top = state.grid.layoutInfo.viewportStartOffset
            val bottom = state.grid.layoutInfo.viewportEndOffset
            val y = state.pointer.y
            val speed = when {
                y < top + edgePx -> -((top + edgePx - y) / edgePx).coerceIn(0f, 1f)
                y > bottom - edgePx -> ((y - bottom + edgePx) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) { state.grid.scrollBy(speed * edgePx * 8 * dt); state.retarget() }
        }
    }
}
