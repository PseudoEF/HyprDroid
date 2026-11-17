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
import dev.hyprdroid.launcher.model.PanelSide
import dev.hyprdroid.launcher.model.PanelsSettings
import dev.hyprdroid.launcher.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

/** Explicitly enabled foreground service. No app inspection, boot start, sticky restart or hidden API. */
class OverlaySidebarService : Service() {
    companion object {
        internal var instance: OverlaySidebarService? = null
        private const val CHANNEL = "experimental-sidebar"
        private const val STOP = "dev.hyprdroid.launcher.STOP_OVERLAY"
        private const val REVEAL = "dev.hyprdroid.launcher.REVEAL_TOPBAR"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windows: WindowManager
    private lateinit var windowContext: Context
    private lateinit var owner: OverlayOwner
    private lateinit var repository: ShellRepository
    private var root: View? = null
    private var compose: ComposeView? = null
    private var openSide: PanelSide? = null
    private var topSurface: String? = null
    private lateinit var topbar: TopbarOverlay
    private val handles = mutableListOf<View>()
    private var settings = OverlaySettings()
    private var panels = PanelsSettings()
    private var observing: Job? = null
    private var registered = false
    private var screenOff = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOff = intent?.action == Intent.ACTION_SCREEN_OFF
            openSide = null; refreshVisibility()
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
        // Service contexts are not display-associated. Bind a display before creating the visual context.
        windowContext = if (Build.VERSION.SDK_INT >= 30) {
            val display = requireNotNull(getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
        windows = windowContext.getSystemService(WindowManager::class.java)
        topbar = TopbarOverlay(windowContext, windows, { openHome() }, { topSurface = "menu"; refreshVisibility() },
            { side -> if (panels[side].scope.apps) { openSide = side; topSurface = null; refreshVisibility() } },
            { topSurface = "quick"; refreshVisibility() }, { openHome("topbar") })
        owner = OverlayOwner()
        repository = ShellRepository(this)
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        registered = true
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
        scope.launch {
            while (isActive) {
                val safe = getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked && !screenOff
                if (!safe && (root != null || handles.isNotEmpty())) refreshVisibility()
                if (settings.enabled) topbar.update(OverlaySession.homeVisible, safe, root != null)
                delay(1000)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            disableOverlay()
            return START_NOT_STICKY
        }
        if (intent?.action == REVEAL) {
            scope.launch { runCatching { repository.setTopbar { it.copy(manuallyHidden = false) } }; topbar.reveal() }
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "HyprDroid · overlay-панели", NotificationManager.IMPORTANCE_LOW))
            val home = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val reveal = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction(REVEAL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("HyprDroid · Панели поверх приложений")
                .setContentText("TopBar и боковые панели. Восстановление или отключение — ниже.")
                .setContentIntent(home).setOngoing(true).addAction(Notification.Action.Builder(null, "Показать 20 с", reveal).build())
                .addAction(Notification.Action.Builder(null, "Отключить", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(62, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(62, notification)
            OverlaySession.running(true)
            if (observing == null) observing = scope.launch {
                try {
                    combine(repository.settings, repository.topbar) { shell, bar -> shell to bar }.distinctUntilChanged().collect { (shell, bar) ->
                        settings = shell.overlay; panels = shell.panels; topbar.settings = bar
                        if (openSide?.let { !panels[it].scope.apps } == true) openSide = null
                        if (!settings.enabled || (PanelSide.entries.none { panels[it].scope.apps } && bar.mode == dev.hyprdroid.launcher.model.TopbarMode.HOME_ONLY)) stopSelf() else refreshVisibility()
                    }
                } catch (_: java.io.IOException) { OverlaySession.failed("Не удалось прочитать overlay-настройки"); stopSelf() }
            }
        } catch (_: RuntimeException) { OverlaySession.failed("Не удалось запустить foreground-службу Sidebar"); stopSelf() }
        return START_NOT_STICKY
    }
    private fun dp(value: Int) = (value * windowContext.resources.displayMetrics.density).toInt()
    internal fun refreshVisibility() {
        if (!settings.enabled) { removeWindow(); topbar.clear(); return }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val awake = getSystemService(PowerManager::class.java).isInteractive
        if (OverlaySession.homeVisible || screenOff || locked || !awake) { openSide = null; topSurface = null; removeWindow(); topbar.update(OverlaySession.homeVisible, !screenOff && !locked && awake, false); return }
        render()
        topbar.update(false, true, root != null)
    }
    private fun render() {
        removeWindow()
        try {
            val params: WindowManager.LayoutParams
            val view: View
            val side = openSide
            if (side != null || topSurface != null) {
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
                                when (topSurface) {
                                "quick" -> ControlCenter(::closePanel, { closePanel(); openHome("quickEditor") }, { topSurface = "power"; refreshVisibility() })
                                "power" -> PowerMenu(::closePanel)
                                "menu" -> TopbarOverlayMenu(buildList {
                                    add(AppMenuAction("Quick Settings") { topSurface = "quick"; refreshVisibility() })
                                    add(AppMenuAction("Питание") { topSurface = "power"; refreshVisibility() })
                                    add(AppMenuAction("Скрыть TopBar до восстановления") { scope.launch { repository.setTopbar { it.copy(manuallyHidden = true) }; topSurface = null; refreshVisibility() } })
                                    topbar.foregroundPackage?.let { pkg -> add(AppMenuAction("Всегда скрывать в $pkg") { scope.launch { repository.setTopbar { it.copy(appRules = it.appRules + (pkg to dev.hyprdroid.launcher.model.AppBarRule.HIDE)) }; topSurface = null; refreshVisibility() } }) }
                                    add(AppMenuAction("Настройки TopBar") { closePanel(); openHome("topbar") })
                                    add(AppMenuAction("Открыть HyprDroid") { closePanel(); openHome() })
                                    add(AppMenuAction("Отключить overlay", onClick = ::disableOverlay))
                                }, ::closePanel)
                                else -> {
                                CompositionLocalProvider(LocalGlobalWidgetSurface provides true) {
                                ModularSidebar(state, ::closePanel, { closePanel(); model.launchSidebar(it) }, model::setSidebarItem,
                                    model::removeSidebarItem, model::reorderSidebar, model::placeSidebarBefore,
                                    onStopOverlay = ::disableOverlay, side = requireNotNull(side))
                                }
                                }
                                }
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
                PanelSide.entries.filter { panels[it].scope.apps }.forEach { edgeSide -> addHandle(edgeSide) }
                return
            }
            params.setTitle("HyprDroid ${side?.name ?: topSurface} panel")
            root = view
            windows.addView(view, params)
            view.alpha = 0f; view.animate().alpha(1f).setDuration(180).start(); view.requestFocus()
        } catch (_: RuntimeException) {
            removeWindow(); OverlaySession.failed("Android не разрешил показать overlay-панель"); stopSelf()
        }
    }
    private fun addHandle(side: PanelSide) {
                val value = panels[side]
                val view = OverlayEdgeView(windowContext, side, value) { openSide = side; refreshVisibility() }
                val screen = windowContext.resources.displayMetrics.heightPixels
                val height = dp(value.heightDp).coerceAtMost(screen / 2)
                val params = WindowManager.LayoutParams(dp(value.touchWidthDp), height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
                params.gravity = Gravity.TOP or if (side == PanelSide.LEFT) Gravity.LEFT else Gravity.RIGHT
                params.x = dp(value.insetDp)
                params.y = ((screen - height) * value.positionPercent / 100).coerceAtLeast(dp(32))
                params.setTitle("HyprDroid ${side.name} activation")
                handles.add(view)
                windows.addView(view, params)
    }
    private fun closePanel() {
        val current = root ?: return
        current.animate().alpha(0f).setDuration(150).withEndAction {
            if (root === current) { openSide = null; topSurface = null; refreshVisibility() }
        }.start()
    }
    private fun disableOverlay() {
        settings = settings.copy(enabled = false)
        topbar.clear()
        removeWindow()
        scope.launch {
            try { repository.setOverlay(repository.settings.first().overlay.copy(enabled = false)) }
            catch (_: java.io.IOException) { OverlaySession.failed("Панель остановлена; не удалось сохранить отключение") }
            finally { stopSelf() }
        }
    }
    private fun removeWindow() {
        handles.forEach { try { windows.removeViewImmediate(it) } catch (_: IllegalArgumentException) { } }
        handles.clear()
        root?.let { view -> view.animate().cancel(); try { windows.removeViewImmediate(view) } catch (_: IllegalArgumentException) { } }
        compose?.disposeComposition(); compose = null; root = null
        owner.visible(false)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); openSide = null; refreshVisibility() }
    override fun onDestroy() {
        removeWindow()
        topbar.destroy()
        if (registered) unregisterReceiver(receiver)
        appOps.stopWatchingMode(permissionListener)
        scope.cancel(); owner.destroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        OverlaySession.running(false); instance = null
        super.onDestroy()
    }

    private fun openHome(route: String? = null) {
        runCatching { startActivity(Intent(this, MainActivity::class.java).putExtra("hyprdroid.route", route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
