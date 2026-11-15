package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.model.WORKSPACE_COUNT
import dev.hyprdroid.launcher.model.WorkspaceSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

@Composable
fun WorkspaceScreen(model: WorkspaceViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refreshApps()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbar.showSnackbar(message)
            model.clearMessage()
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFA6E3A1), background = Color(0xFF101116), surface = Color(0xFF20232C),
    )) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("HyprDroid", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (!openHomeSettings(context)) model.showMessage("Откройте настройки Android и выберите другой домашний экран.")
                    }) { Text("Другой лаунчер") }
                }
                if (state.storageError) {
                    Text("Настройки недоступны. Изменения рабочих пространств временно отключены.")
                    TextButton(onClick = model::retryStorage) { Text("Повторить чтение") }
                }
                val settings = state.settings
                if (settings == null) {
                    if (!state.storageError) {
                        CircularProgressIndicator(Modifier.padding(24.dp))
                        Text("Загрузка рабочих пространств…")
                    }
                } else {
                    WorkspacePages(state, settings, model, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WorkspacePages(
    state: WorkspaceUiState,
    settings: WorkspaceSettings,
    model: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(initialPage = settings.selectedWorkspace, pageCount = { WORKSPACE_COUNT })
    var pickerWorkspace by rememberSaveable { mutableStateOf<Int?>(null) }
    val appsById = remember(state.apps) { state.apps.associateBy { it.componentId } }
    LaunchedEffect(pager, state.storageError) {
        if (!state.storageError) snapshotFlow { pager.currentPage }.distinctUntilChanged().collect(model::selectWorkspace)
    }
    Column(modifier) {
        Text("Рабочее пространство ${pager.currentPage + 1} / $WORKSPACE_COUNT", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (settings.gridLayout) "Сетка" else "Список", Modifier.weight(1f))
            Switch(checked = settings.gridLayout, onCheckedChange = model::setGridLayout, enabled = !state.storageError)
        }
        Button(onClick = { pickerWorkspace = pager.currentPage }, enabled = !state.storageError, modifier = Modifier.fillMaxWidth()) {
            Text("Добавить приложения")
        }
        if (state.loadingApps) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.catalogError) {
            Text("Список приложений недоступен.")
            TextButton(onClick = model::refreshApps) { Text("Обновить приложения") }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            val workspace = settings.workspaces[page]
            if (workspace.pinnedApps.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Здесь пока нет закреплённых приложений.")
                    Text("Нажмите «Добавить приложения». У каждого пространства свой набор.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val pins = workspace.pinnedApps.sortedBy { appsById[it]?.label ?: it }
                LazyVerticalGrid(
                    columns = if (settings.gridLayout) GridCells.Adaptive(140.dp) else GridCells.Fixed(1),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    gridItems(pins, key = { it }) { component ->
                        PinnedApp(
                            app = appsById[component], component = component,
                            loading = state.loadingApps,
                            editable = !state.storageError,
                            onLaunch = { model.launch(component) },
                            onRemove = { model.setPinned(page, component, false) },
                        )
                    }
                }
            }
        }
        Text("Свайп — смена пространства", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 8.dp))
    }
    pickerWorkspace?.let { workspace ->
        AppPicker(
            state = state,
            pinned = settings.workspaces[workspace].pinnedApps,
            workspace = workspace,
            onToggle = { component, checked -> model.setPinned(workspace, component, checked) },
            onRetry = model::refreshApps,
            onDismiss = { pickerWorkspace = null },
        )
    }
}

@Composable
private fun PinnedApp(
    app: LauncherApp?, component: String, loading: Boolean, editable: Boolean,
    onLaunch: () -> Unit, onRemove: () -> Unit,
) {
    Column {
        Card(onClick = onLaunch, enabled = app != null, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (app != null) {
                    Image(app.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                }
                Text(app?.label ?: component.substringBefore('/'), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (app == null) Text(if (loading) "Загрузка…" else "Недоступно", style = MaterialTheme.typography.labelMedium)
            }
        }
        // A sibling control remains usable even when the launch card is disabled.
        TextButton(onClick = onRemove, enabled = editable, modifier = Modifier.fillMaxWidth()) {
            Text("Убрать", maxLines = 1)
        }
    }
}

@Composable
private fun AppPicker(
    state: WorkspaceUiState, pinned: Set<String>, workspace: Int,
    onToggle: (String, Boolean) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.apps, search) {
        state.apps.filter { it.label.contains(search, ignoreCase = true) || it.componentId.contains(search, ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().fillMaxHeight(.85f)) {
            Column(Modifier.padding(16.dp)) {
                Text("Приложения · ${workspace + 1}", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Поиск приложений") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (state.loadingApps) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f)) {
                    if (state.catalogError) {
                        item {
                            Text("Не удалось обновить список.")
                            TextButton(onClick = onRetry) { Text("Повторить") }
                        }
                    }
                    if (filtered.isEmpty() && !state.loadingApps) {
                        item { Text(if (search.isBlank()) "Нет доступных приложений." else "Ничего не найдено.", modifier = Modifier.padding(vertical = 16.dp)) }
                    }
                    items(filtered, key = { it.componentId }) { app ->
                        val checked = app.componentId in pinned
                        ListItem(
                            headlineContent = { Text(app.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Image(app.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp)) },
                            trailingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = !state.storageError) },
                            modifier = Modifier.toggleable(value = checked, enabled = !state.storageError, role = Role.Checkbox, onValueChange = { onToggle(app.componentId, it) }),
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Готово") }
            }
        }
    }
}
