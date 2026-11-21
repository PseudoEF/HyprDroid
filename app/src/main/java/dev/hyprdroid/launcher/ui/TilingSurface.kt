package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlin.math.abs
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.modules.BuiltInModules
import dev.hyprdroid.launcher.modules.LocalModuleEnvironment
import dev.hyprdroid.launcher.modules.LocalTileModuleRegistry
import dev.hyprdroid.launcher.modules.ModuleEnvironment
import dev.hyprdroid.launcher.modules.ModuleHost
import dev.hyprdroid.launcher.modules.ModuleRegistry
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
    registry: ModuleRegistry = BuiltInModules.registry,
    apps: List<LauncherApp> = emptyList(),
    appsLoading: Boolean = false,
    catalogError: Boolean = false,
    onLaunchApp: (String) -> Unit = {},
    onRetryApps: () -> Unit = {},
    onSelectModule: (WindowId, String) -> Unit = { _, _ -> },
    onFocusWindow: (WindowId) -> Unit = {},
    onCloseWindow: (WindowId) -> Unit = {},
    onAddWindow: (LayoutArea, Float) -> Unit = { _, _ -> },
    onAddWindowAt: (WindowId, LayoutArea, Float) -> Unit = { _, _, _ -> },
    onLayoutArea: (LayoutArea) -> Unit = {},
    onResizeSplit: (SplitId, Float) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val environment = remember(apps, appsLoading, catalogError, onLaunchApp, onRetryApps) {
        ModuleEnvironment(apps, appsLoading, catalogError, onLaunchApp, onRetryApps)
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("tiling-surface")) {
        val area = LayoutArea(
            widthPx = with(density) { maxWidth.roundToPx().toInt() },
            heightPx = with(density) { maxHeight.roundToPx().toInt() },
        )
        LaunchedEffect(area.widthPx, area.heightPx) { onLayoutArea(area) }
        val mins: Map<WindowId, WindowMin> = remember(engine, area) {
            engine.windowsById().mapValues { entry -> tilingWindowMin(entry.value, density.density) }.toMap()
        }
        val placed = remember(engine, area, mins) { engine.calculateLayout(area, mins) }
        val splits = remember(engine, area, mins) {
            engine.splitsWithRects(engine.root, LayoutRect(x = 0, y = 0, width = area.widthPx, height = area.heightPx), mins)
        }
        val focusedId = engine.focusedWindowId
        val canAddWindow = engine.canAddWindow(area)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(placed, canAddWindow, area, density.density) {
                    val threshold = 48.dp.toPx()

                    fun tileAt(x: Float, y: Float): WindowId? =
                        placed.firstOrNull { item ->
                            val r = item.rect
                            x >= r.x && x < r.x + r.width &&
                            y >= r.y && y < r.y + r.height
                        }?.windowId

                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )

                        var target: WindowId? = null
                        var initialX: Float? = null
                        var initialY: Float? = null
                        var triggered = false
                        var multiTouch = false
                        var rejected = false
                        var resizeX = 0f
                        var resizeY = 0f
                        var resizing = false

                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val active = event.changes.filter { it.pressed }

                            if (active.size >= 2) {
                                multiTouch = true
                                event.changes.forEach { it.consume() }

                                if (initialX == null && !rejected) {
                                    val first = active[0].position
                                    val second = active[1].position
                                    val firstTile = tileAt(first.x, first.y)
                                    val secondTile = tileAt(second.x, second.y)

                                    if (firstTile != null && firstTile == secondTile) {
                                        target = firstTile
                                        initialX = abs(first.x - second.x)
                                        initialY = abs(first.y - second.y)
                                    } else {
                                        rejected = true
                                    }
                                } else if (!triggered && !rejected) {
                                    val first = active[0].position
                                    val second = active[1].position
                                    val dx = abs(first.x - second.x) - (initialX ?: 0f)
                                    val dy = abs(first.y - second.y) - (initialY ?: 0f)

                                    if (abs(dx) > threshold &&
                                        abs(dx) > abs(dy) * 1.5f) {
                                        // Horizontal gesture: add/remove.
                                        triggered = true
                                        target?.let { id ->
                                            if (dx > 0f && canAddWindow) {
                                                onAddWindowAt(id, area, density.density)
                                            } else if (dx < 0f && placed.size > 1) {
                                                onCloseWindow(id)
                                            }
                                        }
                                    } else if (abs(dy) > threshold &&
                                        abs(dy) > abs(dx) * 1.5f) {
                                        // Vertical gesture: resize height.
                                        resizing = true
                                    } else if (
                                        abs(dx) > threshold &&
                                        abs(dy) > threshold
                                    ) {
                                        // Diagonal gesture: resize both axes.
                                        resizing = true
                                    }

                                    if (resizing) {
                                        resizeX = dx
                                        resizeY = dy
                                    }
                                }
                            } else if (multiTouch) {
                                event.changes.forEach { it.consume() }
                            }

                            if (resizing && active.size >= 2) {
                                val first = active[0].position
                                val second = active[1].position
                                resizeX = abs(first.x - second.x) - (initialX ?: 0f)
                                resizeY = abs(first.y - second.y) - (initialY ?: 0f)
                            }
                        } while (event.changes.any { it.pressed })

                        if (resizing && !triggered) {
                            val selected = placed.firstOrNull {
                                it.windowId == target
                            }

                            if (selected != null) {
                                val rect = selected.rect
                                val cx = rect.x + rect.width / 2f
                                val cy = rect.y + rect.height / 2f

                                fun resizeAxis(
                                    orientation: Orientation,
                                    delta: Float,
                                ) {
                                    if (abs(delta) < 12.dp.toPx()) return

                                    val candidates = splits.mapNotNull { item ->
                                        if (item.split.orientation != orientation) {
                                            return@mapNotNull null
                                        }

                                        val first = item.firstRect
                                        val second = item.secondRect

                                        fun contains(r: LayoutRect): Boolean =
                                            cx >= r.x && cx < r.x + r.width &&
                                            cy >= r.y && cy < r.y + r.height

                                        val inFirst = contains(first)
                                        val inSecond = contains(second)

                                        if (!inFirst && !inSecond) {
                                            null
                                        } else {
                                            val size = if (
                                                orientation == Orientation.HORIZONTAL
                                            ) {
                                                first.width + second.width
                                            } else {
                                                first.height + second.height
                                            }

                                            Triple(item, inFirst, size)
                                        }
                                    }

                                    val candidate = candidates.minByOrNull {
                                        it.third
                                    } ?: return

                                    val (placement, inFirst, extent) = candidate
                                    if (extent <= 0) return

                                    val direction = if (inFirst) 1f else -1f
                                    val ratio = (
                                        placement.split.ratio +
                                        direction * delta / extent
                                    ).coerceIn(0.2f, 0.8f)

                                    onResizeSplit(placement.split.id, ratio)
                                }

                                if (abs(resizeY) > threshold) {
                                    resizeAxis(Orientation.VERTICAL, resizeY)
                                }

                                if (
                                    abs(resizeX) > threshold &&
                                    abs(resizeY) > threshold
                                ) {
                                    resizeAxis(Orientation.HORIZONTAL, resizeX)
                                }
                            }
                        }
                    }
                },
        ) {
        Layout(
            content = {
                CompositionLocalProvider(
                    LocalModuleEnvironment provides environment,
                    LocalTileModuleRegistry provides registry,
                ) {
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
                        ModuleHost(
                            windowId = placedWindow.windowId,
                            moduleId = engine.windowsById()[placedWindow.windowId]?.moduleId.orEmpty(),
                            registry = registry,
                            onSelectModule = { moduleId -> onSelectModule(placedWindow.windowId, moduleId) },
                            modifier = Modifier.fillMaxSize(),
                            headerActions = {
                                if (isFocused) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .clickable { onCloseWindow(placedWindow.windowId) }
                                            .testTag("tiling-close-window-${placedWindow.windowId.value}"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Закрыть окно",
                                            tint = Mocha.Red,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            },
                        )
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

        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .testTag("tiling-add-window")
                .clickable { if (canAddWindow) onAddWindow(area, density.density) },
        ) {
            Icon(Icons.Default.Add, contentDescription = "Добавить окно", tint = Mocha.Text)
        }
    }
}
