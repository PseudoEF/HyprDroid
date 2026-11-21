package dev.hyprdroid.launcher.modules

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.hyprdroid.launcher.ui.Mocha
import dev.hyprdroid.launcher.workspaceengine.WindowId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class MemoryReading(val totalBytes: Long, val availableBytes: Long) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0)
    val usedPercent: Int get() = if (totalBytes <= 0) 0 else ((usedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

internal data class BatteryReading(val percent: Int?, val charging: Boolean?, val plugged: Boolean)

internal data class StorageReading(val totalBytes: Long, val availableBytes: Long)

internal data class MonitorSnapshot(
    val memory: MemoryReading? = null,
    val battery: BatteryReading? = null,
    val storage: StorageReading? = null,
    val androidVersion: String = "",
    val device: String = "",
    val cpuCores: Int = 0,
    val uptimeMillis: Long = 0,
)

internal class SystemMonitorState(windowId: WindowId) : ModuleState(windowId) {
    var snapshot by mutableStateOf(MonitorSnapshot())
        private set

    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var latestBattery: BatteryReading? = null

    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        latestBattery = readBattery(app, null)
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    latestBattery = readBattery(app, intent)
                    snapshot = snapshot.copy(battery = latestBattery)
                }
            }
        }
        receiver = try {
            ContextCompat.registerReceiver(
                app,
                listener,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            listener
        } catch (_: RuntimeException) {
            null
        }
    }

    suspend fun refreshAsync() {
        val context = appContext ?: return
        val battery = latestBattery
        val next = withContext(Dispatchers.Default) { readSnapshot(context, battery) }
        snapshot = next
    }

    override fun dispose() {
        receiver?.let { listener ->
            try {
                appContext?.unregisterReceiver(listener)
            } catch (_: RuntimeException) {
                // The receiver may already be gone after process or context teardown.
            }
        }
        receiver = null
        appContext = null
    }
}

internal object SystemMonitorModule : TileModule {
    override val id = "hyprdroid.system"
    override val title = "Система"
    override val summary = "ОЗУ, батарея, устройство"
    override val glyph = ModuleGlyph.SYSTEM

    override fun createState(windowId: WindowId): ModuleState = SystemMonitorState(windowId)

    @Composable
    override fun Content(state: ModuleState, modifier: Modifier) {
        val monitor = state as SystemMonitorState
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(monitor, context) {
            monitor.attach(context)
            onDispose { monitor.dispose() }
        }
        LaunchedEffect(monitor, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    monitor.refreshAsync()
                    delay(2_000)
                }
            }
        }
        val snapshot = monitor.snapshot
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("module-system-${state.windowId.value}"),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            MonitorRow("ОЗУ", snapshot.memory?.let {
                "${it.usedPercent}% · ${megabytes(it.usedBytes)} / ${megabytes(it.totalBytes)} МБ"
            } ?: "недоступно")
            MonitorRow("Батарея", batteryText(snapshot.battery))
            MonitorRow("Хранилище", snapshot.storage?.let {
                "${gigabytes(it.availableBytes)} ГБ свободно из ${gigabytes(it.totalBytes)}"
            } ?: "недоступно")
            MonitorRow("Android", snapshot.androidVersion.ifBlank { "недоступно" })
            MonitorRow("Устройство", snapshot.device.ifBlank { "недоступно" })
            MonitorRow("Ядра ЦП", if (snapshot.cpuCores > 0) snapshot.cpuCores.toString() else "недоступно")
            MonitorRow("Аптайм", if (snapshot.uptimeMillis > 0) uptimeText(snapshot.uptimeMillis) else "недоступно")
            Text(
                text = "Нагрузка ЦП и температура недоступны без root",
                style = MaterialTheme.typography.labelSmall,
                color = Mocha.Subtext,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun MonitorRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Mocha.Subtext,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = Mocha.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.6f),
        )
    }
}

private fun readSnapshot(context: Context, battery: BatteryReading?): MonitorSnapshot = MonitorSnapshot(
    memory = readMemory(context),
    battery = battery ?: readBattery(context, null),
    storage = readStorage(context),
    androidVersion = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
    device = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
    cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(0),
    uptimeMillis = SystemClock.elapsedRealtime(),
)

private fun readMemory(context: Context): MemoryReading? {
    return try {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        if (info.totalMem > 0 && info.availMem >= 0) MemoryReading(info.totalMem, info.availMem) else null
    } catch (_: RuntimeException) {
        null
    }
}

private fun readStorage(context: Context): StorageReading? = try {
    val stats = StatFs(context.filesDir.absolutePath)
    if (stats.totalBytes > 0) StorageReading(stats.totalBytes, stats.availableBytes) else null
} catch (_: RuntimeException) {
    null
}

@Suppress("DEPRECATION")
private fun readBattery(context: Context, intent: Intent?): BatteryReading? {
    val manager = context.getSystemService(BatteryManager::class.java)
    val capacity = (try {
        manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: RuntimeException) {
        null
    })?.takeIf { it in 0..100 }

    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val percent = capacity ?: if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
    val charging = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
        BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
        else -> try { manager?.isCharging } catch (_: RuntimeException) { null }
    }
    if (percent == null && charging == null) return null
    return BatteryReading(percent, charging, plugged)
}

private fun batteryText(reading: BatteryReading?): String {
    if (reading == null) return "недоступно"
    val percent = reading.percent?.let { "$it%" } ?: "процент недоступен"
    val state = when (reading.charging) {
        true -> "заряжается"
        false -> if (reading.plugged) "подключено, не заряжается" else "не заряжается"
        null -> "состояние неизвестно"
    }
    return "$percent · $state"
}

private fun megabytes(bytes: Long): Long = (bytes / (1024L * 1024L)).coerceAtLeast(0)

private fun gigabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))

private fun uptimeText(millis: Long): String {
    val totalMinutes = millis / 60_000
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "$days д $hours ч"
        hours > 0 -> "$hours ч $minutes мин"
        else -> "$minutes мин"
    }
}
