package dev.hyprdroid.launcher.external

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TileRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalLuaReserveTest {
    @Test fun readyReserveIsSingleUseAndRetiresOnTrimAndInvisibility() = runBlocking {
        assumeTrue(BuildConfig.V2_FIXTURE_LUA)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val script = instrumentation.context.assets.open("external-hello-1.0.0.lua").use { it.readBytes() }
        val runtime = ExperimentalExternalLua
        runtime.prewarmExperiment = true
        val hide = runtime.visibleTile(app)
        try {
            suspend fun render(event: String? = null): String {
                val result = runtime.run(app, script, TileRequest(event, "0"))
                check(result is PluginResult.Success) { "Worker returned ${result.javaClass.simpleName}" }
                return requireNotNull(result.tile).state
            }

            assertEquals("0", render())
            val first = awaitReady()
            assertEquals(1, runtime.fixtureProcessPermits())
            assertEquals("1", render("increment"))
            val second = awaitReady()
            assertNotEquals(first, second)
            assertEquals(1, runtime.fixtureProcessPermits())

            runtime.fixtureTrimMemory()
            awaitRelease()
            assertEquals(null, runtime.fixtureReadyWorker())

            assertEquals("0", render())
            awaitReady()
            hide()
            awaitRelease()
            assertEquals("0", render())
            assertEquals(2, runtime.fixtureProcessPermits())
            assertEquals(null, runtime.fixtureReadyWorker())
        } finally {
            hide()
            runtime.prewarmExperiment = false
        }
    }

    @Test fun idleExpiryDoesNotRefillWithoutDemand() = runBlocking {
        assumeTrue(BuildConfig.V2_FIXTURE_LUA)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val script = instrumentation.context.assets.open("external-hello-1.0.0.lua").use { it.readBytes() }
        val runtime = ExperimentalExternalLua
        runtime.prewarmExperiment = true
        val hide = runtime.visibleTile(app)
        try {
            check(runtime.run(app, script, TileRequest(null, "0")) is PluginResult.Success)
            awaitReady()
            awaitRelease()
            assertEquals(null, runtime.fixtureReadyWorker())
            delay(250)
            assertEquals(2, runtime.fixtureProcessPermits())
        } finally {
            hide()
            runtime.prewarmExperiment = false
        }
    }

    private suspend fun awaitReady(): Long = withTimeout(6_000) {
        var ready = ExperimentalExternalLua.fixtureReadyWorker()
        while (ready == null) {
            delay(10)
            ready = ExperimentalExternalLua.fixtureReadyWorker()
        }
        ready
    }

    private suspend fun awaitRelease() = withTimeout(6_000) {
        while (ExperimentalExternalLua.fixtureProcessPermits() != 2) delay(10)
    }
}
