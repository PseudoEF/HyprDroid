package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keeps a continuous thumb draft while integer preferences or system observers update. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0,
    onCommit: ((Float) -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    var draft by remember { mutableFloatStateOf(value.coerceIn(valueRange)) }
    LaunchedEffect(value, dragging, enabled) { if (!dragging || !enabled) draft = value.coerceIn(valueRange) }
    Slider(value = draft, onValueChange = { draft = it; onValueChange(it) },
        onValueChangeFinished = { onCommit?.invoke(draft) }, enabled = enabled, valueRange = valueRange, steps = steps,
        modifier = modifier.heightIn(min = 48.dp), interactionSource = interaction,
        colors = SliderDefaults.colors(thumbColor = Mocha.Text, activeTrackColor = Mocha.Mauve, inactiveTrackColor = Mocha.Surface),
        thumb = { Box(Modifier.size(18.dp).background(if (enabled) Mocha.Text else Mocha.Outline, CircleShape)) })
}
