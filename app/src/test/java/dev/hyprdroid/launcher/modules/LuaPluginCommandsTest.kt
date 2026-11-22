package dev.hyprdroid.launcher.modules

import dev.hyprdroid.launcher.plugins.BuiltInLuaPlugin
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.PluginWindow
import dev.hyprdroid.launcher.workspaceengine.WindowId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaPluginCommandsTest {
    @Test
    fun commandRejectsPackageIdAndPathsAndPublishesOnlyBuiltInResult() = runBlocking {
        val calls = mutableListOf<BuiltInLuaPlugin>()
        val windows = mutableListOf<PluginWindow?>()
        val window = PluginWindow("DroidFetch", "Android")
        val commands = LuaPluginCommands(
            runPlugin = {
                calls += it
                PluginResult.Success(listOf("DroidFetch output"), window)
            },
            showWindow = { windows += it },
        )
        val processor = CommandProcessor(luaPluginCommands = commands)

        assertTrue(processor.execute("lua run hyprdroid.droidfetch-sample") is CommandResult.Output)
        assertTrue(processor.execute("lua run ../droidfetch.lua") is CommandResult.Output)
        assertTrue(calls.isEmpty())

        val panel = CommandPanelState(WindowId("lua-terminal"))
        panel.updateInput("lua run droidfetch")
        val events = checkNotNull(panel.submit(processor))
        assertTrue(calls.isEmpty())
        panel.play(events)
        assertEquals(listOf(BuiltInLuaPlugin.DROIDFETCH), calls)
        assertEquals(listOf(window), windows)
        assertEquals("DroidFetch output", panel.lines.last().text)
    }
}
