package dev.hyprdroid.launcher.plugins

import android.os.Bundle

/** One versioned request and one result per binding. Only bounded scalar values cross Binder. */
internal object LuaPluginIpc {
    const val VERSION = 1
    const val RUN = 1
    const val RESULT = 2
    const val ID = "id"

    fun request(plugin: BuiltInLuaPlugin, inputs: PluginInputs, tile: TileRequest?) = Bundle().apply {
        putString(ID, plugin.commandId)
        tile?.let {
            putString("tileState", it.state)
            putString("tileEvent", it.event)
        }
        inputs.device?.let {
            putString("manufacturer", it.manufacturer)
            putString("model", it.model)
            putString("release", it.release)
            putInt("sdk", it.sdk)
        }
        inputs.battery?.let {
            putInt("percent", it.percent ?: -1)
            val charging = when (it.charging) {
                true -> 1
                false -> 0
                null -> -1
            }
            putInt("charging", charging)
        }
    }

    fun readTileRequest(data: Bundle, plugin: BuiltInLuaPlugin): TileRequest? {
        val state = data.getString("tileState")
        val event = data.getString("tileEvent")
        require(plugin.isTile == (state != null))
        require(event == null || (plugin.isTile && tileEventId.matches(event)))
        return state?.let {
            require(it.isPluginText(TILE_STATE_LIMIT))
            TileRequest(event, it)
        }
    }

    fun readInputs(data: Bundle, plugin: BuiltInLuaPlugin): PluginInputs {
        fun field(name: String): String {
            val value = data.getString(name)
            require(value != null && value.isPluginText(PluginLimits.DEVICE_FIELD))
            return value
        }
        val permissions = plugin.permissions
        val device = if (PluginPermission.SYSTEM_DEVICE in permissions) {
            val sdk = data.getInt("sdk")
            require(sdk > 0)
            PluginDevice(field("manufacturer"), field("model"), field("release"), sdk)
        } else {
            null
        }
        val battery = if (PluginPermission.SYSTEM_BATTERY in permissions) {
            val percent = data.getInt("percent", -1).takeIf { it in 0..100 }
            val charging = when (data.getInt("charging", -1)) {
                0 -> false
                1 -> true
                else -> null
            }
            PluginBattery(percent, charging)
        } else {
            null
        }
        return PluginInputs(device, battery)
    }

    fun response(result: PluginResult): Bundle = Bundle().apply {
        when (result) {
            is PluginResult.Failure -> putString("error", result.message)
            is PluginResult.Success -> {
                putStringArrayList("output", ArrayList(result.output))
                result.tile?.let {
                    putString("tileState", it.state)
                    it.root?.let { root -> putStringArrayList("tileTree", TileUiCodec.encode(root)) }
                }
                result.window?.let {
                    putString("title", it.title)
                    putString("text", it.text)
                }
            }
        }
    }

    fun readResult(data: Bundle, plugin: BuiltInLuaPlugin): PluginResult = try {
        val error = data.getString("error")
        if (error != null) {
            require(error.isPluginText(PluginLimits.ERROR_TEXT))
            PluginResult.Failure(error)
        } else {
            val output = requireNotNull(data.getStringArrayList("output"))
            val title = data.getString("title")
            val text = data.getString("text")
            require(output.size <= PluginLimits.OUTPUT_LINES)
            require(output.all { it.isPluginText(PluginLimits.OUTPUT_TEXT) })
            require(output.sumOf { it.length } <= PluginLimits.OUTPUT_TOTAL)
            require(output.isEmpty() || PluginPermission.TERMINAL_PRINT in plugin.permissions)
            require((title == null) == (text == null))
            val window = if (title != null && text != null) {
                require(PluginPermission.UI_WINDOW in plugin.permissions)
                require(title.isPluginText(PluginLimits.WINDOW_TITLE) && text.isPluginText(PluginLimits.WINDOW_TEXT))
                PluginWindow(title, text)
            } else {
                null
            }
            val tileState = data.getString("tileState")
            val tree = data.getStringArrayList("tileTree")
            require(plugin.isTile == (tileState != null) && (tree == null || plugin.isTile))
            val tile = tileState?.let {
                require(it.isPluginText(TILE_STATE_LIMIT))
                TileResult(tree?.let(TileUiCodec::decode), it)
            }
            PluginResult.Success(output.toList(), window, tile)
        }
    } catch (_: RuntimeException) {
        PluginResult.Failure("Некорректный ответ процесса Lua")
    }
}
