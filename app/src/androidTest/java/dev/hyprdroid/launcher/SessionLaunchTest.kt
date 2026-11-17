package dev.hyprdroid.launcher

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.hyprdroid.launcher.data.LauncherCatalog
import dev.hyprdroid.launcher.data.SessionLauncher
import org.junit.Assert.*
import org.junit.Test

class SessionLaunchTest {
    @Test fun publicLauncherRequestOpensSettingsAndDoesNotClaimTaskReuse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = LauncherCatalog(context).load().first { it.componentId.substringBefore('/') == "com.android.settings" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val launcher = SessionLauncher(activity)
                val rejected = launcher.launch(0, listOf("missing.hyprdroid.test/.Missing"), false)
                assertTrue(rejected.result.contains("недоступно"))
                val result = launcher.launch(0, listOf(settings.componentId), false)
                assertEquals("launch_or_return", result.action)
                assertTrue(result.result.contains("отправлен"))
                assertTrue(result.result.contains("не подтверждено"))
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
            device.pressBack()
        }
    }
}
