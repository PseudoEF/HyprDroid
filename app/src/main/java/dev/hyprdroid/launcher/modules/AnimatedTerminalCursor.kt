package dev.hyprdroid.launcher.modules

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.ui.Mocha
import kotlin.math.abs
import kotlin.math.ceil

internal data class CursorTarget(
    val x: Float,
    val top: Float,
    val bottom: Float,
)

/** Maps the text layout's caret to the viewport. Its origin already accounts for vertical scroll. */
internal fun viewportCaret(
    caret: Rect,
    textOrigin: Offset,
    horizontalScroll: Int = 0,
): CursorTarget? {
    val x = textOrigin.x + caret.left - horizontalScroll
    val top = textOrigin.y + caret.top
    val bottom = textOrigin.y + caret.bottom
    return if (x.isFinite() && top.isFinite() && bottom.isFinite() && bottom > top) {
        CursorTarget(x, top, bottom)
    } else {
        null
    }
}

internal fun inlineCursorPlacement(
    caret: Rect,
    textOrigin: Offset,
    viewportHeight: Int,
    row: Long,
    visibleRows: Map<Long, Int>,
    inputScroll: Int? = null,
): CursorPlacement? {
    if (row !in visibleRows || viewportHeight <= 0) return null
    val target = viewportCaret(caret, textOrigin, inputScroll ?: 0)
        ?.takeIf { it.bottom > 0f && it.top < viewportHeight } ?: return null
    return CursorPlacement(target, row, visibleRows, inputScroll)
}

internal data class CursorPlacement(
    val target: CursorTarget,
    val row: Long? = null,
    val rowOffsets: Map<Long, Int> = emptyMap(),
    val inputScroll: Int? = null,
) {
    /** Keeps the spring and its past samples anchored to the same content during a scroll. */
    fun displacementFrom(previous: CursorPlacement): Offset {
        val x = if (inputScroll != null && previous.inputScroll != null) {
            (previous.inputScroll - inputScroll).toFloat()
        } else 0f
        // Appending output moves the prompt relative to the text. Prefer a common log row
        // when the previous target was the prompt, so only actual scroll shifts the trail.
        val anchor = if (previous.inputScroll != null) {
            previous.rowOffsets.keys.lastOrNull { it != previous.row && it in rowOffsets }
                ?: previous.row.takeIf { inputScroll != null }
        } else previous.row
        val oldOffset = previous.rowOffsets[anchor]
        val newOffset = rowOffsets[anchor]
        val y = if (oldOffset != null && newOffset != null) (newOffset - oldOffset).toFloat() else 0f
        return Offset(x, y)
    }
}

private fun CursorTarget.translate(by: Offset) =
    CursorTarget(x + by.x, top + by.y, bottom + by.y)

internal data class CursorMotion(
    val position: CursorTarget,
    val velocity: Offset = Offset.Zero,
) {
    fun translate(by: Offset) = copy(position = position.translate(by))

    /** Semi-implicit spring with bounded substeps to keep retargeting stable across frame rates. */
    fun advance(target: CursorTarget, seconds: Float): CursorMotion {
        val elapsed = seconds.coerceIn(0f, 0.05f)
        val steps = ceil(elapsed / 0.008f).toInt().coerceAtLeast(1)
        val dt = elapsed / steps
        val goal = Offset(target.x, target.top)
        var point = Offset(position.x, position.top)
        var speed = velocity
        repeat(steps) {
            speed += ((goal - point) * 500f - speed * 32f) * dt
            point += speed * dt
        }
        if ((goal - point).getDistance() < 0.15f && speed.getDistance() < 2f) {
            return CursorMotion(target)
        }
        return CursorMotion(
            CursorTarget(point.x, point.y, point.y + target.bottom - target.top),
            speed,
        )
    }
}

private data class CursorScene(val placement: CursorPlacement?, val tracking: Boolean)

private data class CursorGhost(val position: CursorTarget, val time: Long)
private const val TRAIL_DURATION_NS = 180_000_000L
private const val SAMPLE_INTERVAL_NS = 24_000_000L

@Composable
internal fun AnimatedTerminalCursor(
    placement: CursorPlacement?,
    tracking: Boolean,
    modifier: Modifier = Modifier,
) {
    val latestScene = rememberUpdatedState(CursorScene(placement, tracking))
    var motion by remember { mutableStateOf<CursorMotion?>(null) }
    var ghosts by remember { mutableStateOf(emptyList<CursorGhost>()) }
    var frameTime by remember { mutableStateOf(0L) }

    // The effect lasts as long as the Canvas. New goals are sampled by the active frame loop.
    LaunchedEffect(Unit) {
        var previousPlacement: CursorPlacement? = null
        val durationScale = coroutineContext[MotionDurationScale]
        snapshotFlow { latestScene.value }.collect {
            var previousFrame: Long? = null
            while (true) {
                val scene = latestScene.value
                if (!scene.tracking) {
                    motion = null
                    ghosts = emptyList()
                    previousPlacement = null
                    break
                }
                val next = scene.placement ?: break
                val goal = next.target
                val displacement = previousPlacement?.let(next::displacementFrom) ?: Offset.Zero
                previousPlacement = next
                val scale = durationScale?.scaleFactor ?: 1f
                if (motion == null || scale == 0f) {
                    motion = CursorMotion(goal)
                    ghosts = emptyList()
                    break
                }
                if (displacement != Offset.Zero) {
                    motion = motion?.translate(displacement)
                    ghosts = ghosts.map { it.copy(position = it.position.translate(displacement)) }
                }
                val current = checkNotNull(motion)
                if (current.position == goal && current.velocity == Offset.Zero && ghosts.isEmpty()) break

                withFrameNanos { now ->
                    val elapsed = previousFrame?.let { (now - it) / 1_000_000_000f / scale }
                    if (elapsed != null) {
                        motion = current.advance(goal, elapsed)
                        val remaining = ghosts.dropWhile { now - it.time >= TRAIL_DURATION_NS }
                        ghosts = if (current.position != motion?.position &&
                            now - (remaining.lastOrNull()?.time ?: 0L) >= SAMPLE_INTERVAL_NS
                        ) {
                            remaining + CursorGhost(current.position, now)
                        } else {
                            remaining
                        }
                        frameTime = now
                    }
                    previousFrame = now
                }
            }
        }
    }

    Canvas(modifier) {
        val current = motion ?: return@Canvas
        if (placement == null) return@Canvas
        val width = 3.dp.toPx()
        for (sample in ghosts) {
            val alpha = 1f - (frameTime - sample.time).toFloat() / TRAIL_DURATION_NS
            val point = sample.position
            drawRoundRect(
                color = Mocha.Mauve.copy(alpha = 0.30f * alpha * alpha),
                topLeft = Offset(point.x - width / 2, point.top),
                size = Size(width, point.bottom - point.top),
            )
        }
        val point = current.position
        val stretchX = (abs(current.velocity.x) * 0.008f).coerceAtMost(5.dp.toPx())
        val stretchY = (abs(current.velocity.y) * 0.008f).coerceAtMost(5.dp.toPx())
        drawRoundRect(
            color = Mocha.Mauve,
            topLeft = Offset(point.x - (width + stretchX) / 2, point.top - stretchY / 2),
            size = Size(width + stretchX, point.bottom - point.top + stretchY),
        )
    }
}
