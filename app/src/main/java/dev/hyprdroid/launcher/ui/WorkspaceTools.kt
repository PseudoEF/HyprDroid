package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.model.*
import kotlin.math.roundToInt

@Composable
private fun ToolHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Закрыть настройки пространства") }
    }
}

@Composable
fun WorkspaceAppearanceScreen(workspace: Workspace, app: LauncherApp?, onApply: (WorkspaceAppearance) -> Unit, onClose: () -> Unit) {
    var icon by rememberSaveable(workspace.id) { mutableFloatStateOf(workspace.appearance.iconSizeDp.toFloat()) }
    var spacing by rememberSaveable(workspace.id) { mutableFloatStateOf(workspace.appearance.spacingDp.toFloat()) }
    var density by rememberSaveable(workspace.id) { mutableStateOf(workspace.appearance.density.name) }
    Surface(color = Mocha.Base, contentColor = Mocha.Text, modifier = Modifier.fillMaxSize().blockHomeBehind().testTag("workspace-appearance")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                ToolHeader("Вид пространства 0${workspace.id + 1}", onClose)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { AppImage(app, Modifier.size(icon.roundToInt().dp)) }
                Text("Размер иконок · ${icon.roundToInt()} dp", Modifier.padding(top = 24.dp))
                Slider(value = icon, onValueChange = { icon = it }, valueRange = 36f..64f, steps = 6,
                    modifier = Modifier.testTag("icon-size-slider").semantics { contentDescription = "Размер иконок" })
                Text("Плотность сетки", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                GridDensity.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().selectable(selected = density == option.name, role = Role.RadioButton, onClick = { density = option.name }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = density == option.name, onClick = null)
                        Text(option.title, Modifier.padding(start = 12.dp))
                    }
                }
                Text("Расстояние между элементами · ${spacing.roundToInt()} dp", Modifier.padding(top = 16.dp))
                Slider(value = spacing, onValueChange = { spacing = it }, valueRange = 4f..24f, steps = 4,
                    modifier = Modifier.testTag("spacing-slider").semantics { contentDescription = "Расстояние между элементами" })
                Text("Изменения применяются только к этому пространству. Сетка учитывает размер иконок и экрана.", color = Mocha.Subtext)
                Button(onClick = { onApply(workspace.appearance.copy(iconSizeDp = icon.roundToInt(), spacingDp = spacing.roundToInt(), density = GridDensity.valueOf(density))) }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("Применить") }
            }
        }
    }
}

@Composable
fun WorkspaceOrderScreen(workspace: Workspace, apps: Map<String, LauncherApp>, onReorder: (String, Int) -> Unit, onClose: () -> Unit) {
    Surface(color = Mocha.Base, contentColor = Mocha.Text, modifier = Modifier.fillMaxSize().blockHomeBehind().testTag("workspace-order")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) { ToolHeader("Порядок · 0${workspace.id + 1}", onClose) }
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth().weight(1f)) {
                if (workspace.items.isEmpty()) item { Text("В пространстве пока нет элементов", Modifier.padding(16.dp)) }
                itemsIndexed(workspace.items, key = { _, item -> item.id }) { index, item ->
                    val app = (item as? WorkspaceItem.AppShortcut)?.let { apps[it.componentId] }
                    val label = app?.label ?: "Недоступный элемент"
                    ListItem(
                        headlineContent = { Text(label) }, supportingContent = { Text("Позиция ${index + 1}") },
                        leadingContent = { AppImage(app, Modifier.size(40.dp)) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onReorder(item.id, -1) }, enabled = index > 0, modifier = Modifier.testTag("earlier-${item.id}")) { Icon(Icons.Default.KeyboardArrowUp, "Раньше: $label") }
                                IconButton(onClick = { onReorder(item.id, 1) }, enabled = index < workspace.items.lastIndex, modifier = Modifier.testTag("later-${item.id}")) { Icon(Icons.Default.KeyboardArrowDown, "Позже: $label") }
                            }
                        }, modifier = Modifier.testTag("order-item-${item.id}"),
                    )
                }
            }
        }
    }
}
