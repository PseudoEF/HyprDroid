package dev.hyprdroid.launcher.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hyprdroid.launcher.power.PowerDialogService

@Composable
fun PowerMenu(onClose: () -> Unit) {
    val context = LocalContext.current
    val available by PowerDialogService.available.collectAsStateWithLifecycle()
    var failed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().testTag("power-menu")) {
        Box(Modifier.fillMaxSize().background(Mocha.Base.copy(alpha = .6f)).clickable(onClick = onClose))
        ShellSurface(Modifier.align(Alignment.Center).safeDrawingPadding().padding(20.dp).widthIn(max = 440.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Питание", style = MaterialTheme.typography.headlineSmall)
                Text("Выключение и перезагрузка выполняются только в системном меню Android.")
                if (!available) Text("Чтобы открыть его отсюда, включите необязательную службу «HyprDroid · системное меню питания» в специальных возможностях. Она не читает экраны и не отслеживает приложения. Можно пользоваться физическими кнопками устройства.")
                if (failed) Text("Android не открыл меню. Используйте физические кнопки или проверьте доступ.")
                Button(onClick = { if (PowerDialogService.showPowerDialog()) onClose() else failed = true }, enabled = available,
                    modifier = Modifier.fillMaxWidth().testTag("power-system-action")) { Text("Системное меню питания") }
                if (!available) TextButton(onClick = { if (openSystemSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)) onClose() }) { Text("Настроить доступ") }
                else TextButton(onClick = { PowerDialogService.disable() }) { Text("Отключить доступ") }
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        }
    }
}
