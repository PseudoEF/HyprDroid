package dev.hyprdroid.launcher.ui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private data class ControlStatus(val now: Long = System.currentTimeMillis(), val battery: Int? = null, val charging: Boolean = false)

@Composable
fun ControlCenter(onClose: () -> Unit, onSettings: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scrollState = rememberScrollState()
    val status by produceState(ControlStatus(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = withContext(Dispatchers.IO) {
                    val intent = try { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
                    catch (_: RuntimeException) { null }
                    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val state = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    ControlStatus(battery = if (scale > 0 && level >= 0) (100L * level / scale).toInt().coerceIn(0, 100) else null,
                        charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL)
                }
                delay(30_000)
            }
        }
    }
    Surface(color = Mocha.Base, contentColor = Mocha.Text, modifier = Modifier.fillMaxSize()
        .panelDismissGestures(PanelDismissDirection.UP, { !scrollState.canScrollForward }, onClose).testTag("control-center")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(scrollState), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .panelDismissGestures(PanelDismissDirection.UP, onDismiss = onClose, header = true).testTag("control-dismiss-header"), verticalAlignment = Alignment.CenterVertically) {
                Text("Control Center", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Закрыть Control Center") }
            }
            Spacer(Modifier.height(24.dp))
            Text(DateFormat.getTimeFormat(context).format(Date(status.now)), fontSize = 40.sp, color = Mocha.Lavender)
            Text(java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(Date(status.now)), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            ShellSurface(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Батарея · ${status.battery?.let { "$it%" } ?: "недоступно"}${if (status.charging) " · зарядка" else ""}")
                    status.battery?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }
                }
            }
            Spacer(Modifier.height(16.dp))
            for ((label, action) in listOf("Wi-Fi" to Settings.ACTION_WIFI_SETTINGS, "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
                "Экран" to Settings.ACTION_DISPLAY_SETTINGS, "Настройки Android" to Settings.ACTION_SETTINGS)) {
                OutlinedButton(onClick = { onSettings(action) }, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(min = 56.dp)) { Text(label) }
            }
            Text("Открывает системные настройки. Эта панель работает только внутри HyprDroid.", style = MaterialTheme.typography.bodyMedium, color = Mocha.Subtext, modifier = Modifier.widthIn(max = 640.dp).padding(top = 16.dp))
        }
    }
}
