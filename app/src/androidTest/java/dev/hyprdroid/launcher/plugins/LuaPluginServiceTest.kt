package dev.hyprdroid.launcher.plugins

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LuaPluginServiceTest {
    @Test
    fun packagedScriptRunsThroughIsolatedBinderService() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AndroidLuaPlugins.run(context, BuiltInLuaPlugin.DROIDFETCH)
        // The host also checks that the Binder reply comes from a UID other than its own.
        assertTrue("Expected output from the isolated service, got $result", result is PluginResult.Success)
        val success = result as PluginResult.Success
        assertTrue(success.output.single().contains("DroidFetch · Lua"))
        assertEquals("DroidFetch · Lua", success.window?.title)
    }
}
