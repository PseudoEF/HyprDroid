package dev.hyprdroid.launcher.overlay

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.hyprdroid.launcher.data.ShellRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Only visible, opted-in launcher sessions start the service; no boot receiver or background restart. */
object OverlaySession {
    internal var homeVisible = false
    private val mutableRunning = MutableStateFlow(false)
    val running = mutableRunning.asStateFlow()
    internal fun running(value: Boolean) { mutableRunning.value = value }
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    internal fun failed(message: String?) { mutableError.value = message }

    fun start(activity: Activity): Boolean {
        if (!Settings.canDrawOverlays(activity)) { failed("Разрешение «Поверх других приложений» не выдано"); return false }
        return try {
            ContextCompat.startForegroundService(activity, Intent(activity, OverlaySidebarService::class.java))
            failed(null); true
        } catch (_: RuntimeException) { failed("Android не разрешил запустить панель. Повторите из открытого HyprDroid."); false }
    }
    fun stop(activity: Activity) { activity.stopService(Intent(activity, OverlaySidebarService::class.java)) }

    fun attach(activity: androidx.activity.ComponentActivity) {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { homeVisible = true; OverlaySidebarService.instance?.refreshVisibility() }
            override fun onResume(owner: LifecycleOwner) {
                activity.lifecycleScope.launch {
                    try {
                        if (ShellRepository(activity).settings.first().overlay.enabled && Settings.canDrawOverlays(activity) && !running.value) start(activity)
                    } catch (_: java.io.IOException) { failed("Не удалось прочитать настройки overlay") }
                }
            }
            override fun onStop(owner: LifecycleOwner) { homeVisible = false; OverlaySidebarService.instance?.refreshVisibility() }
        })
    }
}
