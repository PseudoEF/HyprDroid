package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.model.WorkspaceItem
import dev.hyprdroid.launcher.model.SidebarModule

private fun Modifier.sidebarSwipe(left: Boolean, threshold: Float, action: () -> Unit): Modifier = composed {
    val currentAction by rememberUpdatedState(action)
    pointerInput(left, threshold) {
    var distance = 0f
    detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
        onDragEnd = { if (distance * (if (left) -1 else 1) >= threshold) currentAction() },
        onHorizontalDrag = { change, amount -> change.consume(); distance += amount })
}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModularSidebar(
    state: WorkspaceUiState, onClose: () -> Unit, onLaunch: (WorkspaceItem.AppShortcut) -> Unit,
    onPin: (WorkspaceItem.AppShortcut, Boolean) -> Unit, onRemove: (String) -> Unit,
    onReorder: (String, Int) -> Unit,
    panelModifier: Modifier = Modifier,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val entries = remember(state.sidebarEntries) { state.sidebarEntries.associateBy { it.item.id } }
    val items = state.shell?.sidebar.orEmpty()
    val enabled = state.shell != null && !state.shellError
    val modules = state.shell?.presentation?.sidebarModules ?: SidebarModule.entries.toSet()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val dismiss = { keyboard?.hide(); focus.clearFocus(force = true); onClose() }
    val close by rememberUpdatedState(dismiss)
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val listState = remember(adding) { LazyListState() }
    Box(Modifier.fillMaxSize().imePadding().testTag("sidebar-overlay")) {
        Box(Modifier.fillMaxSize().background(Mocha.Base.copy(alpha = .45f)).testTag("sidebar-scrim").clickable(onClickLabel = "Закрыть Sidebar", onClick = dismiss))
        Surface(color = Mocha.Mantle.copy(alpha = .97f), contentColor = Mocha.Text,
            shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Mocha.Mauve.copy(alpha = .5f)),
            modifier = Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(12.dp)
                .widthIn(max = 420.dp).fillMaxWidth(.80f).fillMaxHeight(.86f).then(panelModifier).testTag("sidebar")
                .sidebarSwipe(false, threshold) { close() }) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("sidebar-header")) {
                    Text("Sidebar", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "Закрыть Sidebar") }
                }
                if (SidebarModule.QUICK_LAUNCH in modules) {
                    FlowRow {
                        TextButton(onClick = { editing = !editing; adding = false }, enabled = enabled) { Text(if (editing) "Готово" else "Редактировать") }
                        if (editing) TextButton(onClick = { adding = !adding }, enabled = enabled) { Text(if (adding) "К панели" else "Добавить ярлык") }
                    }
                }
                if (!enabled) Text("Хранилище панели недоступно. Повторите чтение через меню оболочки.")
                LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag(if (adding) "sidebar-picker" else "sidebar-items"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (SidebarModule.SYSTEM_STATUS in modules && !adding && !editing) item(key = "module:status") { SystemStatusModule() }
                    if (SidebarModule.QUICK_LAUNCH in modules) {
                        item(key = "module:quick") { Text("Quick Launch", style = MaterialTheme.typography.labelLarge, color = Mocha.Subtext, modifier = Modifier.testTag("sidebar-quick-launch")) }
                        if (adding) {
                        item(key = "search") {
                            OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                                label = { Text("Поиск ярлыков") }, modifier = Modifier.fillMaxWidth())
                            Text("Ярлыки Android доступны, когда HyprDroid выбран домашним экраном.", style = MaterialTheme.typography.bodySmall)
                        }
                        val candidates = state.sidebarEntries.filter { it.app.label.contains(search, ignoreCase = true) }
                        if (candidates.isEmpty()) item(key = "empty-search") { Text(if (state.loadingApps) "Загрузка…" else "Доступных ярлыков нет") }
                        items(candidates, key = { it.item.id }) { entry ->
                            val pinned = items.any { it.id == entry.item.id }
                            AppTile(entry.app, entry.app.componentId, { onPin(entry.item, !pinned) }, emptyList(),
                                list = true, selected = pinned, modifier = Modifier.padding(vertical = 4.dp).testTag("sidebar-choice-${entry.item.id}"))
                        }
                        } else {
                        if (items.isEmpty()) item(key = "empty-quick") { Text("Панель пуста. Нажмите «Редактировать», чтобы добавить приложения или ярлыки.", modifier = Modifier.padding(12.dp)) }
                        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                            val shortcut = item as? WorkspaceItem.AppShortcut
                            val entry = entries[item.id]
                            Column(Modifier.animateItem().testTag("sidebar-item-${item.id}")) {
                                if (shortcut != null) AppTile(entry?.app, "Ярлык недоступен", { if (!editing) onLaunch(shortcut) },
                                    listOf(AppMenuAction("Убрать из Sidebar", enabled) { onRemove(item.id) }), list = true)
                                else Text(if (item is WorkspaceItem.WidgetPlaceholder) "${item.title} · виджет пока не поддерживается" else "Элемент новой версии", modifier = Modifier.padding(12.dp))
                                if (editing) FlowRow {
                                    TextButton(onClick = { onReorder(item.id, -1) }, enabled = enabled && index > 0,
                                        modifier = Modifier.testTag("sidebar-earlier-${item.id}")) { Text("Выше") }
                                    TextButton(onClick = { onReorder(item.id, 1) }, enabled = enabled && index < items.lastIndex) { Text("Ниже") }
                                    TextButton(onClick = { onRemove(item.id) }, enabled = enabled,
                                        modifier = Modifier.testTag("sidebar-remove-${item.id}")) { Text("Убрать") }
                                }
                            }
                        }
                        }
                    } else if (modules.isEmpty()) item(key = "empty-modules") { Text("Модули скрыты. Настройте их через меню верхней панели.", modifier = Modifier.padding(12.dp)) }
                }
            }
        }
    }
}
