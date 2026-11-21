package dev.hyprdroid.launcher.modules

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.ui.Mocha
import kotlin.math.abs

@Composable
internal fun AnimatedTerminalCursor(
    targetX: Float,
    targetTop: Float,
    targetBottom: Float,
    modifier: Modifier = Modifier,
) {
    val trailX = remember { Animatable(targetX) }

    LaunchedEffect(targetX) {
        trailX.animateTo(
            targetValue = targetX,
            animationSpec = tween(durationMillis = 180),
        )
    }

    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        val distance = abs(targetX - trailX.value)

        if (distance < 1f) return@Canvas

        val left = minOf(targetX, trailX.value)
        val right = maxOf(targetX, trailX.value)
        val centerY = (targetTop + targetBottom) / 2f

        // Мягкое свечение
        drawLine(
            color = Mocha.Mauve.copy(alpha = 0.12f),
            start = Offset(left, centerY),
            end = Offset(right, centerY),
            strokeWidth = 12.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // Основной шлейф
        drawLine(
            color = Mocha.Mauve.copy(alpha = 0.45f),
            start = Offset(left, centerY),
            end = Offset(right, centerY),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // Яркая сердцевина
        drawLine(
            color = Mocha.Mauve.copy(alpha = 0.8f),
            start = Offset(left, centerY),
            end = Offset(right, centerY),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
