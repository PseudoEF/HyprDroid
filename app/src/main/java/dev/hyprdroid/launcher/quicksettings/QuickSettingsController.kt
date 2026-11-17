package dev.hyprdroid.launcher.quicksettings

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.camera2.*
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared application controller. Observes only while a tile surface is STARTED, never stores system state. */
class QuickSettingsController private constructor(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val mutable = MutableStateFlow<Map<QuickTile, QuickTileState>>(emptyMap())
    val states = mutable.asStateFlow()
    private var users = 0
    private var torchId: String? = null
    private var torch: Boolean? = null
    private var torchAvailable = false
    private var cameraRegistered = false
    private var receiverRegistered = false
    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) { if (id == torchId) { torch = enabled; torchAvailable = true; refresh() } }
        override fun onTorchModeUnavailable(id: String) { if (id == torchId) { torch = null; torchAvailable = false; refresh() } }
    }
    private val observer = object : ContentObserver(handler) { override fun onChange(selfChange: Boolean) { refresh() } }
    private val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, intent: Intent?) { refresh() } }
    private val poll = object : Runnable { override fun run() { if (users > 0) { refresh(); handler.postDelayed(this, 2000) } } }
    fun acquire() {
        if (++users != 1) return
        torchId = runCatching { camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull()
        torch = null; torchAvailable = false
        cameraRegistered = runCatching { camera.registerTorchCallback(callback, handler); true }.getOrDefault(false)
        listOf(Settings.System.SCREEN_BRIGHTNESS, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.ACCELEROMETER_ROTATION).forEach {
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(it), false, observer)
        }
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            }, ContextCompat.RECEIVER_EXPORTED); true
        }.getOrDefault(false)
        handler.post(poll)
    }
    fun release() {
        if (users == 0 || --users != 0) return
        handler.removeCallbacks(poll)
        if (cameraRegistered) runCatching { camera.unregisterTorchCallback(callback) }
        cameraRegistered = false
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
        context.contentResolver.unregisterContentObserver(observer)
    }
    fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    private fun refresh() {
        val write = Settings.System.canWrite(context)
        fun system(key: String, fallback: Int) = runCatching { Settings.System.getInt(context.contentResolver, key, fallback) }.getOrDefault(fallback)
        val brightness = system(Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(0, 255)
        val auto = system(Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val volume = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val bt = runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) null
            else context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
        }.getOrNull()
        val wifi = runCatching { context.getSystemService(WifiManager::class.java).wifiState }.getOrNull()
        val filter = runCatching { context.getSystemService(NotificationManager::class.java).currentInterruptionFilter }.getOrNull()
        val rotate = system(Settings.System.ACCELEROMETER_ROTATION, 0) != 0
        fun onOff(on: Boolean?) = when(on) { true -> "Включено"; false -> "Выключено"; null -> "Статус недоступен · в системе" }
        val wifiEnabled = when(wifi) { WifiManager.WIFI_STATE_ENABLED -> true; WifiManager.WIFI_STATE_DISABLED -> false; else -> null }
        val dnd = when(filter) { null, NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> null; NotificationManager.INTERRUPTION_FILTER_ALL -> false; else -> true }
        mutable.value = mapOf(
            QuickTile.WIFI to QuickTileState(onOff(wifiEnabled), wifiEnabled),
            QuickTile.BLUETOOTH to QuickTileState(onOff(bt), bt),
            QuickTile.TORCH to QuickTileState(when { torchId == null -> "Нет вспышки"; !hasPermission(Manifest.permission.CAMERA) -> "Нужен доступ к камере"; !torchAvailable -> "Занят / недоступен"; else -> onOff(torch) }, torch),
            QuickTile.DND to QuickTileState(onOff(dnd), dnd),
            QuickTile.BRIGHTNESS to QuickTileState("${if (auto) "Авто · " else ""}${brightness * 100 / 255}%${if (!write) " · в системе" else ""}", value = brightness / 255f, adjustable = write && !auto),
            QuickTile.VOLUME to QuickTileState("Медиа · ${if (max > 0) volume * 100 / max else 0}%", volume > 0, if (max > 0) volume.toFloat() / max else 0f, max > 0 && !audio.isVolumeFixed),
            QuickTile.ROTATION to QuickTileState(onOff(rotate) + if (!write) " · в системе" else "", rotate),
            QuickTile.SETTINGS to QuickTileState("Android"),
        )
    }
    /** Returns true only if another activity was opened, so overlay callers can dismiss their panel. */
    fun tap(tile: QuickTile): Boolean = try {
        when(tile) {
            QuickTile.TORCH -> {
                if (torchId == null) { message("Вспышка недоступна"); false }
                else if (!hasPermission(Manifest.permission.CAMERA)) permission(false)
                else { camera.setTorchMode(requireNotNull(torchId), torch != true); false }
            }
            QuickTile.ROTATION -> if (Settings.System.canWrite(context)) {
                val next = if (Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 0) 1 else 0
                check(Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, next)); refresh(); false
            } else settings(tile)
            QuickTile.VOLUME -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI); false }
            else -> settings(tile, panel = tile == QuickTile.WIFI)
        }
    } catch (_: Exception) { message("Действие недоступно; используйте системные настройки"); settings(tile) }
    fun adjust(tile: QuickTile, value: Float) {
        try {
            when(tile) {
                QuickTile.VOLUME -> if (!audio.isVolumeFixed) audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value.coerceIn(0f, 1f) * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0)
                QuickTile.BRIGHTNESS -> if (Settings.System.canWrite(context) && Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) != Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                    check(Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (value.coerceIn(0f, 1f) * 254).toInt() + 1))
                else -> Unit
            }
            refresh()
        } catch (_: Exception) { message("Изменение отклонено Android") }
    }
    fun settings(tile: QuickTile, panel: Boolean = false): Boolean {
        val action = when(tile) {
            QuickTile.WIFI -> if (panel) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            QuickTile.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            QuickTile.DND -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS
            QuickTile.BRIGHTNESS -> Settings.ACTION_DISPLAY_SETTINGS
            QuickTile.VOLUME -> Settings.ACTION_SOUND_SETTINGS
            QuickTile.ROTATION -> if (Build.VERSION.SDK_INT >= 31) Settings.ACTION_AUTO_ROTATE_SETTINGS else Settings.ACTION_DISPLAY_SETTINGS
            QuickTile.TORCH -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            QuickTile.SETTINGS -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply { if (tile == QuickTile.TORCH) data = Uri.parse("package:${context.packageName}") }
        if (open(intent)) return true
        if (open(Intent(Settings.ACTION_SETTINGS))) return true
        message("Системные настройки недоступны"); return false
    }
    fun writeAccess() = open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
    fun permission(bluetooth: Boolean) = open(Intent(context, TilePermissionActivity::class.java).putExtra("bluetooth", bluetooth))
    private fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    private fun message(text: String) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    companion object {
        @Volatile private var instance: QuickSettingsController? = null
        fun get(context: Context): QuickSettingsController = instance ?: synchronized(this) {
            instance ?: QuickSettingsController(context.applicationContext).also { instance = it }
        }
    }
}
