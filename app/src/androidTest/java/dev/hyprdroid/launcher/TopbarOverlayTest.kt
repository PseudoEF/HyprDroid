package dev.hyprdroid.launcher

import android.content.Intent
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.overlay.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TopbarOverlayTest {
    @Test fun boundedSharedBarRespectsAppRulesAndCanBeRestoredWithoutTakingInputFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        fun waitFor(condition: () -> Boolean) { val end = SystemClock.uptimeMillis() + 12_000; while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(100); assertTrue(condition()) }
        val repo = ShellRepository(context)
        val original = runBlocking { repo.settings.first() }
        val oldBar = runBlocking { repo.topbar.first() }
        val target = context.packageName + ".test"
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        // This isolated fixture leaves notification permission until instrumentation ends;
        // the CI script revokes it before the separate restart phase (runtime revocation can kill a process).
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        runBlocking {
            repo.setPanel(PanelSide.LEFT, original.panels.left.copy(scope = PanelScope.HOME))
            repo.setPanel(PanelSide.RIGHT, original.panels.right.copy(scope = PanelScope.HOME))
            repo.setTopbar { TopbarSettings(TopbarMode.ALWAYS, appRules = mapOf(target to AppBarRule.SHOW)) }
            repo.setOverlay(original.overlay.copy(enabled = true))
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { assertTrue(OverlaySession.start(it)) }
            context.startActivity(Intent().setClassName(target, OverlayTestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("Home")), 12_000))
            assertFalse("Workspace selectors must not appear over another app", device.hasObject(By.desc("Рабочее пространство 1")))
            assertFalse("Home-only side panels must not expose dead global actions", device.hasObject(By.desc("Открыть Sidebar")))
            device.wait(Until.findObject(By.text("Outside taps: 0")), 5_000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.text("Outside taps: 1")), 5_000))
            val input = device.findObject(By.desc("Keyboard fixture")); input.click(); input.text = "focus retained"
            SystemClock.sleep(1500)
            assertTrue(device.findObject(By.desc("Keyboard fixture")).isFocused)
            device.pressBack()
            device.setOrientationLeft()
            assertTrue(device.wait(Until.hasObject(By.text("Home")), 8_000))
            assertTrue(device.findObject(By.text("Home")).visibleBounds.right <= device.displayWidth)
            device.setOrientationNatural()
            runBlocking { repo.setTopbar { it.copy(widthPercent = 50) } }
            assertTrue(device.wait(Until.hasObject(By.desc("Меню оболочки")), 8_000))
            assertTrue(device.findObject(By.desc("Меню оболочки")).visibleBounds.right <= device.displayWidth)
            runBlocking { repo.setTopbar { it.copy(widthPercent = 96) } }
            captureShellScreen("v09-global-topbar")
            runBlocking { repo.setTopbar { it.copy(appRules = emptyMap()) } }
            assertTrue("Auto hides the fixture's declared Game category", device.wait(Until.gone(By.text("Home")), 8_000))
            runBlocking { repo.setTopbar { it.copy(appRules = mapOf(target to AppBarRule.SHOW)) } }
            assertTrue(device.wait(Until.hasObject(By.text("Home")), 8_000))
            runBlocking { repo.setTopbar { it.copy(appRules = mapOf(target to AppBarRule.HIDE)) } }
            assertTrue(device.wait(Until.gone(By.text("Home")), 8_000))
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 62 }.notification
            notification.actions.first { it.title.toString() == "Показать 20 с" }.actionIntent.send()
            assertTrue(device.wait(Until.hasObject(By.text("Home")), 8_000))
            // New app category is a conservative hint, not a fullscreen detector.
            runBlocking { repo.setTopbar { it.copy(mode = TopbarMode.AUTO_HIDE, contextAware = false, appRules = emptyMap()) } }
            // Clear the explicit temporary override by restarting only this opted-in service.
            context.stopService(Intent(context, OverlaySidebarService::class.java))
            waitFor { !OverlaySession.running.value }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor { device.currentPackageName == context.packageName }
            scenario.onActivity { OverlaySession.start(it) }
            context.startActivity(Intent().setClassName(target, OverlayTestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.desc("Показать глобальный TopBar")), 12_000))
            device.findObject(By.desc("Показать глобальный TopBar")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Home")), 8_000))
            device.sleep(); assertTrue(device.wait(Until.gone(By.text("Home")), 5_000))
            device.wakeUp(); shell("wm dismiss-keyguard")
        } finally {
            device.setOrientationNatural(); device.unfreezeRotation()
            context.stopService(Intent(context, OverlaySidebarService::class.java))
            runBlocking {
                repo.setTopbar { oldBar }; repo.setOverlay(original.overlay)
                repo.setPanel(PanelSide.LEFT, original.panels.left); repo.setPanel(PanelSide.RIGHT, original.panels.right)
            }
            scenario.close()
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            shell("appops set ${context.packageName} GET_USAGE_STATS default")
        }
    }
}
