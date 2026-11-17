package dev.hyprdroid.launcher.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.hyprdroid.launcher.data.LauncherApp
import dev.hyprdroid.launcher.model.WORKSPACE_COUNT
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Date

@Composable
fun Waybar(current: Int, onSelect: (Int) -> Unit, menuItems: List<AppMenuAction>, modifier: Modifier = Modifier,
    onDrawer: (() -> Unit)? = null, onSidebar: (() -> Unit)? = null, onLeftPanel: (() -> Unit)? = null,
    onMenuVisibilityChanged: (Boolean) -> Unit = {}, onSettings: (() -> Unit)? = null, home: Boolean = true, onHome: (() -> Unit)? = null, onMenuRequest: (() -> Unit)? = null) {
    var menuOpen by remember { mutableStateOf(false) }
    val popupOpen = menuOpen
    SideEffect { onMenuVisibilityChanged(popupOpen) }
    DisposableEffect(Unit) { onDispose { onMenuVisibilityChanged(false) } }
    ShellSurface(modifier) {
        BoxWithConstraints {
            val density = LocalDensity.current
            val badgeSize = maxOf(26.dp, with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + 4.dp)
            val targetSize = maxOf(48.dp, badgeSize + 8.dp)
            // Keep one row: panel actions also remain available in the existing overflow menu.
            val showDetails = home || maxWidth >= 260.dp
            val showRight = maxWidth >= 340.dp && density.fontScale <= 1.35f
            val showLeft = maxWidth >= 480.dp && density.fontScale <= 1.35f
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clipToBounds().horizontalScroll(rememberScrollState())) {
                        if (!home) { TextButton(onClick = { onHome?.invoke() }) { Text("Home") } } else repeat(WORKSPACE_COUNT) { index ->
                            Box(
                                Modifier.size(targetSize).selectable(selected = current == index, role = Role.Tab, onClick = { onSelect(index) })
                                    .semantics { contentDescription = "Рабочее пространство ${index + 1}" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(badgeSize).background(if (current == index) Mocha.Mauve else Mocha.Surface, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                                    Text("${index + 1}", color = if (current == index) Mocha.Base else Mocha.Subtext, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    if (showDetails) ShellClock()
                    if (showDetails) onSettings?.let { IconButton(onClick = it, modifier = Modifier.testTag("topbar-settings")) { Icon(Icons.Default.Settings, "Настройки HyprDroid") } }
                    onDrawer?.let { IconButton(onClick = it) { DrawerGlyph("Открыть app drawer") } }
                    if (showRight) onSidebar?.let { IconButton(onClick = it, modifier = Modifier.testTag("sidebar-open")) { Icon(Icons.Default.Menu, "Открыть Sidebar") } }
                    if (showLeft) onLeftPanel?.let { IconButton(onClick = it, modifier = Modifier.testTag("left-panel-open")) { Text("◧", color = Mocha.Lavender, modifier = Modifier.semantics { contentDescription = "Открыть левую панель" }) } }
                    Box {
                        IconButton(onClick = { if (onMenuRequest != null) onMenuRequest() else menuOpen = true }) { Icon(Icons.Default.MoreVert, "Меню оболочки") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                            modifier = Modifier.sidebarClosingGesture(onClose = { menuOpen = false }).testTag("shell-menu")) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).sidebarClosingGesture(onClose = { menuOpen = false })
                                .testTag("shell-menu-gesture"), verticalAlignment = Alignment.CenterVertically) {
                                Text("Меню", modifier = Modifier.weight(1f).padding(start = 12.dp))
                                IconButton(onClick = { menuOpen = false }) { Icon(Icons.Default.Close, "Закрыть меню") }
                            }
                            menuItems.forEach { item ->
                                DropdownMenuItem(text = { Text(item.label) }, enabled = item.enabled, onClick = { menuOpen = false; item.onClick() })
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun ShellClock() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = System.currentTimeMillis()
                delay(60_000L - value % 60_000L)
            }
        }
    }
    Text(DateFormat.getTimeFormat(context).format(Date(now)), maxLines = 1, softWrap = false,
        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge, color = Mocha.Text)
}

@Composable
fun FavoriteDock(
    components: List<String>, apps: Map<String, LauncherApp>,
    onLaunch: (String) -> Unit, menu: (String) -> List<AppMenuAction>,
    onDrawer: () -> Unit, modifier: Modifier = Modifier,
    showDrawer: Boolean = true,
) {
    if (components.isEmpty() && !showDrawer) return
    ShellSurface(modifier.widthIn(max = 440.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            components.forEach { component ->
                AppTile(apps[component], component, onClick = { onLaunch(component) }, menu = menu(component), compact = true, modifier = Modifier.width(52.dp).testTag("dock-app-$component"))
            }
            if (showDrawer) IconButton(onClick = onDrawer, modifier = Modifier.size(52.dp)) { DrawerGlyph() }
        }
    }
}

@Composable
fun DrawerGlyph(description: String = "Все приложения") {
    Canvas(Modifier.size(22.dp).semantics { contentDescription = description }) {
        val side = size.width * .38f
        for (x in 0..1) for (y in 0..1) {
            drawRoundRect(Mocha.Lavender, topLeft = Offset(x * (size.width - side), y * (size.height - side)), size = Size(side, side), cornerRadius = CornerRadius(3.dp.toPx()))
        }
    }
}

@Composable
fun EditToolbar(workspace: Int, grid: Boolean, onAdd: () -> Unit, onLayout: () -> Unit, onDone: () -> Unit, onOrder: () -> Unit, onAppearance: () -> Unit, modifier: Modifier = Modifier) {
    ShellSurface(modifier, highlighted = true) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Text("ПРОСТРАНСТВО 0${workspace + 1}", style = MaterialTheme.typography.labelSmall, color = Mocha.Lavender)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onAdd) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text("Приложения") }
                    TextButton(onClick = onLayout) { Text(if (grid) "Раскладка: сетка" else "Раскладка: список") }
                    TextButton(onClick = onOrder) { Text("Порядок") }
                    TextButton(onClick = onAppearance) { Text("Вид") }
                }
                IconButton(onClick = onDone) { Icon(Icons.Default.Check, "Готово, завершить редактирование", tint = Mocha.Green) }
            }
        }
    }
}
