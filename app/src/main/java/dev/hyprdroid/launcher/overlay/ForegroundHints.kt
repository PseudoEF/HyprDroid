package dev.hyprdroid.launcher.overlay

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import dev.hyprdroid.launcher.model.ForegroundCategory

/** Optional package-only hints. No text, windows, history database, analytics or Accessibility. */
internal class ForegroundHints(private val context: Context) {
    private var since = 0L
    private var foreground: String? = null
    fun permitted(): Boolean = context.getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    fun reset() { since = 0; foreground = null }
    fun sample(): Pair<String?, ForegroundCategory> {
        if (!permitted()) { reset(); return null to ForegroundCategory.UNKNOWN }
        val now = System.currentTimeMillis()
        return try {
            val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(if (since == 0L) now - 120_000 else (since - 1).coerceAtLeast(now - 120_000), now)
            if (events == null) { reset(); return null to ForegroundCategory.UNKNOWN }
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> foreground = event.packageName
                    UsageEvents.Event.ACTIVITY_PAUSED -> if (foreground == event.packageName) foreground = null
                }
            }
            since = now
            val pkg = foreground
            val category = if (pkg == null) ForegroundCategory.UNKNOWN else {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                when (info.category) { ApplicationInfo.CATEGORY_GAME -> ForegroundCategory.GAME; ApplicationInfo.CATEGORY_VIDEO -> ForegroundCategory.VIDEO; ApplicationInfo.CATEGORY_UNDEFINED -> ForegroundCategory.UNKNOWN; else -> ForegroundCategory.NORMAL }
            }
            pkg to category
        } catch (_: Exception) { null to ForegroundCategory.UNKNOWN }
    }
}
