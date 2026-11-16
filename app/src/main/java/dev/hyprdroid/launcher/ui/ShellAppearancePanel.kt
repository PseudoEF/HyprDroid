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
                Text("Рабочий стол и панели", style = MaterialTheme.typography.titleLarge)
                Text("Предпросмотр · Back отменяет изменения", style = MaterialTheme.typography.bodySmall, color = Mocha.Subtext)
                Row {
                    DesktopMode.entries.forEach { mode ->
                        FilterChip(selected = value.desktopMode == mode, enabled = !saving, onClick = { onChange(value.copy(desktopMode = mode)) },
                            label = { Text(if (mode == DesktopMode.CLEAN) "Clean" else "Classic") }, modifier = Modifier.padding(end = 8.dp).testTag("desktop-mode-${mode.name}"))
                    }
                }
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
