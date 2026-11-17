package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Compatibility only: retired settings survive writes to the active shell configuration. */
class TopbarPolicyTest {
    @Test fun legacyConfigurationRoundTripsAndUnknownDocumentsAreNotReset() {
        val value = TopbarSettings(TopbarMode.AUTO_HIDE, 72, 64, 70, false, true, mapOf("example.app" to AppBarRule.HIDE))
        assertEquals(value, TopbarCodec.read(TopbarCodec.write(value)))
        assertEquals(TopbarMode.HOME_ONLY, TopbarCodec.read(null).mode)
        listOf("broken", "{}", "{\"version\":9}").forEach {
            try { TopbarCodec.read(it); fail("Do not overwrite unknown data") } catch (_: IOException) { }
        }
    }
}
