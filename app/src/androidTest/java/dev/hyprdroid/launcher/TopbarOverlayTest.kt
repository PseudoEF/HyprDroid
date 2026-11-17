package dev.hyprdroid.launcher

import android.content.Intent
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

/** Old Always/Auto settings must never bring back a dock over app headers. */
class TopbarOverlayTest {
    @Test fun retiredGlobalBarIsIgnoredWhileHomeAndSidePanelsRemainAvailable() {
        dismissBackgroundPixelLauncherAnr()
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
        context.stopService(Intent(context, OverlaySidebarService::class.java))
        runBlocking {
            repo.setPanel(PanelSide.LEFT, original.panels.left.copy(scope = PanelScope.HOME))
            repo.setPanel(PanelSide.RIGHT, original.panels.right.copy(scope = PanelScope.HOME))
            repo.setOverlay(original.overlay.copy(enabled = true))
            repo.setTopbar { TopbarSettings(TopbarMode.ALWAYS, appRules = mapOf(target to AppBarRule.SHOW)) }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue(device.wait(Until.hasObject(By.desc("Рабочее пространство 1")), 12_000))
            SystemClock.sleep(1000)
            assertFalse("Legacy TopBar cannot start a foreground service", OverlaySession.running.value)
            fun openExternal() {
                context.startActivity(Intent().setClassName(target, OverlayTestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                assertTrue(device.wait(Until.hasObject(By.text("Outside taps: 0")), 8_000))
                SystemClock.sleep(1200)
                assertFalse(device.hasObject(By.text("Home")))
                assertFalse(device.hasObject(By.desc("Меню оболочки")))
                assertFalse(device.hasObject(By.desc("Показать глобальный TopBar")))
            }
            openExternal()
            device.findObject(By.text("Outside taps: 0")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Outside taps: 1")), 5_000))
            val input = device.findObject(By.desc("Keyboard fixture")); input.click(); input.text = "native app focus"
            assertTrue(device.findObject(By.desc("Keyboard fixture")).isFocused)
            device.pressBack()
            // Existing side-panel scope and master switch remain the only service inputs.
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor { device.currentPackageName == context.packageName }
            runBlocking {
                repo.setTopbar { it.copy(mode = TopbarMode.AUTO_HIDE) }
                repo.setPanel(PanelSide.RIGHT, original.panels.right.copy(scope = PanelScope.EVERYWHERE))
            }
            scenario.onActivity { assertTrue(OverlaySession.start(it)) }
            waitFor { OverlaySession.running.value }
            context.startActivity(Intent().setClassName(target, OverlayTestHostActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("Outside taps: 1")), 8_000))
            SystemClock.sleep(1200)
            assertFalse(device.hasObject(By.text("Home")))
            assertEquals(TopbarMode.AUTO_HIDE, runBlocking { repo.topbar.first().mode })
            captureShellScreen("v09-home-only-topbar-external")
        } finally {
            context.stopService(Intent(context, OverlaySidebarService::class.java))
            runBlocking {
                repo.setTopbar { oldBar }; repo.setOverlay(original.overlay)
                repo.setPanel(PanelSide.LEFT, original.panels.left); repo.setPanel(PanelSide.RIGHT, original.panels.right)
            }
            scenario.close()
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }
}
