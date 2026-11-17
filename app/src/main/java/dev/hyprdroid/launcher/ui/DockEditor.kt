package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.model.MAX_DOCK_APPS

/** Small, accessible alternative to dragging, with the same atomic repository operations. */
@Composable
internal fun DockEditor(state: WorkspaceUiState, pin: (String, Boolean) -> Unit,
    place: (String, String?) -> Unit, onClose: () -> Unit) {
    val order = state.shell?.dockComponents.orEmpty()
    val apps = state.apps.associateBy { it.componentId }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val enabled = state.shell != null && !state.shellError
    Surface(Modifier.fillMaxSize().blockHomeBehind().testTag("dock-editor"), color = Mocha.Base) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dock · ${order.size}/$MAX_DOCK_APPS", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Закрыть настройки dock") }
            }
            Text("Удерживайте и перетаскивайте иконки на Home. Здесь доступны кнопки порядка.", style = MaterialTheme.typography.bodySmall, color = Mocha.Subtext)
            LazyColumn(Modifier.weight(1f).testTag("dock-editor-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(order, key = { "pinned:$it" }) { component ->
                val index = order.indexOf(component)
                ShellSurface(Modifier.fillMaxWidth().testTag("dock-editor-$component")) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppImage(apps[component], Modifier.size(32.dp))
                        Text(apps[component]?.label ?: "Недоступное приложение", Modifier.weight(1f).padding(8.dp), maxLines = 1)
                        IconButton(onClick = { place(component, order[index - 1]) }, enabled = enabled && index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Раньше в dock: $component") }
                        IconButton(onClick = { place(component, order.getOrNull(index + 2)) }, enabled = enabled && index < order.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Позже в dock: $component") }
                        IconButton(onClick = { pin(component, false) }, enabled = enabled) { Icon(Icons.Default.Close, "Убрать из dock: $component") }
                    }
                }
            }
            item { TextButton(onClick = { adding = !adding }, modifier = Modifier.testTag("dock-add")) { Text(if (adding) "Свернуть приложения" else "Добавить приложения") } }
            if (adding) {
                item { OutlinedTextField(query, { query = it }, label = { Text("Поиск приложений") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    items(state.apps.filter { it.label.contains(query, ignoreCase = true) }, key = { it.componentId }) { app ->
                        val selected = app.componentId in order
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AppImage(app, Modifier.size(36.dp))
                            Text(app.label, Modifier.weight(1f).padding(8.dp))
                            Checkbox(selected, { pin(app.componentId, it) }, enabled = enabled && (selected || order.size < MAX_DOCK_APPS),
                                modifier = Modifier.testTag("dock-pin-${app.componentId}"))
                        }
                    }
            } else if (order.isEmpty()) item { Text("Dock пуст. Добавьте приложения; настройки его видимости остаются в «Рабочий стол и панели».", color = Mocha.Subtext) }
            }
        }
    }
}
