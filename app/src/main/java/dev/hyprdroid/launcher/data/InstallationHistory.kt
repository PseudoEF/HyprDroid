package dev.hyprdroid.launcher.data

import android.content.Context

/** Capture before either DataStore can create files. An empty new workspace is not a legacy install. */
internal object InstallationHistory {
    private var legacy: Boolean? = null
    @Synchronized fun capture(context: Context): Boolean {
        return legacy ?: (listOf("workspaces", "shell").any {
            context.applicationContext.filesDir.resolve("datastore/$it.preferences_pb").exists()
        } || runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime != info.lastUpdateTime
        }.getOrDefault(false)).also { legacy = it }
    }
}
