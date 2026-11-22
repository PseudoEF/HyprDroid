package dev.hyprdroid.launcher.modules

import dev.hyprdroid.launcher.plugins.BuiltInLuaPlugin
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.PluginWindow

internal class LuaPluginCommands(
    private val runPlugin: suspend (BuiltInLuaPlugin) -> PluginResult,
    private val showWindow: (PluginWindow?) -> Unit,
) {
    fun execute(args: List<String>): CommandResult {
        if (args.isEmpty() || args == listOf("help")) {
            return CommandResult.Output("lua run droidfetch — запустить встроенный Lua-модуль")
        }
        if (args.size != 2 || args[0] != "run") {
            return CommandResult.Output("Использование: lua help")
        }
        val plugin = BuiltInLuaPlugin.find(args[1])
            ?: return CommandResult.Output("Модуль отсутствует в списке встроенных Lua-модулей.")
        return CommandResult.Stream(
            listOf(
                CommandEvent.Line("Запуск встроенного Lua-модуля…"),
                CommandEvent.Operation {
                    when (val result = runPlugin(plugin)) {
                        is PluginResult.Failure -> listOf("Ошибка Lua: ${result.message}")
                        is PluginResult.Success -> {
                            showWindow(result.window)
                            result.output
                        }
                    }
                },
            ),
        )
    }
}
