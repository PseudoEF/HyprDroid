package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.model.Workspace
import dev.hyprdroid.launcher.model.WorkspaceItem

@Composable
fun WorkspaceCanvas(
    workspace: Workspace, apps: Map<String, LauncherApp>, editing: Boolean,
    gesturesEnabled: Boolean, onEdit: () -> Unit, onSwipeUp: () -> Unit, onSwipeDown: () -> Unit,
    onLaunch: (String) -> Unit, onRemove: (String) -> Unit,
    menu: (WorkspaceItem) -> List<AppMenuAction>, modifier: Modifier = Modifier,
    onDrop: (String, String?) -> Unit = { _, _ -> }, onDragging: (Boolean) -> Unit = {},
    resetToken: Int = 0,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val appearance = workspace.appearance
    val grid = gridState
    val drag = remember(workspace.id) { WorkspaceDragState(grid) }
    val activeCallback by rememberUpdatedState(onDragging)
    val dropCallback by rememberUpdatedState(onDrop)
    val haptics = LocalHapticFeedback.current
    drag.gridDensity = LocalDensity.current.density
    drag.onActive = { activeCallback(it); if (it) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    drag.onDrop = { id, before -> dropCallback(id, before) }
    LaunchedEffect(workspace.items, resetToken) {
        if (drag.draggedId != null) drag.finish(false)
        drag.items = workspace.items
    }
    LaunchedEffect(editing, gesturesEnabled) { if (!editing || !gesturesEnabled) drag.finish(false) }
    DisposableEffect(drag) { onDispose { drag.finish(false) } }
    DragAutoScroll(drag, with(LocalDensity.current) { 64.dp.toPx() })
    Box(
        modifier.testTag("workspace-canvas-${workspace.id}")
            .then(if (editing) Modifier.border(BorderStroke(1.dp, Mocha.Mauve.copy(alpha = .6f)), RoundedCornerShape(24.dp)) else Modifier)
            .semantics { customActions = listOf(CustomAccessibilityAction("Редактировать пространство") { onEdit(); true }) }
            .homeBlankGestures(gesturesEnabled, !editing, onEdit, onSwipeUp, onSwipeDown),
    ) {
        LazyVerticalGrid(
            state = grid,
            userScrollEnabled = drag.draggedId == null,
            columns = if (appearance.gridLayout) GridCells.Adaptive(maxOf(appearance.density.cellDp, appearance.iconSizeDp + 24).dp) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize().workspaceDrag(drag, editing && gesturesEnabled), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(appearance.spacingDp.dp), verticalArrangement = Arrangement.spacedBy(appearance.spacingDp.dp),
        ) {
            items(drag.items, key = { it.id }) { item ->
                val app = item as? WorkspaceItem.AppShortcut
                val dragging = drag.draggedId == item.id
                AppTile(
                    app = app?.let { apps[it.componentId] }, component = app?.componentId ?: "Элемент новой версии",
                    onClick = { if (app != null && !editing) onLaunch(app.componentId) }, menu = menu(item),
                    list = !appearance.gridLayout, iconSizeDp = appearance.iconSizeDp,
                    selected = dragging, dragEditing = editing,
                    onRemove = if (editing) ({ onRemove(item.id) }) else null,
                    modifier = Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                        .then(if (!dragging) Modifier.animateItem() else Modifier)
                        .graphicsLayer { val offset = drag.translation(item.id); translationX = offset.x.toFloat(); translationY = offset.y.toFloat();
                            scaleX = if (dragging) 1.06f else 1f; scaleY = scaleX; shadowElevation = if (dragging) 12.dp.toPx() else 0f }
                        .testTag(if (app != null) "workspace-app-${app.componentId}" else "workspace-item-${item.id}"),
                )
            }
        }
    }
}
