package dev.hyprdroid.launcher.power

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/** User-authorized power-dialog capability only. No event subscription, nodes, gestures or windows. */
class PowerDialogService : AccessibilityService() {
    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply { eventTypes = 0; flags = 0 }
        current = WeakReference(this); connected.value = true
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { clear(); return super.onUnbind(intent) }
    override fun onDestroy() { clear(); super.onDestroy() }
    private fun clear() { if (current?.get() === this) { current = null; connected.value = false } }
    companion object {
        private var current: WeakReference<PowerDialogService>? = null
        private val connected = MutableStateFlow(false)
        val available = connected.asStateFlow()
        fun showPowerDialog(): Boolean = runCatching { current?.get()?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) == true }.getOrDefault(false)
        fun disable() { current?.get()?.disableSelf() }
    }
}
