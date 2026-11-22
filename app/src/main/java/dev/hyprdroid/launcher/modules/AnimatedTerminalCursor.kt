package dev.hyprdroid.launcher.modules

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.ui.Mocha
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal data class CursorTarget(
    val x: Float,
    val top: Float,
    val bottom: Float,
)

internal data class CursorTrail(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/** Pure geometry: bounding box between the previous and the current caret, stretched across rows. */
internal fun cursorTrail(
    from: CursorTarget,
    to: CursorTarget,
    minWidth: Float = 0f,
): CursorTrail {
    val left = minOf(from.x, to.x)
    val right = maxOf(from.x, to.x) + maxOf(minWidth, 0f)
    val top = minOf(from.top, to.top)
    val bottom = maxOf(from.bottom, to.bottom)
    // Identical carets have no trail; the caret block still keeps its own height.
    val height = if (from == to) 0f else maxOf(bottom - top, 0f)
    return CursorTrail(
        left = left,
        top = top,
        width = maxOf(right - left, 0f),
        height = height,
    )
}

@Composable
internal fun AnimatedTerminalCursor(
    target: CursorTarget?,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val cursorX = remember { Animatable(0f) }
    val cursorTop = remember { Animatable(0f) }
    val cursorBottom = remember { Animatable(0f) }
    val trailAlpha = remember { Animatable(0f) }
    val previousTarget = remember { mutableStateOf<CursorTarget?>(null) }
    val lastTarget = remember { mutableStateOf<CursorTarget?>(null) }

    val blink = rememberInfiniteTransition(label = "terminal-cursor-blink")
    val blinkAlpha by blink.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 620,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "terminal-cursor-blink-alpha",
    )

    LaunchedEffect(target) {
        val safeTarget = target?.sanitized(
            fallbackX = cursorX.value,
            fallbackTop = cursorTop.value,
            fallbackBottom = cursorBottom.value,
        )
        if (safeTarget == null) {
            previousTarget.value = null
            lastTarget.value = null
            trailAlpha.snapTo(0f)
            return@LaunchedEffect
        }

        val from = lastTarget.value
        lastTarget.value = safeTarget
        previousTarget.value = from ?: safeTarget

        if (from == null) {
            cursorX.snapTo(safeTarget.x)
            cursorTop.snapTo(safeTarget.top)
            cursorBottom.snapTo(safeTarget.bottom)
            trailAlpha.snapTo(0f)
            return@LaunchedEffect
        }

        trailAlpha.snapTo(1f)
        coroutineScope {
            launch {
                cursorX.animateTo(
                    targetValue = safeTarget.x,
                    animationSpec = tween(
                        durationMillis = 180,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            launch {
                cursorTop.animateTo(
                    targetValue = safeTarget.top,
                    animationSpec = tween(
                        durationMillis = 180,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            launch {
                cursorBottom.animateTo(
                    targetValue = safeTarget.bottom,
                    animationSpec = tween(
                        durationMillis = 180,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            launch {
                trailAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = 280,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        }
    }

    Canvas(modifier = modifier) {
        if (target == null || !visible) return@Canvas

        val canvasWidth = size.width
        val canvasHeight = size.height
        val rawTop = cursorTop.value.finiteOr(0f)
        val rawBottom = cursorBottom.value.finiteOr(rawTop)
        val currentTop = rawTop.coerceIn(0f, canvasHeight)
        val currentBottom = rawBottom.coerceIn(currentTop, canvasHeight)
        val currentX = cursorX.value.finiteOr(0f).coerceIn(0f, canvasWidth)
        val blockWidth = 7.dp.toPx()

        val previous = previousTarget.value
        val trailAlphaValue = trailAlpha.value
        if (previous != null && trailAlphaValue > 0.001f) {
            val from = previous.clamped(
                maxWidth = canvasWidth,
                maxHeight = canvasHeight,
            )
            val trail = cursorTrail(
                from = from,
                to = CursorTarget(
                    x = currentX,
                    top = currentTop,
                    bottom = currentBottom,
                ),
                minWidth = blockWidth,
            )
            if (trail.width > 0f && trail.height > 0f) {
                drawRect(
                    color = Mocha.Mauve.copy(alpha = 0.12f * trailAlphaValue),
                    topLeft = Offset(trail.left, trail.top),
                    size = Size(trail.width, trail.height),
                )
                drawRect(
                    color = Mocha.Mauve.copy(alpha = 0.35f * trailAlphaValue),
                    topLeft = Offset(trail.left, trail.top),
                    size = Size(trail.width, trail.height),
                )
            }
        }

        val blockHeight = currentBottom - currentTop
        if (blockHeight > 0f && blockWidth > 0f) {
            drawRect(
                color = Mocha.Mauve.copy(alpha = blinkAlpha),
                topLeft = Offset(currentX, currentTop),
                size = Size(blockWidth, blockHeight),
            )
        }
    }
}

private fun CursorTarget.sanitized(
    fallbackX: Float,
    fallbackTop: Float,
    fallbackBottom: Float,
): CursorTarget {
    val safeX = x.finiteOr(fallbackX)
    val safeTop = top.finiteOr(fallbackTop)
    val safeBottom = bottom.finiteOr(fallbackBottom)
    return CursorTarget(
        x = safeX,
        top = safeTop,
        bottom = maxOf(safeBottom, safeTop),
    )
}

private fun CursorTarget.clamped(maxWidth: Float, maxHeight: Float): CursorTarget {
    val safeTop = top.coerceIn(0f, maxHeight)
    val safeBottom = bottom.coerceIn(safeTop, maxHeight)
    return CursorTarget(
        x = x.coerceIn(0f, maxWidth),
        top = safeTop,
        bottom = safeBottom,
    )
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
