package dev.hyprdroid.launcher.overlay

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.hyprdroid.launcher.MainActivity
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.model.OverlaySettings
import dev.hyprdroid.launcher.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect

/** Explicitly enabled foreground service. No app inspection, boot start, sticky restart or hidden API. */
class OverlaySidebarService : Service() {
    companion object {
        internal var instance: OverlaySidebarService? = null
        private const val CHANNEL = "experimental-sidebar"
        private const val STOP = "dev.hyprdroid.launcher.STOP_OVERLAY"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windows: WindowManager
    private lateinit var windowContext: Context
    private lateinit var owner: OverlayOwner
    private lateinit var repository: ShellRepository
    private var root: View? = null
    private var compose: ComposeView? = null
    private var panel = false
    private var settings = OverlaySettings()
    private var observing: Job? = null
    private var registered = false
    private var screenOff = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOff = intent?.action == Intent.ACTION_SCREEN_OFF
            panel = false; refreshVisibility()
        }
    }
    private val appOps by lazy { getSystemService(AppOpsManager::class.java) }
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, packageName ->
        if (packageName == this.packageName) Handler(mainLooper).post {
            if (!Settings.canDrawOverlays(this)) { OverlaySession.failed("Разрешение overlay отозвано"); stopSelf() }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        instance = this
        windowContext = if (Build.VERSION.SDK_INT >= 30) createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else this
        windows = windowContext.getSystemService(WindowManager::class.java)
        owner = OverlayOwner()
        repository = ShellRepository(this)
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        registered = true
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            disableOverlay()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Экспериментальная Sidebar", NotificationManager.IMPORTANCE_LOW))
            val home = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("HyprDroid · Sidebar поверх приложений")
                .setContentText("Правая зона активна. Нажмите, чтобы открыть HyprDroid.")
                .setContentIntent(home).setOngoing(true).addAction(Notification.Action.Builder(null, "Отключить", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(62, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(62, notification)
            OverlaySession.running(true)
            if (observing == null) observing = scope.launch {
                try {
                    repository.settings.map { it.overlay }.distinctUntilChanged().collect {
                        settings = it
                        if (!it.enabled) stopSelf() else refreshVisibility()
                    }
                } catch (_: java.io.IOException) { OverlaySession.failed("Не удалось прочитать overlay-настройки"); stopSelf() }
            }
        } catch (_: RuntimeException) { OverlaySession.failed("Не удалось запустить foreground-службу Sidebar"); stopSelf() }
        return START_NOT_STICKY
    }
    private fun dp(value: Int) = (value * windowContext.resources.displayMetrics.density).toInt()
    internal fun refreshVisibility() {
        if (!settings.enabled) { removeWindow(); return }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val awake = getSystemService(PowerManager::class.java).isInteractive
        if (OverlaySession.homeVisible || screenOff || locked || !awake) { panel = false; removeWindow(); return }
        render()
    }
    private fun render() {
        removeWindow()
        try {
            val params: WindowManager.LayoutParams
            val view: View
            if (panel) {
                val holder = BackFrame(windowContext, ::closePanel)
                holder.setViewTreeLifecycleOwner(owner)
                holder.setViewTreeViewModelStoreOwner(owner)
                holder.setViewTreeSavedStateRegistryOwner(owner)
                val model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[WorkspaceViewModel::class.java]
                val content = ComposeView(windowContext).apply {
                    setContent {
                        val state by model.state.collectAsState()
                        ShellTheme {
                            Box(Modifier.fillMaxSize()) {
                                ModularSidebar(state, ::closePanel, { closePanel(); model.launchSidebar(it) }, model::setSidebarItem,
                                    model::removeSidebarItem, model::reorderSidebar, model::placeSidebarBefore,
                                    onStopOverlay = ::disableOverlay)
                                state.message?.let { Text(it) }
                            }
                        }
                    }
                }
                compose = content
                holder.addView(content, FrameLayout.LayoutParams(-1, -1))
                view = holder
                params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                owner.visible(true)
            } else {
                view = OverlayEdgeView(windowContext) { panel = true; refreshVisibility() }
                val screen = windowContext.resources.displayMetrics.heightPixels
                val height = dp(settings.heightDp).coerceAtMost(screen / 2)
                params = WindowManager.LayoutParams(dp(24), height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
                params.gravity = Gravity.TOP or Gravity.RIGHT
                params.x = dp(settings.insetDp)
                params.y = ((screen - height) * settings.positionPercent / 100).coerceAtLeast(dp(32))
            }
            params.setTitle(if (panel) "HyprDroid Quick Shell" else "HyprDroid Sidebar activation")
            root = view
            windows.addView(view, params)
            if (panel) { view.alpha = 0f; view.animate().alpha(1f).setDuration(180).start(); view.requestFocus() }
        } catch (_: RuntimeException) {
            removeWindow(); OverlaySession.failed("Android не разрешил показать overlay-панель"); stopSelf()
        }
    }
    private fun closePanel() {
        val current = root ?: return
        current.animate().alpha(0f).setDuration(150).withEndAction {
            if (root === current) { panel = false; refreshVisibility() }
        }.start()
    }
    private fun disableOverlay() {
        removeWindow()
        scope.launch {
            try { repository.setOverlay(repository.settings.first().overlay.copy(enabled = false)) }
            catch (_: java.io.IOException) { OverlaySession.failed("Панель остановлена; не удалось сохранить отключение") }
            finally { stopSelf() }
        }
    }
    private fun removeWindow() {
        root?.let { view -> view.animate().cancel(); try { windows.removeViewImmediate(view) } catch (_: IllegalArgumentException) { } }
        compose?.disposeComposition(); compose = null; root = null
        owner.visible(false)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); panel = false; refreshVisibility() }
    override fun onDestroy() {
        removeWindow()
        if (registered) unregisterReceiver(receiver)
        appOps.stopWatchingMode(permissionListener)
        scope.cancel(); owner.destroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        OverlaySession.running(false); instance = null
        super.onDestroy()
    }

    private class BackFrame(context: Context, private val close: () -> Unit) : FrameLayout(context) {
        // Keep Android 13 classes out of the API 29–32 view's fields/signatures.
        private var platformBack: Any? = null
        init { isFocusableInTouchMode = true }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (Build.VERSION.SDK_INT >= 33) platformBack = PlatformBack(this, close)
        }
        override fun onDetachedFromWindow() {
            if (Build.VERSION.SDK_INT >= 33) (platformBack as? PlatformBack)?.dispose()
            platformBack = null
            super.onDetachedFromWindow()
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) close(); return true }
            return super.dispatchKeyEvent(event)
        }
    }
    @androidx.annotation.RequiresApi(33)
    private class PlatformBack(view: View, close: () -> Unit) {
        private val dispatcher = view.findOnBackInvokedDispatcher()
        private val callback = OnBackInvokedCallback { close() }
        init { dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback) }
        fun dispose() { dispatcher?.unregisterOnBackInvokedCallback(callback) }
    }
}
