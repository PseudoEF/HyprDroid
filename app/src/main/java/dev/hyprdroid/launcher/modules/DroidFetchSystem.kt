package dev.hyprdroid.launcher.modules

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import java.util.Locale

internal object DroidFetchSystem {
    fun read(context: Context): DroidFetchInfo {
        val memory = try {
            val manager = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            if (info.totalMem > 0) {
                val used = (info.totalMem - info.availMem).coerceAtLeast(0)
                "${gib(used)} / ${gib(info.totalMem)} GiB"
            } else "N/A"
        } catch (_: Exception) {
            "N/A"
        }

        val battery = try {
            val manager = context.getSystemService(BatteryManager::class.java)
            val percent = manager?.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CAPACITY
            ) ?: -1
            if (percent in 0..100) "$percent%" else "N/A"
        } catch (_: Exception) {
            "N/A"
        }

        val minutes = SystemClock.elapsedRealtime() / 60_000
        val days = minutes / 1440
        val hours = (minutes / 60) % 24
        val mins = minutes % 60

        val uptime = buildString {
            if (days > 0) append("${days}d ")
            append("${hours}h ${mins}m")
        }

        return DroidFetchInfo(
            os = "Android ${Build.VERSION.RELEASE}",
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            cpu = Build.SUPPORTED_ABIS.firstOrNull() ?: "N/A",
            memory = memory,
            battery = battery,
            uptime = uptime,
        )
    }

    private fun gib(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / 1073741824.0)
}
