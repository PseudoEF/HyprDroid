package dev.hyprdroid.launcher.ui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Date

internal data class SystemStatus(val now: Long = System.currentTimeMillis(), val battery: Int? = null, val charging: Boolean = false)

@Composable
internal fun rememberSystemStatus(): State<SystemStatus> {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(SystemStatus(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = withContext(Dispatchers.IO) {
                    val intent = try { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
                    catch (_: RuntimeException) { null }
                    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val state = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    SystemStatus(battery = if (scale > 0 && level >= 0) (100L * level / scale).toInt().coerceIn(0, 100) else null,
                        charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL)
                }
                delay(30_000)
            }
        }
    }
}

@Composable
internal fun SystemStatusModule() {
    val context = LocalContext.current
    val status by rememberSystemStatus()
    ShellSurface(Modifier.fillMaxWidth().testTag("sidebar-system-status")) {
        Column(Modifier.padding(12.dp)) {
            Text("System Status", style = MaterialTheme.typography.labelMedium, color = Mocha.Subtext)
            Text(DateFormat.getTimeFormat(context).format(Date(status.now)), style = MaterialTheme.typography.headlineSmall)
            Text("Батарея · ${status.battery?.let { "$it%" } ?: "недоступно"}${if (status.charging) " · зарядка" else ""}")
        }
    }
}

/** Shared built-in module renderer, used by either side in Home and overlay windows. */
@Composable
internal fun PanelStatusModule(id: String) {
    if (id == "SYSTEM_STATUS") { SystemStatusModule(); return }
    val known = dev.hyprdroid.launcher.model.PanelModule.entries.find { it.name == id }
    if (id !in setOf("CLOCK", "DATE", "BATTERY")) {
        ShellSurface(Modifier.fillMaxWidth().testTag("panel-module-$id")) {
            Text("Недоступный модуль. Запись сохранена.", modifier = Modifier.padding(16.dp))
        }
        return
    }
    val context = LocalContext.current
    val status by rememberSystemStatus()
    ShellSurface(Modifier.fillMaxWidth().testTag("panel-module-$id")) {
        Column(Modifier.padding(16.dp)) {
            Text(known?.title ?: "Недоступный модуль", style = MaterialTheme.typography.labelMedium, color = Mocha.Subtext)
            Text(when (id) {
                "CLOCK" -> DateFormat.getTimeFormat(context).format(Date(status.now))
                "DATE" -> DateFormat.getLongDateFormat(context).format(Date(status.now))
                "BATTERY" -> "${status.battery?.let { "$it%" } ?: "недоступно"}${if (status.charging) " · зарядка" else ""}"
                else -> "Этот тип пока не поддерживается. Запись сохранена."
            }, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
