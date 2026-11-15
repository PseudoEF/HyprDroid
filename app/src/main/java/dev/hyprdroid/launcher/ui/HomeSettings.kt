package dev.hyprdroid.launcher.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings

fun openSystemSettings(context: Context, action: String): Boolean {
    for (destination in listOf(action, Settings.ACTION_SETTINGS).distinct()) {
        try {
            context.startActivity(Intent(destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: RuntimeException) { }
    }
    return false
}

/** Always leave a route to Android settings, even on devices without a HOME settings screen. */
fun openHomeSettings(context: Context): Boolean {
    for (action in listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: RuntimeException) {
            // Some OEMs do not expose the more specific settings activities.
        }
    }
    return false
}
