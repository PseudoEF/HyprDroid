package dev.hyprdroid.launcher

import android.Manifest
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.model.QuickTile
import dev.hyprdroid.launcher.quicksettings.QuickSettingsController
import org.junit.Assert.*
import org.junit.Test

/** Permission setup is confined to the isolated CI emulator; the product always uses system consent. */
class QuickSettingsStateTest {
    @Test fun publicWritesAndExternalChangesUpdateStatesAndRevocationStopsWrites() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
        val oldMode = Regex("WRITE_SETTINGS: (allow|deny|ignore|default)").find(shell("appops get ${context.packageName} WRITE_SETTINGS"))?.groupValues?.get(1) ?: "default"
        val brightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val auto = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        val rotation = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val controller = QuickSettingsController.get(context)
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue(condition())
        }
        shell("appops set ${context.packageName} WRITE_SETTINGS allow")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var acquired = false
        try {
            assertTrue(Settings.System.canWrite(context))
            scenario.onActivity { controller.acquire(); acquired = true }
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 64)
            await { controller.states.value[QuickTile.BRIGHTNESS]?.value?.let { it < 0.3f } == true }
            scenario.onActivity { controller.adjust(QuickTile.BRIGHTNESS, 0.75f); controller.tap(QuickTile.ROTATION) }
            assertEquals(191, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
            assertEquals(if (rotation == 0) 1 else 0, Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION))
            if (!audio.isVolumeFixed && audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) > 0) {
                scenario.onActivity { controller.adjust(QuickTile.VOLUME, 0f) }
                await { controller.states.value[QuickTile.VOLUME]?.active == false }
            }
            if (android.os.Build.VERSION.SDK_INT >= 31 && !controller.hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                assertNull(controller.states.value[QuickTile.BLUETOOTH]?.active)
            shell("appops set ${context.packageName} WRITE_SETTINGS ignore")
            await { controller.states.value[QuickTile.BRIGHTNESS]?.adjustable == false }
            scenario.onActivity { controller.adjust(QuickTile.BRIGHTNESS, 0.1f) }
            assertEquals(191, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
        } finally {
            shell("appops set ${context.packageName} WRITE_SETTINGS allow")
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, auto)
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, rotation)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            if (acquired) scenario.onActivity { controller.release() }
            scenario.close()
            shell("appops set ${context.packageName} WRITE_SETTINGS $oldMode")
        }
    }
}
