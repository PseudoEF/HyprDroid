package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.workspaceengine.LayoutArea
import dev.hyprdroid.launcher.workspaceengine.LayoutRect
import dev.hyprdroid.launcher.workspaceengine.Orientation
import dev.hyprdroid.launcher.workspaceengine.SplitId
import dev.hyprdroid.launcher.workspaceengine.WindowId
import dev.hyprdroid.launcher.workspaceengine.WindowMin
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine

/** Internal tiling surface: renders one workspace of internal windows from the engine layout. */
@Composable
internal fun TilingSurface(
    engine: WorkspaceEngine,
    modifier: Modifier = Modifier,
    onFocusWindow: (WindowId) -> Unit = {},
    onCloseWindow: (WindowId) -> Unit = {},
    onAddWindow: (Orientation) -> Unit = {},
    onResizeSplit: (SplitId, Float) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("tiling-surface")) {
        val area = LayoutArea(
            widthPx = with(density) { maxWidth.roundToPx().toInt() },
            heightPx = with(density) { maxHeight.roundToPx().toInt() },
        )
        val mins: Map<WindowId, WindowMin> = remember(engine, area) {
            engine.windowsById().mapValues { entry -> tilingWindowMin(entry.value, density.density) }.toMap()
        }
        val placed = remember(engine, area, mins) { engine.calculateLayout(area, mins) }
        val splits = remember(engine, area, mins) {
            engine.splitsWithRects(engine.root, LayoutRect(x = 0, y = 0, width = area.widthPx, height = area.heightPx), mins)
        }
        val focusedId = engine.focusedWindowId
        val addableWindowId = engine.focusedWindowId ?: engine.leaves().firstOrNull()
        val canAddWindow = addableWindowId != null && engine.canSplit(addableWindowId, Orientation.HORIZONTAL, area)

        Layout(
            content = {
                placed.forEach { placedWindow ->
                    val isFocused = placedWindow.windowId == focusedId
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (isFocused) Mocha.Surface else Mocha.Mantle, RoundedCornerShape(12.dp))
                            .border(BorderStroke(1.dp, if (isFocused) Mocha.Mauve else Mocha.Outline.copy(alpha = .5f)), RoundedCornerShape(12.dp))
                            .testTag("tiling-window-${placedWindow.windowId.value}")
                            .clickable { onFocusWindow(placedWindow.windowId) },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("Окно ${placedWindow.windowId.value}", style = MaterialTheme.typography.titleSmall, color = Mocha.Subtext)
                            engine.windowsById()[placedWindow.windowId]?.let { window ->
                                Text(window.moduleId, style = MaterialTheme.typography.bodySmall, color = Mocha.Subtext)
                            }
                        }
                        if (isFocused) {
                            IconButton(onClick = { onCloseWindow(placedWindow.windowId) }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).testTag("tiling-close-window-${placedWindow.windowId.value}")) {
                                Icon(Icons.Default.Close, contentDescription = "Закрыть окно", tint = Mocha.Red)
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val placeables = measurables.mapIndexed { index, measurable ->
                val rect = placed[index].rect
                measurable.measure(Constraints.fixed(rect.width, rect.height))
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val rect = placed[index].rect
                    placeable.place(rect.x, rect.y)
                }
            }
        }

        splits.forEach { placement ->
            val split = placement.split
            val isHorizontal = split.orientation == Orientation.HORIZONTAL
            val boundary = if (isHorizontal) placement.firstRect.x + placement.firstRect.width else placement.firstRect.y + placement.firstRect.height
            val hitModifier = if (isHorizontal) {
                Modifier.fillMaxHeight().width(12.dp).background(Mocha.Outline.copy(alpha = .6f), RoundedCornerShape(6.dp)).offset(x = with(density) { boundary.toInt().toDp() })
            }
            else {
                Modifier.fillMaxWidth().height(12.dp).background(Mocha.Outline.copy(alpha = .6f), RoundedCornerShape(6.dp)).offset(y = with(density) { boundary.toInt().toDp() })
            }
            Box(
                modifier = hitModifier.pointerInput(split.id.value) {
                    var dividerPos = 0f
                    detectDragGestures(
                        onDragStart = { dividerPos = if (split.orientation == Orientation.HORIZONTAL) it.x else it.y },
                        onDrag = { _, dragDelta ->
                            dividerPos += if (split.orientation == Orientation.HORIZONTAL) dragDelta.x else dragDelta.y
                            val available = if (split.orientation == Orientation.HORIZONTAL) area.widthPx else area.heightPx
                            val firstSize = dividerPos.toInt().coerceIn(0, available)
                            onResizeSplit(split.id, tilingRatio(available, firstSize))
                        },
                    )
                },
            ) {
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .testTag("tiling-add-window")
                .clickable { if (canAddWindow) onAddWindow(Orientation.HORIZONTAL) },
        ) {
            Icon(Icons.Default.Add, contentDescription = "Добавить окно", tint = Mocha.Text)
        }
    }
}
