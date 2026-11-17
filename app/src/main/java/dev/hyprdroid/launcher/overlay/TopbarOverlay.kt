package dev.hyprdroid.launcher.overlay

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.ui.*

/** Bounded, non-focusable window; no transparent fullscreen input surface for the dock. */
internal class TopbarOverlay(private val context: Context, private val windows: WindowManager,
    private val onHome: () -> Unit, private val onMenu: () -> Unit, private val onSide: (PanelSide) -> Unit,
    private val onQuick: () -> Unit, private val onSettings: () -> Unit) {
    var settings = TopbarSettings()
    private val hints = ForegroundHints(context)
    private val owner = OverlayOwner()
    private var view: View? = null
    private var content: ComposeView? = null
    private var key: Any? = null
    private var lastTouch = SystemClock.elapsedRealtime()
    private var revealUntil = 0L
    private var ime = false
    private var safe = false
    private var home = true
    private var suspended = false
    private var destroyed = false
    private var previousPackage: String? = null
    var foregroundPackage: String? = null; private set
    private var category = ForegroundCategory.UNKNOWN
    fun reveal() { lastTouch = SystemClock.elapsedRealtime(); revealUntil = lastTouch + 20_000; key = null; render() }
    fun update(home: Boolean, safe: Boolean, suspended: Boolean) {
        if (this.home != home) lastTouch = SystemClock.elapsedRealtime()
        this.home = home; this.safe = safe; this.suspended = suspended
        if (!safe || home || settings.mode == TopbarMode.HOME_ONLY || (!settings.contextAware && settings.appRules.isEmpty())) { hints.reset(); foregroundPackage = null; category = ForegroundCategory.UNKNOWN }
        else hints.sample().let { (pkg, type) ->
            foregroundPackage = pkg; category = type
            if (pkg != previousPackage) { previousPackage = pkg; lastTouch = SystemClock.elapsedRealtime() }
        }
        render()
    }
    private fun render() {
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        val protected = foregroundPackage in setOf(context.packageName, "com.android.settings", "com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller")
        val state = settings.visibility(home, safe && !suspended && !protected, false, foregroundPackage, category, now - lastTouch >= 6000, now < revealUntil)
        val metrics = context.resources.displayMetrics
        var width = metrics.widthPixels
        var left = 0; var right = 0; var top = dp(32)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windows.currentWindowMetrics
            width = bounds.bounds.width()
            val insets = bounds.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            left = insets.left; right = insets.right; top = insets.top
        }
        val available = (width - left - right - dp(16)).coerceAtLeast(dp(120))
        val barWidth = (available * settings.widthPercent / 100).coerceAtMost(dp(760))
        val next = listOf(state, barWidth, top, left, right, settings.heightDp, settings.opacityPercent)
        if (next == key) { view?.visibility = if (ime) View.INVISIBLE else View.VISIBLE; owner.visible(state == BarVisibility.BAR && !ime); return }
        remove(); key = next
        if (state == BarVisibility.NONE) { ime = false; return }
        val holder = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean { if (event.actionMasked == MotionEvent.ACTION_DOWN) lastTouch = SystemClock.elapsedRealtime(); return super.dispatchTouchEvent(event) }
        }
        val child: View
        if (state == BarVisibility.REVEAL) {
            child = TextView(context).apply {
                text = "⌄"; contentDescription = "Показать глобальный TopBar"; gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.rgb(205, 214, 244)); setBackgroundColor(android.graphics.Color.rgb(30, 30, 46))
                setOnClickListener { reveal() }
            }
            holder.addView(child, FrameLayout.LayoutParams(-1, dp(20)))
        } else {
            holder.setViewTreeLifecycleOwner(owner); holder.setViewTreeViewModelStoreOwner(owner); holder.setViewTreeSavedStateRegistryOwner(owner)
            val compose = ComposeView(context).apply { setContent { ShellTheme {
                Box(Modifier.heightIn(min = settings.heightDp.dp)) {
                    Waybar(0, {}, emptyList(), Modifier.fillMaxWidth(), home = false, onHome = onHome,
                        onSidebar = { onSide(PanelSide.RIGHT) }, onLeftPanel = { onSide(PanelSide.LEFT) }, onSettings = onSettings,
                        onMenuRequest = onMenu)
                }
            } } }
            content = compose; child = compose
            holder.addView(child, FrameLayout.LayoutParams(-1, -2)); owner.visible(true)
        }
        ViewCompat.setOnApplyWindowInsetsListener(holder) { target, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (ime != visible) { ime = visible; target.post { render() } }
            insets
        }
        val params = WindowManager.LayoutParams(if (state == BarVisibility.REVEAL) dp(72) else barWidth, -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = left + ((width - left - right - params.width) / 2).coerceAtLeast(0)
        params.y = top + dp(6); params.alpha = settings.opacityPercent / 100f
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (Build.VERSION.SDK_INT >= 30) { params.setFitInsetsTypes(0); params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        params.setTitle("HyprDroid TopBar")
        try { view = holder; holder.visibility = if (ime) View.INVISIBLE else View.VISIBLE; windows.addView(holder, params); ViewCompat.requestApplyInsets(holder) }
        catch (_: RuntimeException) { remove(); OverlaySession.failed("TopBar: Android отклонил окно; откройте HyprDroid из уведомления") }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun remove() {
        content?.disposeComposition(); content = null
        view?.let { runCatching { windows.removeViewImmediate(it) } }; view = null
        owner.visible(false)
    }
    fun clear() { key = null; remove(); ime = false }
    fun destroy() { destroyed = true; clear(); owner.destroy() }
}
