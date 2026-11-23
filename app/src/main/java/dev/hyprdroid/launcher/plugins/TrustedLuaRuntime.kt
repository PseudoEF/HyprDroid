package dev.hyprdroid.launcher.plugins

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaInteger
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.VarArgFunction

/** Runs trusted UTF-8 source. The Android caller only provides scripts baked into the APK. */
internal class TrustedLuaRuntime(private val nanoTime: () -> Long = System::nanoTime) {
    fun run(source: ByteArray, permissions: Set<PluginPermission>, inputs: PluginInputs, tile: TileRequest? = null): PluginResult {
        if (source.isEmpty() || source.size > PluginLimits.SOURCE_BYTES) {
            return PluginResult.Failure("Недопустимый размер скрипта Lua")
        }
        if (source.first() == 0x1b.toByte() || source.any { it == 0.toByte() }) {
            return PluginResult.Failure("Ожидается текстовый скрипт Lua")
        }
        val script = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source)).toString()
        } catch (_: CharacterCodingException) {
            return PluginResult.Failure("Скрипт Lua должен быть в UTF-8")
        }

        if (tile != null && PluginPermission.UI_TILE !in permissions) {
            return PluginResult.Failure("Доступ запрещён: ui.tile")
        }
        if (tile != null && (!tile.state.isPluginText(TILE_STATE_LIMIT) ||
                tile.event?.let { !tileEventId.matches(it) } == true)
        ) {
            return PluginResult.Failure("Некорректное событие тайла")
        }
        val allowed = if (tile == null) permissions - PluginPermission.UI_TILE
            else permissions - PluginPermission.UI_WINDOW - PluginPermission.TERMINAL_PRINT
        val effects = PluginEffects(allowed, inputs, tile)
        return try {
            val globals = Globals()
            LuaC.install(globals)
            // LuaClosure calls this hook directly. No debug table is ever registered.
            globals.debuglib = ExecutionBudget(nanoTime)
            globals.set("hypr", effects.api())
            globals.load(script.reader(), "@trusted-plugin.lua").call()
            effects.dispatchTileEvent()
            effects.result()
        } catch (error: LuaError) {
            PluginResult.Failure(
                error.message.orEmpty().lineSequence().firstOrNull().orEmpty()
                    .filterNot(Char::isISOControl).take(PluginLimits.ERROR_TEXT).ifBlank { "Ошибка Lua" },
            )
        } catch (_: IllegalArgumentException) {
            PluginResult.Failure("Некорректные аргументы Tile Plugin API")
        } catch (_: StackOverflowError) {
            PluginResult.Failure("Превышен предел стека Lua")
        }
    }

    private class ExecutionBudget(private val nanoTime: () -> Long) : DebugLib() {
        private val started = nanoTime()
        private var remaining = PluginLimits.INSTRUCTIONS
        private var depth = 0

        override fun onInstruction(pc: Int, v: Varargs, top: Int) {
            if (remaining-- <= 0) throw LuaError("Превышен лимит инструкций Lua")
            if (remaining % 64 == 0) {
                if (Thread.currentThread().isInterrupted) throw LuaError("Выполнение Lua отменено")
                if (nanoTime() - started >= PluginLimits.EXECUTION_NS) throw LuaError("Превышен лимит времени Lua")
            }
        }

        override fun onCall(f: LuaFunction) = enter()
        override fun onCall(c: LuaClosure, varargs: Varargs, stack: Array<LuaValue>) = enter()
        override fun onReturn() {
            depth--
        }
        override fun traceback(level: Int) = ""

        private fun enter() {
            if (++depth > PluginLimits.CALL_DEPTH) throw LuaError("Превышена глубина вызовов Lua")
        }
    }
}

private class PluginEffects(
    private val permissions: Set<PluginPermission>,
    private val inputs: PluginInputs,
    private val tile: TileRequest?,
) {
    private val output = mutableListOf<String>()
    private var outputSize = 0
    private var remainingCalls = PluginLimits.API_CALLS
    private var nextWindow = 0
    private var window: PluginWindow? = null
    private var root: TileNode? = null
    private var tileState = tile?.state.orEmpty()
    private val callbacks = mutableMapOf<String, LuaFunction>()

    fun dispatchTileEvent() {
        val event = tile?.event ?: return
        val callback = callbacks[event] ?: throw LuaError("Обработчик события тайла отсутствует")
        callback.call()
    }

    fun api(): LuaTable = LuaTable().apply {
        set("terminal", LuaTable().apply {
            expose("print", PluginPermission.TERMINAL_PRINT, 1) { args ->
                val text = args.arg(1).boundedText(PluginLimits.OUTPUT_TEXT)
                if (output.size >= PluginLimits.OUTPUT_LINES || outputSize + text.length > PluginLimits.OUTPUT_TOTAL) {
                    throw LuaError("Превышен лимит вывода Lua")
                }
                output += text
                outputSize += text.length
                LuaValue.NIL
            }
        })
        set("system", LuaTable().apply {
            expose("getDeviceInfo", PluginPermission.SYSTEM_DEVICE, 0) {
                val device = inputs.device ?: throw LuaError("Сведения об устройстве недоступны")
                LuaTable().apply {
                    set("manufacturer", LuaValue.valueOf(device.manufacturer))
                    set("model", LuaValue.valueOf(device.model))
                    set("release", LuaValue.valueOf(device.release))
                    set("sdk", LuaValue.valueOf(device.sdk))
                }
            }
            expose("getBattery", PluginPermission.SYSTEM_BATTERY, 0) {
                val battery = inputs.battery ?: throw LuaError("Сведения о батарее недоступны")
                LuaTable().apply {
                    battery.percent?.let { set("percent", LuaValue.valueOf(it)) }
                    battery.charging?.let { set("charging", LuaValue.valueOf(it)) }
                }
            }
        })
        set("ui", LuaTable().apply {
            if (tile != null) {
                expose("render", PluginPermission.UI_TILE, 1) { args ->
                    root = TileUiCodec.fromLua(args.arg(1))
                    LuaValue.NIL
                }
                expose("on", PluginPermission.UI_TILE, 2) { args ->
                    val id = TileUiCodec.luaString(args.arg(1), 32)
                    require(tileEventId.matches(id) && id !in callbacks && callbacks.size < 32)
                    callbacks[id] = args.arg(2).checkfunction()
                    LuaValue.NIL
                }
                expose("getState", PluginPermission.UI_TILE, 0) { LuaValue.valueOf(tileState) }
                expose("setState", PluginPermission.UI_TILE, 1) { args ->
                    tileState = TileUiCodec.luaString(args.arg(1), TILE_STATE_LIMIT)
                    LuaValue.NIL
                }
            }
            expose("createWindow", PluginPermission.UI_WINDOW, 1) { args ->
                if (window != null) throw LuaError("Одновременно разрешено одно окно Lua")
                window = PluginWindow(args.arg(1).boundedText(PluginLimits.WINDOW_TITLE), "")
                LuaValue.valueOf(++nextWindow)
            }
            expose("showText", PluginPermission.UI_WINDOW, 2) { args ->
                val active = requireWindow(args.arg(1))
                window = active.copy(text = args.arg(2).boundedText(PluginLimits.WINDOW_TEXT))
                LuaValue.NIL
            }
            expose("closeWindow", PluginPermission.UI_WINDOW, 1) { args ->
                requireWindow(args.arg(1))
                window = null
                LuaValue.NIL
            }
        })
    }

    fun result() = PluginResult.Success(output.toList(), window, tile?.let { TileResult(root, tileState) })

    private fun LuaTable.expose(
        name: String,
        permission: PluginPermission,
        count: Int,
        body: (Varargs) -> LuaValue,
    ) {
        set(name, object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                if (permission !in permissions) throw LuaError("Доступ запрещён: ${permission.apiName}")
                if (remainingCalls-- <= 0) throw LuaError("Превышен лимит вызовов Plugin API")
                if (args.narg() != count) throw LuaError("Некорректное число аргументов Plugin API")
                return body(args)
            }
        })
    }

    private fun requireWindow(handle: LuaValue): PluginWindow {
        if (handle !is LuaInteger || handle.toint() != nextWindow) throw LuaError("Недействительный дескриптор окна")
        return window ?: throw LuaError("Окно Lua закрыто")
    }

    private fun LuaValue.boundedText(limit: Int): String {
        if (type() != LuaValue.TSTRING) throw LuaError("Plugin API ожидает строку")
        val value = checkstring()
        if (value.m_length > limit) throw LuaError("Превышен лимит текста Plugin API")
        val text = value.tojstring()
        if (!text.isPluginText(limit)) throw LuaError("Некорректный текст Plugin API")
        return text
    }
}
