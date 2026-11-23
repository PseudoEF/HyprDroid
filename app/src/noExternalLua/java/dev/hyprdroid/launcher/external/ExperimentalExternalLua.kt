package dev.hyprdroid.launcher.external

import android.content.Context
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TileRequest
import dev.hyprdroid.launcher.plugins.TileExecutionV2
import dev.hyprdroid.launcher.plugins.TileFrameV2

internal object ExperimentalExternalLua {
    @Suppress("UNUSED_PARAMETER")
    suspend fun run(context: Context, source: ByteArray, request: TileRequest, authorized: () -> Boolean = { true }): PluginResult =
        PluginResult.Failure("Внешний Lua отключён")
    @Suppress("UNUSED_PARAMETER")
    suspend fun runV2(context: Context, source: ByteArray, request: TileExecutionV2, authorized: () -> Boolean = { true }): TileFrameV2? = null
}
