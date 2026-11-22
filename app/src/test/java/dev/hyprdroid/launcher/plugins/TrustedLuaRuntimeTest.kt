package dev.hyprdroid.launcher.plugins

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedLuaRuntimeTest {
    private val runtime = TrustedLuaRuntime(nanoTime = { 0L })
    private val inputs = PluginInputs(PluginDevice("Google", "Pixel", "15", 35), PluginBattery(73, true))
    private val allPermissions = PluginPermission.entries.toSet()

    @Test
    fun bundledDroidFetchDisplaysPermissionBoundDeviceAndBatteryData() {
        val plugin = BuiltInLuaPlugin.DROIDFETCH
        val path = "src/main/assets/${plugin.asset}"
        val asset = sequenceOf(File(path), File("app", path)).first { it.isFile }
        val text = "Google Pixel\nAndroid 15 (API 35)\nБатарея: 73% (зарядка)"

        assertEquals(
            PluginResult.Success(listOf("DroidFetch · Lua\n$text"), PluginWindow("DroidFetch · Lua", text)),
            runtime.run(asset.readBytes(), plugin.permissions, inputs),
        )
    }

    @Test
    fun unknownIdsCannotSelectAssetPaths() {
        assertEquals(BuiltInLuaPlugin.DROIDFETCH, BuiltInLuaPlugin.find("droidfetch"))
        for (id in listOf("../droidfetch", "trusted-lua/droidfetch.lua", "hyprdroid.droidfetch-sample")) {
            assertEquals(null, BuiltInLuaPlugin.find(id))
        }
    }

    @Test
    fun standardLibrariesAndDynamicCodeLoadersAreAbsent() {
        val forbidden = listOf(
            "os", "io", "debug", "package", "require", "luajava", "load", "loadfile",
            "dofile", "coroutine", "getmetatable", "setmetatable", "pcall", "xpcall",
        )
        val check = forbidden.joinToString("\n") { name ->
            """if $name ~= nil then hypr.terminal.print("$name") end"""
        }
        assertEquals(PluginResult.Success(emptyList(), null), run(check))
    }

    @Test
    fun eachApiGroupRequiresItsOwnPermission() {
        val calls = mapOf(
            PluginPermission.TERMINAL_PRINT to """hypr.terminal.print("denied")""",
            PluginPermission.SYSTEM_DEVICE to "hypr.system.getDeviceInfo()",
            PluginPermission.SYSTEM_BATTERY to "hypr.system.getBattery()",
            PluginPermission.UI_WINDOW to """hypr.ui.createWindow("denied")""",
        )
        for ((permission, call) in calls) {
            assertFailure("Доступ запрещён: ${permission.apiName}", run(call, allPermissions - permission))
        }
    }

    @Test
    fun wrongTypesArityAndStaleWindowHandlesAreRejected() {
        assertFailure("ожидает строку", run("hypr.terminal.print(123)"))
        assertFailure("число аргументов", run("""hypr.terminal.print("a", "b")"""))
        assertFailure("дескриптор", run("""
            local window = hypr.ui.createWindow("Window")
            hypr.ui.showText(tostring, "invalid")
        """))
        assertFailure("закрыто", run("""
            local window = hypr.ui.createWindow("Window")
            hypr.ui.closeWindow(window)
            hypr.ui.showText(window, "closed")
        """))
        assertFailure("дескриптор", run("""
            local first = hypr.ui.createWindow("First")
            hypr.ui.closeWindow(first)
            local second = hypr.ui.createWindow("Second")
            hypr.ui.showText(first, "stale")
        """))
        assertFailure("одно окно", run("""
            hypr.ui.createWindow("First")
            hypr.ui.createWindow("Second")
        """))
        assertEquals(PluginResult.Success(emptyList(), null), run("""
            local window = hypr.ui.createWindow("Window")
            hypr.ui.showText(window, "Visible")
            hypr.ui.closeWindow(window)
        """))
    }

    @Test
    fun syntaxAndRuntimeErrorsReturnOnlyABoundedFailure() {
        assertFailure("trusted-plugin.lua", run("local ="))
        assertFailure("trusted-plugin.lua", run("""
            hypr.terminal.print("This effect is discarded")
            hypr.ui.createWindow("This window is discarded")
            missing()
        """))
    }

    @Test
    fun instructionTimeAndCallDepthBudgetsStopNonTerminatingScripts() {
        assertFailure("лимит инструкций", run("while true do end"))
        var readings = 0
        val timedRuntime = TrustedLuaRuntime(nanoTime = { if (readings++ == 0) 0L else PluginLimits.EXECUTION_NS })
        assertFailure("лимит времени", timedRuntime.run(
            "while true do end".encodeToByteArray(), allPermissions, inputs,
        ))
        assertFailure("глубина вызовов", run("""
            local function recurse()
                return 1 + recurse()
            end
            recurse()
        """))
    }

    @Test
    fun sourceSizeEncodingAndBytecodeAreValidatedBeforeExecution() {
        assertFailure("размер", runtime.run(byteArrayOf(), allPermissions, inputs))
        assertFailure("размер", runtime.run(ByteArray(PluginLimits.SOURCE_BYTES + 1), allPermissions, inputs))
        assertFailure("текстовый", runtime.run(byteArrayOf(0x1b, 0x4c, 0x75, 0x61), allPermissions, inputs))
        assertFailure("текстовый", run("-- comment\u0000print"))
        assertFailure("UTF-8", runtime.run(byteArrayOf(0xc3.toByte(), 0x28), allPermissions, inputs))
    }

    @Test
    fun apiCallOutputAndWindowTextBudgetsAreEnforced() {
        assertFailure("лимит вызовов", run("""
            for i = 1, ${PluginLimits.API_CALLS + 1} do hypr.system.getBattery() end
        """))
        assertFailure("лимит вывода", run("""
            for i = 1, ${PluginLimits.OUTPUT_LINES + 1} do hypr.terminal.print("x") end
        """))
        assertFailure("лимит текста", run("""hypr.terminal.print("${"x".repeat(PluginLimits.OUTPUT_TEXT + 1)}")"""))
        val chunk = "x".repeat(PluginLimits.OUTPUT_TEXT)
        assertFailure("лимит вывода", run("""
            for i = 1, ${PluginLimits.OUTPUT_TOTAL / PluginLimits.OUTPUT_TEXT + 1} do
                hypr.terminal.print("$chunk")
            end
        """))
        assertFailure("лимит текста", run("""hypr.ui.createWindow("${"x".repeat(PluginLimits.WINDOW_TITLE + 1)}")"""))
        assertFailure("лимит текста", run("""
            local window = hypr.ui.createWindow("Window")
            hypr.ui.showText(window, "${"x".repeat(PluginLimits.WINDOW_TEXT + 1)}")
        """))
    }

    private fun run(source: String, permissions: Set<PluginPermission> = allPermissions) =
        runtime.run(source.trimIndent().encodeToByteArray(), permissions, inputs)

    private fun assertFailure(expected: String, result: PluginResult) {
        assertTrue("Expected failure containing '$expected', but got $result", result is PluginResult.Failure)
        val message = (result as PluginResult.Failure).message
        assertTrue("Expected '$expected' in '$message'", message.contains(expected))
        assertTrue(message.length <= PluginLimits.ERROR_TEXT)
    }
}
