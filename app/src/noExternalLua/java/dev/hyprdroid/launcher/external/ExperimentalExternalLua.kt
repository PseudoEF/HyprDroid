package dev.hyprdroid.launcher.external

import android.content.Context
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TileRequest

internal object ExperimentalExternalLua {
    @Suppress("UNUSED_PARAMETER")
    suspend fun run(context: Context, source: ByteArray, request: TileRequest, authorized: () -> Boolean = { true }): PluginResult =
        PluginResult.Failure("Внешний Lua отключён")
}
