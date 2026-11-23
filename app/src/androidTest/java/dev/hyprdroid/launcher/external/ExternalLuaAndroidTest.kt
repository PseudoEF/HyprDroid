package dev.hyprdroid.launcher.external

import android.content.ComponentName
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TileRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalLuaAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val sample get() = instrumentation.context.assets.open("external-hello-1.0.0.lua").use { it.readBytes() }

    @Before
    fun optInOnly() {
        assumeTrue(BuildConfig.EXTERNAL_LUA)
    }

    @Test
    fun experimentalAppUsesPrivateWorkerAndItsOwnPackageStorage() {
        assertEquals("dev.hyprdroid.launcher.experimental", app.packageName)
        assertEquals("HyprDroid Experimental", app.applicationInfo.loadLabel(app.packageManager).toString())
        assertTrue(app.noBackupFilesDir.parentFile?.name == app.packageName)
        assertNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
        val component = ComponentName(app.packageName, "dev.hyprdroid.launcher.external.ExternalLuaService")
        val service = app.packageManager.getServiceInfo(component, 0)
        assertFalse(service.exported)
        assertTrue(service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
    }

    @Test
    fun arenaAndInstructionLimitsFailWithoutCrashingCore() = runBlocking {
        val memory = evaluate("local s = 'x'; while true do s = s .. s end")
        assertTrue("Unexpected memory result: $memory",
            memory is PluginResult.Failure && memory.message.contains("память", ignoreCase = true))
        val loop = evaluate("while true do end")
        assertTrue("Unexpected loop result: $loop",
            loop is PluginResult.Failure && loop.message.contains("квоту"))
        assertTrue(ExperimentalExternalLua.run(app, sample, TileRequest()) is PluginResult.Success)
    }

    @Test
    fun kernelTimeoutKillsWorkerAndNextBindingRecovers() = runBlocking {
        val dead = evaluate("-- hypr-test-force-kernel-timeout")
        assertTrue("Unexpected watchdog result: $dead", dead is PluginResult.Failure &&
            (dead.message.contains("worker завершился") || dead.message.contains("Связь")))
        assertTrue(ExperimentalExternalLua.run(app, sample, TileRequest()) is PluginResult.Success)
    }

    @Test
    fun twoSimultaneousRequestsCarryIndependentStateAndRejectUnknownEvent() = runBlocking {
        val bytes = sample
        val a = async { ExperimentalExternalLua.run(app, bytes, TileRequest("increment", "8")) }
        val b = async { ExperimentalExternalLua.run(app, bytes, TileRequest("increment", "0")) }
        assertEquals("9", (a.await() as PluginResult.Success).tile?.state)
        assertEquals("1", (b.await() as PluginResult.Success).tile?.state)
        assertTrue(ExperimentalExternalLua.run(app, bytes, TileRequest("foreign", "1")) is PluginResult.Failure)
    }

    @Test
    fun rejectsInvalidSourceAndPrivilegedLibraries() = runBlocking {
        for (script in listOf("local =", "return io.open('blocked')", "return os.execute('blocked')",
            "return require('blocked')", "return luajava.bindClass('blocked')"
        )) {
            assertTrue(evaluate(script) is PluginResult.Failure)
        }
        val reset = ExperimentalExternalLua.run(app, sample, TileRequest("reset", "9"))
        assertEquals("0", (reset as PluginResult.Success).tile?.state)
    }

    private suspend fun evaluate(source: String) =
        ExperimentalExternalLua.run(app, source.encodeToByteArray(), TileRequest())
}
