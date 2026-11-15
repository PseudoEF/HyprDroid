package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.model.Workspace

@Composable
fun WorkspaceCanvas(
    workspace: Workspace, apps: Map<String, LauncherApp>, grid: Boolean, editing: Boolean,
    onEdit: () -> Unit, onLaunch: (String) -> Unit, onRemove: (String) -> Unit,
    menu: (String) -> List<AppMenuAction>, modifier: Modifier = Modifier,
) {
    val edit by rememberUpdatedState(onEdit)
    val components = remember(workspace.pinnedApps, apps) { workspace.pinnedApps.sortedBy { apps[it]?.label ?: it } }
    Box(
        modifier.testTag("workspace-canvas-${workspace.id}")
            .then(if (editing) Modifier.border(BorderStroke(1.dp, Mocha.Mauve.copy(alpha = .6f)), RoundedCornerShape(24.dp)) else Modifier)
            .semantics { customActions = listOf(CustomAccessibilityAction("Редактировать пространство") { edit(); true }) }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { edit() }) },
    ) {
        LazyVerticalGrid(
            columns = if (grid) GridCells.Adaptive(76.dp) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(components, key = { it }) { component ->
                AppTile(
                    app = apps[component], component = component, onClick = { onLaunch(component) }, menu = menu(component),
                    list = !grid, onRemove = if (editing) ({ onRemove(component) }) else null,
                    modifier = Modifier.fillMaxWidth().testTag("workspace-app-$component"),
                )
            }
        }
    }
}
