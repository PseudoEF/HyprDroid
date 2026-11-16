package dev.hyprdroid.launcher

import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.overlay.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlaySidebarTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun permission(mode: String) {
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 8_000
        while (!condition() && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
        assertTrue(condition())
    }
    @Test fun permissionGatesServiceAndRightZoneOpensOverAnotherAppWithBackAndStop() {
        dismissBackgroundPixelLauncherAnr()
        val repository = ShellRepository(context)
        val app = LauncherCatalog(context).load().first { it.componentId.substringBefore('/') !in setOf(context.packageName, "com.android.settings") }
        val launchItem = WorkspaceItem.AppShortcut(app.componentId)
        val alreadyPinned = runBlocking { repository.settings.first().sidebar.any { it.id == launchItem.id } }
        runBlocking { repository.setSidebarItem(launchItem, true) }
        runBlocking { repository.setOverlay(OverlaySettings()) }
        permission("deny")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { assertFalse(OverlaySession.start(it)) }
            assertFalse(OverlaySession.running.value)
            permission("allow")
            assertTrue(Settings.canDrawOverlays(context))
            runBlocking { repository.setOverlay(OverlaySettings(enabled = true)) }
            scenario.onActivity { assertTrue(OverlaySession.start(it)) }
            waitFor { OverlaySession.running.value }
            assertFalse(device.hasObject(By.desc("Открыть глобальную Sidebar")))
            val before = runBlocking { repository.settings.first().sidebar }
            context.startActivity(Intent().setClassName(context.packageName + ".test", OverlayTestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName + ".test")), 8_000))
            val edge = device.wait(Until.findObject(By.desc("Открыть глобальную Sidebar")), 8_000)
            assertNotNull("The bounded overlay zone must appear over another app", edge)
            val y = edge!!.visibleBounds.centerY()
            val distance = (90 * context.resources.displayMetrics.density).toInt()
            device.swipe(device.displayWidth - 2, y, device.displayWidth - distance, y, 25)
            assertTrue("Native right-edge swipe should open overlay on the test emulator", device.wait(Until.hasObject(By.text("Quick Launch")), 5_000))
            captureShellScreen("v062-overlay-test-host")
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("Открыть глобальную Sidebar")), 5_000))
            // Tap is an accessible fallback when an OEM prioritizes system Back over exclusion.
            device.findObject(By.desc("Открыть глобальную Sidebar")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("Закрыть Sidebar")), 5_000))
            device.findObject(By.desc("Закрыть Sidebar")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("Открыть глобальную Sidebar")), 5_000))
            assertEquals(before, runBlocking { repository.settings.first().sidebar })
            device.findObject(By.desc("Открыть глобальную Sidebar")).click()
            val tile = device.wait(Until.findObject(By.text(app.label)), 8_000)
            assertNotNull("The existing launcher catalogue must provide an icon and label", tile)
            tile!!.click()
            waitFor { device.currentPackageName == app.componentId.substringBefore('/') }
            context.startActivity(Intent().setClassName(context.packageName + ".test", OverlayTestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.desc("Открыть глобальную Sidebar")), 8_000))
            device.findObject(By.desc("Открыть глобальную Sidebar")).click()
            val stop = device.wait(Until.findObject(By.text("Отключить overlay")), 5_000)
            assertNotNull(stop)
            stop!!.click()
            waitFor { !OverlaySession.running.value }
            assertFalse(runBlocking { repository.settings.first().overlay.enabled })
            // A disabled session can be started again only from an explicitly visible launcher.
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor { device.currentPackageName == context.packageName }
            runBlocking { repository.setOverlay(OverlaySettings(enabled = true)) }
            scenario.onActivity { assertTrue(OverlaySession.start(it)) }
            waitFor { OverlaySession.running.value }
            context.startActivity(Intent().setClassName(context.packageName + ".test", OverlayTestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.desc("Открыть глобальную Sidebar")), 8_000))
            permission("deny")
            waitFor { !OverlaySession.running.value }
            assertFalse(device.hasObject(By.desc("Открыть глобальную Sidebar")))
        } finally {
            runBlocking { repository.setOverlay(OverlaySettings()) }
            if (!alreadyPinned) runBlocking { repository.removeSidebarItem(launchItem.id) }
            scenario.onActivity { OverlaySession.stop(it) }
            scenario.close()
            permission("default")
        }
    }
}
