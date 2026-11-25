package dev.hyprdroid.launcher.external

import android.util.Log
import dev.hyprdroid.launcher.BuildConfig

/** Correlation for the isolated-worker instrumentation fixture. Contains no package or state data. */
internal object TileInputTrace {
    @Volatile var current: String? = null

    fun stage(id: String?, phase: String) {
        if (BuildConfig.V2_FIXTURE_LUA && id != null) Log.i("HyprTileInput", "input=$id phase=$phase")
    }
}
