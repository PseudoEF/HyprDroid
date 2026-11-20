package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
internal fun AppDrawer(
    state: WorkspaceUiState, selectingWorkspace: Int?, onClose: () -> Unit,
    onLaunch: (String) -> Unit, onPin: (String, Boolean) -> Unit,
    menu: (String) -> List<AppMenuAction>, onRetry: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val gridState = rememberLazyGridState()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val dismiss = { keyboard?.hide(); focus.clearFocus(force = true); onClose() }
    val filtered = remember(state.apps, search) {
        state.apps.filter { it.label.contains(search, ignoreCase = true) || it.componentId.contains(search, ignoreCase = true) }
    }
    val pinned = selectingWorkspace?.let { state.settings?.workspaces?.get(it)?.pinnedApps }.orEmpty()
    Surface(color = Mocha.Base, contentColor = Mocha.Text, modifier = Modifier.fillMaxSize()
        .panelDismissGestures(PanelDismissDirection.DOWN, { !gridState.canScrollBackward && !imeVisible }, dismiss).testTag("app-drawer")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 960.dp).fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)
                .panelDismissGestures(PanelDismissDirection.DOWN, onDismiss = dismiss, header = true).testTag("drawer-dismiss-header"), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selectingWorkspace == null) "Приложения" else "Закрепить · 0${selectingWorkspace + 1}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = dismiss) { Icon(Icons.Default.Close, if (selectingWorkspace == null) "Закрыть приложения" else "Готово, вернуться к пространству") }
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus(force = true) }),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (search.isNotEmpty()) ({ IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "Очистить поиск") } }) else null,
                placeholder = { Text("Поиск приложений") }, shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth().testTag("app-search"),
            )
            if (state.loadingApps) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            if (state.catalogError) {
                Text("Не удалось обновить каталог", Modifier.padding(top = 12.dp), color = Mocha.Red)
                TextButton(onClick = onRetry) { Text("Повторить") }
            }
            if (filtered.isEmpty() && !state.loadingApps) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (search.isBlank()) "Нет доступных приложений" else "Ничего не найдено", color = Mocha.Subtext)
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(76.dp), modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth().weight(1f).testTag("drawer-grid"),
                    contentPadding = PaddingValues(top = 24.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(filtered, key = { it.componentId }) { app ->
                        AppTile(
                            app = app, component = app.componentId, selected = selectingWorkspace != null && app.componentId in pinned,
                            onClick = {
                                if (selectingWorkspace == null) onLaunch(app.componentId)
                                else if (!state.storageError) onPin(app.componentId, app.componentId !in pinned)
                            }, menu = menu(app.componentId), modifier = Modifier.testTag("drawer-app-${app.componentId}"),
                        )
                    }
                }
            }
        }
    }
}
