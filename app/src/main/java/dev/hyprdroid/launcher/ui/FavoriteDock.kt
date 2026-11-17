package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.model.WorkspaceItem

/** Reuses the workspace drag state; a dock has at most four pinned apps and one drawer button. */
@Composable
fun FavoriteDock(components: List<String>, apps: Map<String, LauncherApp>, onLaunch: (String) -> Unit,
    menu: (String) -> List<AppMenuAction>, onDrawer: () -> Unit, modifier: Modifier = Modifier,
    showDrawer: Boolean = true, enabled: Boolean = true, onPlaceBefore: (String, String?) -> Unit = { _, _ -> }, onDragging: (Boolean) -> Unit = {}) {
    val count = components.size + if (showDrawer) 1 else 0
    if (count == 0) return
    val grid = rememberLazyGridState()
    val drag = remember(grid) { WorkspaceDragState(grid, reserveRemoveTarget = false) }
    val source = remember(components) { components.map { WorkspaceItem.AppShortcut(it) } }
    val place by rememberUpdatedState(onPlaceBefore)
    val active by rememberUpdatedState(onDragging)
    drag.onActive = { active(it) }
    drag.gridDensity = LocalDensity.current.density
    drag.onDrop = { id, before -> place(id.removePrefix("app:"), before?.removePrefix("app:")) }
    LaunchedEffect(source, enabled) {
        drag.finish(false)
        drag.items = source
    }
    DisposableEffect(drag) { onDispose { drag.finish(false) } }
    ShellSurface(modifier.width((52 * count + 12).dp).widthIn(max = 440.dp), highlighted = drag.draggedId != null) {
        LazyVerticalGrid(columns = GridCells.Fixed(count), state = grid, userScrollEnabled = false,
            contentPadding = PaddingValues(6.dp), modifier = Modifier.height(64.dp).workspaceDrag(drag, enabled).testTag("dock-grid")
                .semantics { stateDescription = if (drag.draggedId != null) "Перетаскивание приложения" else "Dock" }) {
            items(drag.items, key = { it.id }) { item ->
                val component = (item as WorkspaceItem.AppShortcut).componentId
                val dragging = drag.draggedId == item.id
                AppTile(apps[component], component, { onLaunch(component) }, emptyList(), compact = true, longPressMenu = false,
                    modifier = Modifier.animateItem().zIndex(if (dragging) 1f else 0f).graphicsLayer {
                        val offset = drag.translation(item.id)
                        translationX = offset.x.toFloat(); translationY = offset.y.toFloat()
                        scaleX = if (dragging) 1.1f else 1f; scaleY = scaleX
                    }.testTag("dock-app-$component").semantics {
                        customActions = menu(component).filter { it.enabled }.map { action -> CustomAccessibilityAction(action.label) { action.onClick(); true } }
                    })
            }
            if (showDrawer) item(key = "dock-drawer") {
                IconButton(onClick = onDrawer, modifier = Modifier.size(52.dp)) { DrawerGlyph() }
            }
        }
    }
}
