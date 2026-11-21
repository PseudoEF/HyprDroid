package dev.hyprdroid.launcher.modules

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.ui.Mocha

@Composable
internal fun AnimatedTerminalCursor(
    targetX: Float,
    targetTop: Float,
    targetBottom: Float,
    modifier: Modifier = Modifier,
    color: Color = Mocha.Mauve,
) {
    val animatedX = remember { Animatable(targetX) }

    LaunchedEffect(targetX) {
        animatedX.animateTo(
            targetValue = targetX,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = 450f,
            ),
        )
    }

    Canvas(modifier.fillMaxSize()) {
        val x = animatedX.value.coerceIn(0f, size.width)
        val top = targetTop.coerceIn(0f, size.height)
        val bottom = targetBottom.coerceIn(top, size.height)

        drawLine(
            color = color,
            start = Offset(x, top),
            end = Offset(x, bottom),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
