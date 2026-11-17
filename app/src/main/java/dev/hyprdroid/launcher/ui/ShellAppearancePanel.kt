package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.model.*

/** Local draft is rendered by the home behind this panel; only Apply persists it. */
@Composable
internal fun ShellAppearancePanel(value: ShellPresentation, saving: Boolean, onChange: (ShellPresentation) -> Unit,
    onApply: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().testTag("shell-appearance-panel")) {
        Box(Modifier.fillMaxSize().clickable(onClickLabel = "Отменить предпросмотр", onClick = { if (!saving) onCancel() }))
        Surface(color = Mocha.Mantle, contentColor = Mocha.Text, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Mocha.Outline),
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp).widthIn(max = 600.dp).fillMaxWidth().heightIn(max = 460.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text("Рабочий стол и панели", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).sidebarClosingGesture(enabled = !saving, onClose = onCancel).testTag("appearance-dismiss-gesture"))
                Text("Предпросмотр · Back отменяет изменения", style = MaterialTheme.typography.bodySmall, color = Mocha.Subtext)
                Row {
                    DesktopMode.entries.forEach { mode ->
                        FilterChip(selected = value.desktopMode == mode, enabled = !saving, onClick = { onChange(value.copy(desktopMode = mode)) },
                            label = { Text(if (mode == DesktopMode.CLEAN) "Clean" else "Classic") }, modifier = Modifier.padding(end = 8.dp).testTag("desktop-mode-${mode.name}"))
                    }
                }
                ButtonVisibilitySetting("Drawer в Topbar", "drawer-topbar", value.drawerTopbar, !saving) { onChange(value.copy(drawerTopbar = it)) }
                ButtonVisibilitySetting("Drawer в Dock", "drawer-dock", value.drawerDock, !saving) { onChange(value.copy(drawerDock = it)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(value.sidebarTopbar, { onChange(value.copy(sidebarTopbar = it)) }, enabled = !saving, modifier = Modifier.testTag("sidebar-topbar"))
                    Text("Кнопка Sidebar в Topbar")
                }
                Text("Жесты и пункты меню остаются доступны при скрытых кнопках.", style = MaterialTheme.typography.bodySmall)
                Text("Масштабирование обоев", style = MaterialTheme.typography.titleMedium)
                WallpaperScale.entries.forEach { scale ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { onChange(value.copy(wallpaperScale = scale)) }
                        .testTag("wallpaper-scale-${scale.name}"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(value.wallpaperScale == scale, { onChange(value.copy(wallpaperScale = scale)) }, enabled = !saving)
                        Text(when (scale) { WallpaperScale.CROP -> "Заполнить с обрезкой"; WallpaperScale.FIT -> "Вписать целиком"; WallpaperScale.STRETCH -> "Растянуть" })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(value.wallpaperMotion, { onChange(value.copy(wallpaperMotion = it)) }, enabled = !saving, modifier = Modifier.testTag("wallpaper-motion"))
                    Text("Анимация GIF на рабочем столе")
                }
                Text("GIF: до 24 МиБ и 8 Мп; декодирование до 1024 px (512 на малом объёме памяти). Анимация останавливается вне Home, под панелями и в энергосбережении.", style = MaterialTheme.typography.bodySmall)
                Text("Dock", style = MaterialTheme.typography.titleMedium)
                if (value.dockVisibility == DockVisibility.AUTO) Text("Скрывается через 3,5 секунды. Касание фона снова показывает dock.", style = MaterialTheme.typography.bodySmall)
                DockVisibility.entries.forEach { visibility ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { onChange(value.copy(dockVisibility = visibility)) }
                        .testTag("dock-visibility-${visibility.name}"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = value.dockVisibility == visibility, enabled = !saving, onClick = { onChange(value.copy(dockVisibility = visibility)) })
                        Text(when (visibility) { DockVisibility.ALWAYS -> "Всегда"; DockVisibility.AUTO -> "Автоматически скрывать"; DockVisibility.HIDDEN -> "Скрыт" })
                    }
                }
                Text("Модули Sidebar", style = MaterialTheme.typography.titleMedium)
                SidebarModule.entries.forEach { module ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(module in value.sidebarModules, enabled = !saving, onCheckedChange = { checked ->
                            onChange(value.copy(sidebarModules = if (checked) value.sidebarModules + module else value.sidebarModules - module))
                        }, modifier = Modifier.testTag("sidebar-module-${module.name}"))
                        Text(if (module == SidebarModule.QUICK_LAUNCH) "Quick Launch" else "System Status")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, enabled = !saving) { Text("Отмена") }
                    Button(onClick = onApply, enabled = !saving, modifier = Modifier.testTag("apply-shell-appearance")) { Text(if (saving) "Сохранение…" else "Сохранить") }
                }
            }
        }
    }
}

@Composable
private fun ButtonVisibilitySetting(label: String, tag: String, value: ShellButtonVisibility, enabled: Boolean, onChange: (ShellButtonVisibility) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    Row {
        ShellButtonVisibility.entries.forEach { choice -> FilterChip(choice == value, { onChange(choice) }, enabled = enabled,
            label = { Text(when (choice) { ShellButtonVisibility.AUTO -> "Авто"; ShellButtonVisibility.SHOWN -> "Вкл"; ShellButtonVisibility.HIDDEN -> "Выкл" }) },
            modifier = Modifier.testTag("$tag-${choice.name}")) }
    }
    if (value == ShellButtonVisibility.AUTO) Text("В Clean скрыто, в Classic показано", style = MaterialTheme.typography.bodySmall)
}
