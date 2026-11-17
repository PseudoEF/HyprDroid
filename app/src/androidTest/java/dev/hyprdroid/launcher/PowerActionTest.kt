package dev.hyprdroid.launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.power.PowerDialogService
import org.junit.Assert.*
import org.junit.Test

/** Enables only the isolated emulator fixture; never presses shutdown/restart or changes power state. */
class PowerActionTest {
    @Test fun explicitGlobalPowerDialogWorksWithZeroWindowCapabilitiesAndNoEvents() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = automation.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim() }
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        fun await(condition: () -> Boolean) { val end = SystemClock.uptimeMillis() + 15_000; while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(100); assertTrue(condition()) }
        val previous = shell("settings get secure enabled_accessibility_services")
        val oldEnabled = shell("settings get secure accessibility_enabled")
        val component = context.packageName + "/.power.PowerDialogService"
        val services = if (previous == "null" || previous.isEmpty()) component else "$previous:$component"
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            shell("settings put secure enabled_accessibility_services ${quote(services)}")
            shell("settings put secure accessibility_enabled 1")
            await { PowerDialogService.available.value }
            val info = context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .first { it.resolveInfo.serviceInfo.name.endsWith("PowerDialogService") }
            assertEquals(0, info.eventTypes)
            assertEquals(0, info.capabilities)
            assertEquals(0, info.flags)
            scenario.onActivity { assertTrue(PowerDialogService.showPowerDialog()) }
            fun find(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
                if (node == null || depth > 20) return false
                if (node.text?.toString() in setOf("Power off", "Restart")) return true
                return (0 until node.childCount).any { find(node.getChild(it), depth + 1) }
            }
            await { automation.windows.any { window -> window.root?.let { it.packageName?.toString() == "com.android.systemui" && find(it) } == true } }
            automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
            automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
        } finally {
            if (previous == "null") shell("settings delete secure enabled_accessibility_services") else shell("settings put secure enabled_accessibility_services ${quote(previous)}")
            if (oldEnabled == "null") shell("settings delete secure accessibility_enabled") else shell("settings put secure accessibility_enabled ${quote(oldEnabled)}")
            await { !PowerDialogService.available.value }
            scenario.close()
        }
    }
}
