package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.model.PanelWidget

/** Only this explicit handle captures drag; RemoteViews never need to surrender long presses. */
@Composable
internal fun WidgetResizeHandle(widget: PanelWidget, widthDp: Float, horizontal: Boolean, vertical: Boolean, onResize: (PanelWidget) -> Unit) {
    val density = LocalDensity.current.density
    val latest by rememberUpdatedState(onResize)
    val current by rememberUpdatedState(widget)
    Text("↔↕", color = Mocha.Text, modifier = Modifier.size(48.dp).background(Mocha.Surface)
        .testTag("widget-resize-${widget.appWidgetId}").semantics { contentDescription = "Изменить размер перетаскиванием; также доступны ползунки" }
        .pointerInput(widget.appWidgetId, horizontal, vertical, density, widthDp) {
            var start = current
            var dx = 0f; var dy = 0f
            detectDragGestures(onDragStart = { start = current; dx = 0f; dy = 0f },
                onDragCancel = { latest(start) }, onDrag = { change, amount ->
                    change.consume(); dx += amount.x / density; dy += amount.y / density
                    latest(start.copy(widthPercent = if (horizontal) (start.widthPercent + dx * 100 / widthDp.coerceAtLeast(1f)).toInt().coerceIn(25, 100) else start.widthPercent,
                        heightDp = if (vertical) (start.heightDp + dy).toInt().coerceIn(64, 600) else start.heightDp))
                })
        })
}
