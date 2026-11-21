package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.modules.CommandProcessor
import dev.hyprdroid.launcher.modules.CommandResult
import dev.hyprdroid.launcher.modules.DroidFetchFormatter
import dev.hyprdroid.launcher.modules.DroidFetchInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidFetchTest {
    private val info = DroidFetchInfo(
        os = "Android 17",
        device = "Pixel 9",
        cpu = "arm64-v8a",
        memory = "5.0 / 12.0 GiB",
        battery = "80%",
        uptime = "2h 30m",
    )

    @Test
    fun fullOutputContainsAsciiLogoAndDeviceInfo() {
        val output = DroidFetchFormatter.format(info)

        assertTrue(output.contains("H  D"))
        assertTrue(output.contains("Android 17"))
        assertTrue(output.contains("Pixel 9"))
        assertTrue(output.contains("HyprDroid"))
    }

    @Test
    fun compactOutputHasNoLogo() {
        val output = DroidFetchFormatter.format(info, compact = true)

        assertTrue(output.startsWith("user@hyprdroid"))
        assertTrue(output.contains("Battery: 80%"))
        assertFalse(output.contains("|  H  D  |"))
    }

    @Test
    fun commandReturnsProvidedAsciiOutput() {
        val expected = DroidFetchFormatter.format(info)

        val processor = CommandProcessor(
            droidfetch = { expected },
        )

        assertEquals(
            CommandResult.Output(expected),
            processor.execute("droidfetch"),
        )
        assertEquals(
            CommandResult.Output(expected),
            processor.execute("DROIDFETCH"),
        )
    }

    @Test
    fun helpIncludesDroidFetch() {
        val result = CommandProcessor().execute("help")
        assertTrue(result is CommandResult.Output)
        assertTrue((result as CommandResult.Output).text.contains("droidfetch"))
    }
}
