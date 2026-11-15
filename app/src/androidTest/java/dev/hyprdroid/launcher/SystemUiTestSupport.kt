package dev.hyprdroid.launcher

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** Google emulator startup can ANR its background Pixel Launcher; never dismiss our app's ANR. */
fun dismissBackgroundPixelLauncherAnr() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val title = By.text("Pixel Launcher isn't responding")
    if (device.hasObject(title)) {
        val close = device.findObject(By.text("Close app"))
        checkNotNull(close) { "Pixel Launcher ANR has no close action" }
        close.click()
        check(device.wait(Until.gone(title), 5_000)) { "Pixel Launcher ANR remains visible" }
    }
}

fun captureShellScreen(name: String) {
    require(name.matches(Regex("[a-z0-9-]+")))
    dismissBackgroundPixelLauncherAnr()
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    for (command in listOf("mkdir -p /sdcard/Download/hyprdroid-shell", "screencap -p /sdcard/Download/hyprdroid-shell/$name.png")) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
}
