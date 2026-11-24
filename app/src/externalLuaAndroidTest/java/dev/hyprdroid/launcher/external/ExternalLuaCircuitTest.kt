package dev.hyprdroid.launcher.external

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TileRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalLuaCircuitTest {
    @Test fun fourAbruptWorkerDeathsOpenTheCircuitAndRecoveryIsPossible(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val fixture = "-- hypr-test-force-kernel-timeout".encodeToByteArray()
        val sample = instrumentation.context.assets.open("external-hello-1.0.0.lua").use { it.readBytes() }
        repeat(4) {
            val result = ExperimentalExternalLua.run(app, fixture, TileRequest())
            assertTrue("Expected abrupt Binder death: $result", result is PluginResult.Failure &&
                (result.message.contains("worker завершился") || result.message.contains("Связь")))
        }
        assertEquals(PluginResult.Failure("Внешний Lua недоступен"),
            ExperimentalExternalLua.run(app, sample, TileRequest()))
        SystemClock.sleep(10_100)
        assertTrue(ExperimentalExternalLua.run(app, sample, TileRequest()) is PluginResult.Success)
    }
}
