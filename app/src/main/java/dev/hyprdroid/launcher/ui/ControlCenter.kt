package dev.hyprdroid.launcher.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.model.QuickSettingsConfig
import java.util.Date

/** The original Control Center evolved into the single Home Quick Settings shade. */
@Composable
fun ControlCenter(onClose: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { ShellRepository(context) }
    var configuration by remember { mutableStateOf<QuickSettingsConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val status by rememberSystemStatus()
    val scroll = rememberScrollState()
    LaunchedEffect(repository) {
        try { repository.quickSettings.collect { configuration = it } }
        catch (_: Exception) { error = "Не удалось прочитать плитки. Данные не изменены." }
    }
    Box(Modifier.fillMaxSize().testTag("control-center")) {
        Box(Modifier.fillMaxSize().background(Mocha.Base.copy(alpha = .55f)).clickable(onClickLabel = "Закрыть Quick Settings", onClick = onClose).testTag("control-scrim"))
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            ShellSurface(Modifier.align(Alignment.TopCenter).widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight * .85f)
                .panelDismissGestures(PanelDismissDirection.UP, { !scroll.canScrollForward }, onClose).testTag("quick-shade-surface")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 96.dp)
                        .panelDismissGestures(PanelDismissDirection.UP, onDismiss = onClose, header = true).testTag("control-dismiss-header"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(DateFormat.getTimeFormat(context).format(Date(status.now)), style = MaterialTheme.typography.headlineMedium, color = Mocha.Lavender, modifier = Modifier.testTag("quick-time"))
                            Text(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(status.now)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("quick-date"))
                            Text("Батарея · ${status.battery?.let { "$it%" } ?: "недоступно"}${if (status.charging) " · зарядка" else ""}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = onEdit) { Icon(Icons.Default.Settings, "Настроить Quick Settings") }
                        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Закрыть Control Center") }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(scroll, enabled = scroll.maxValue > 0)) {
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        configuration?.let { saved ->
                            if (saved.topbar.isEmpty()) Text("Плитки отключены. Добавьте их через настройки шторки.")
                            QuickSettingsContent(saved.topbar, onExternal = onClose)
                        }
                    }
                }
            }
        }
    }
}
