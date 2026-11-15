package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
) {
    val appearance = workspace.appearance
    Box(
        modifier.testTag("workspace-canvas-${workspace.id}")
            .then(if (editing) Modifier.border(BorderStroke(1.dp, Mocha.Mauve.copy(alpha = .6f)), RoundedCornerShape(24.dp)) else Modifier)
            .semantics { customActions = listOf(CustomAccessibilityAction("Редактировать пространство") { onEdit(); true }) }
            .homeBlankGestures(gesturesEnabled, !editing, onEdit, onSwipeUp, onSwipeDown),
    ) {
        LazyVerticalGrid(
            columns = if (appearance.gridLayout) GridCells.Adaptive(maxOf(appearance.density.cellDp, appearance.iconSizeDp + 24).dp) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(appearance.spacingDp.dp), verticalArrangement = Arrangement.spacedBy(appearance.spacingDp.dp),
        ) {
            items(workspace.items, key = { it.id }) { item ->
                val app = item as? WorkspaceItem.App
                AppTile(
                    app = app?.let { apps[it.componentId] }, component = app?.componentId ?: "Элемент новой версии",
                    onClick = { if (app != null) onLaunch(app.componentId) }, menu = menu(item),
                    list = !appearance.gridLayout, iconSizeDp = appearance.iconSizeDp,
                    onRemove = if (editing) ({ onRemove(item.id) }) else null,
                    modifier = Modifier.fillMaxWidth().testTag(if (app != null) "workspace-app-${app.componentId}" else "workspace-item-${item.id}"),
                )
            }
        }
    }
}
