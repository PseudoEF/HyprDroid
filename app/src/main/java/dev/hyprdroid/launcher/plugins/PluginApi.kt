package dev.hyprdroid.launcher.plugins

internal enum class PluginPermission(val apiName: String) {
    TERMINAL_PRINT("terminal.print"),
    SYSTEM_DEVICE("system.device"),
    SYSTEM_BATTERY("system.battery"),
    UI_WINDOW("ui.window"),
    UI_TILE("ui.tile"),
}

/** The APK is the only executable source. IDs are never interpreted as paths. */
internal enum class BuiltInLuaPlugin(
    val commandId: String,
    val asset: String,
    val permissions: Set<PluginPermission>,
) {
    DROIDFETCH(
        "droidfetch",
        "trusted-lua/droidfetch.lua",
        setOf(
            PluginPermission.TERMINAL_PRINT,
            PluginPermission.SYSTEM_DEVICE,
            PluginPermission.SYSTEM_BATTERY,
            PluginPermission.UI_WINDOW,
        ),
    ),
    DROIDFETCH_TILE(
        "internal-droidfetch-tile",
        "trusted-lua/droidfetch-tile.lua",
        setOf(PluginPermission.SYSTEM_DEVICE, PluginPermission.SYSTEM_BATTERY, PluginPermission.UI_TILE),
    );

    val isTile: Boolean get() = PluginPermission.UI_TILE in permissions

    companion object {
        fun find(id: String) = findRuntime(id)?.takeUnless { it.isTile }
        fun findRuntime(id: String) = entries.firstOrNull { it.commandId == id }
    }
}

internal data class PluginDevice(val manufacturer: String, val model: String, val release: String, val sdk: Int)
internal data class PluginBattery(val percent: Int?, val charging: Boolean?)
internal data class PluginInputs(val device: PluginDevice? = null, val battery: PluginBattery? = null)
internal data class PluginWindow(val title: String, val text: String)

internal sealed interface PluginResult {
    data class Success(val output: List<String>, val window: PluginWindow?, val tile: TileResult? = null) : PluginResult
    data class Failure(val message: String) : PluginResult
}

internal object PluginLimits {
    const val SOURCE_BYTES = 16 * 1024
    const val INSTRUCTIONS = 50_000
    const val EXECUTION_NS = 250_000_000L
    const val CALL_DEPTH = 48
    const val API_CALLS = 64
    const val OUTPUT_LINES = 32
    const val OUTPUT_TEXT = 1024
    const val OUTPUT_TOTAL = 8 * 1024
    const val WINDOW_TITLE = 80
    const val WINDOW_TEXT = 4 * 1024
    const val DEVICE_FIELD = 80
    const val ERROR_TEXT = 256
}

internal fun String.isPluginText(limit: Int): Boolean =
    length <= limit && none { it.isISOControl() && it != '\n' && it != '\t' }
