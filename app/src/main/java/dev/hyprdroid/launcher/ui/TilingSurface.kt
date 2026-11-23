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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import dev.hyprdroid.launcher.modules.LocalTileCommandPort
import dev.hyprdroid.launcher.modules.TileCommandPort
import dev.hyprdroid.launcher.packages.DROIDFETCH_SAMPLE_ID
import dev.hyprdroid.launcher.packages.HELLO_TILE_ID
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.modules.BuiltInModules
import dev.hyprdroid.launcher.modules.LocalModuleEnvironment
import dev.hyprdroid.launcher.modules.LocalTileModuleRegistry
import dev.hyprdroid.launcher.modules.ModuleEnvironment
import dev.hyprdroid.launcher.modules.ModuleHost
import dev.hyprdroid.launcher.modules.ModuleRegistry
import dev.hyprdroid.launcher.workspaceengine.LayoutArea
import dev.hyprdroid.launcher.workspaceengine.LayoutRect
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
    onAddPluginTile: ((LayoutArea, Float) -> WindowId?)? = null,
    onAddExternalTile: ((LayoutArea, Float) -> WindowId?)? = null,
    onRemovePluginTile: ((WindowId) -> Boolean)? = null,
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
        val currentEngine by rememberUpdatedState(engine)
        val currentArea by rememberUpdatedState(area)
        val currentDensity by rememberUpdatedState(density.density)
        val addPlugin by rememberUpdatedState(onAddPluginTile)
        val addExternal by rememberUpdatedState(onAddExternalTile)
        val removePlugin by rememberUpdatedState(onRemovePluginTile)
        val port = remember(onAddPluginTile != null && onRemovePluginTile != null) {
            if (onAddPluginTile == null || onRemovePluginTile == null) null
            else TileCommandPort(
                add = { addPlugin?.invoke(currentArea, currentDensity) },
                list = {
                    currentEngine.windowsById().values
                        .filter { it.moduleId == DROIDFETCH_SAMPLE_ID }.map { it.windowId }
                },
                remove = { removePlugin?.invoke(it) == true },
                addExternal = { addExternal?.invoke(currentArea, currentDensity) },
                externalTiles = {
                    currentEngine.windowsById().values
                        .filter { it.moduleId == HELLO_TILE_ID }.map { it.windowId }
                },
            )
        }
        val previewEngine = remember(engine) { mutableStateOf<WorkspaceEngine?>(null) }
        val displayEngine = previewEngine.value ?: engine
        val mins: Map<WindowId, WindowMin> = remember(displayEngine, area) {
            displayEngine.windowsById().mapValues { entry -> tilingWindowMin(entry.value, density.density) }.toMap()
        }
        val placed = remember(displayEngine, area, mins) { displayEngine.calculateLayout(area, mins) }
        val focusedId = displayEngine.focusedWindowId
        val canAddWindow = displayEngine.canAddWindow(area)
        val currentResizeSplit by rememberUpdatedState(onResizeSplit)
        val currentAddWindowAt by rememberUpdatedState(onAddWindowAt)
        val currentCloseWindow by rememberUpdatedState(onCloseWindow)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(engine, area, density.density) {
                    val threshold = 48.dp.toPx()
                    val densityScale = density.density
                    // Base geometry is captured once per engine; preview frames never restart pointerInput.
                    val gestureMins = engine.windowsById()
                        .mapValues { entry -> tilingWindowMin(entry.value, densityScale) }
                    val gesturePlaced = engine.calculateLayout(area, gestureMins)
                    val gestureSplits = engine.splitsWithRects(
                        engine.root,
                        LayoutRect(x = 0, y = 0, width = area.widthPx, height = area.heightPx),
                        gestureMins,
                    )
                    val gestureCanAdd = engine.canAddWindow(area)

                    fun tileAt(x: Float, y: Float): WindowId? =
                        gesturePlaced.firstOrNull { item ->
                            val r = item.rect
                            x >= r.x && x < r.x + r.width &&
                                y >= r.y && y < r.y + r.height
                        }?.windowId

                    awaitEachGesture {
                        try {
                            previewEngine.value = null
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
                            var resizing = false
                            var resize: TilingResizePreview? = null
                            var horizontalActive = false
                            var finished = false
                            var lastSpanX = 0f
                            var lastSpanY = 0f

                            fun applyPreview(spanX: Float, spanY: Float) {
                                val session = resize ?: return
                                lastSpanX = spanX
                                lastSpanY = spanY
                                previewEngine.value = session.update(spanX, spanY, horizontalActive)
                            }

                            fun finish(commit: Boolean) {
                                if (finished || resize == null) return
                                finished = true
                                resizing = false
                                val session = resize!!
                                if (commit) {
                                    val changed = session.changedRatios(lastSpanX, lastSpanY, horizontalActive)
                                    if (changed.isEmpty()) previewEngine.value = null
                                    else changed.forEach { currentResizeSplit(it.splitId, it.ratio) }
                                } else {
                                    previewEngine.value = null
                                }
                            }

                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val active = event.changes.filter { it.pressed }

                                if (active.size >= 2) {
                                    multiTouch = true
                                    event.changes.forEach { it.consume() }
                                    val first = active[0].position
                                    val second = active[1].position
                                    val spanX = abs(first.x - second.x)
                                    val spanY = abs(first.y - second.y)

                                    if (initialX == null && !rejected) {
                                        val firstTile = tileAt(first.x, first.y)
                                        val secondTile = tileAt(second.x, second.y)

                                        if (firstTile != null && firstTile == secondTile) {
                                            target = firstTile
                                            initialX = spanX
                                            initialY = spanY
                                        } else {
                                            rejected = true
                                        }
                                    } else if (!triggered && !finished) {
                                        val dx = spanX - (initialX ?: 0f)
                                        val dy = spanY - (initialY ?: 0f)

                                        if (!resizing) {
                                            when {
                                                abs(dx) > threshold && abs(dx) > abs(dy) * 1.5f -> {
                                                    // Horizontal gesture: add/remove.
                                                    triggered = true
                                                    target?.let { id ->
                                                        if (dx > 0f && gestureCanAdd) {
                                                            currentAddWindowAt(id, area, densityScale)
                                                        } else if (dx < 0f && gesturePlaced.size > 1) {
                                                            currentCloseWindow(id)
                                                        }
                                                    }
                                                }
                                                abs(dy) > threshold && abs(dy) > abs(dx) * 1.5f -> {
                                                    // Vertical gesture: resize height.
                                                    target?.let { id ->
                                                        beginTilingResize(
                                                            engine, id, area, gestureMins,
                                                            initialX ?: 0f, initialY ?: 0f,
                                                            gesturePlaced, gestureSplits,
                                                        ).takeIf { it.vertical != null }?.let { session ->
                                                            resize = session
                                                            resizing = true
                                                        }
                                                    }
                                                }
                                                abs(dx) > threshold && abs(dy) > threshold -> {
                                                    // Diagonal gesture: resize every available axis.
                                                    target?.let { id ->
                                                        beginTilingResize(
                                                            engine, id, area, gestureMins,
                                                            initialX ?: 0f, initialY ?: 0f,
                                                            gesturePlaced, gestureSplits,
                                                        ).takeIf { it.vertical != null || it.horizontal != null }?.let { session ->
                                                            resize = session
                                                            resizing = true
                                                            horizontalActive = session.horizontal != null
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (resizing) {
                                        val dx = spanX - (initialX ?: 0f)
                                        val dy = spanY - (initialY ?: 0f)
                                        if (!horizontalActive && abs(dx) > threshold && abs(dy) > threshold) {
                                            horizontalActive = resize?.horizontal != null
                                        }
                                        applyPreview(spanX, spanY)
                                    }
                                } else if (multiTouch) {
                                    event.changes.forEach { it.consume() }
                                    // A lifted finger ends the resize with the last two-finger geometry.
                                    if (resizing) finish(commit = true)
                                }
                            } while (event.changes.any { it.pressed })

                            if (resizing) finish(commit = true)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            // Workspace switch or disposal cancels the gesture without persisting preview.
                            previewEngine.value = null
                            throw cancelled
                        }
                    }
                },
        ) {
        Layout(
            content = {
                CompositionLocalProvider(
                    LocalModuleEnvironment provides environment,
                    LocalTileModuleRegistry provides registry,
                    LocalTileCommandPort provides port,
                ) {
                    placed.forEach { placedWindow ->
                        key(placedWindow.windowId) {
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
                                    moduleId = displayEngine.windowsById()[placedWindow.windowId]?.moduleId.orEmpty(),
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
