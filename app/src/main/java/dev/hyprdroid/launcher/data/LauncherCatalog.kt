package dev.hyprdroid.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.core.graphics.drawable.toBitmap
import java.util.Locale
import dev.hyprdroid.launcher.model.WorkspaceItem

/** Icons and labels belong to the current catalogue, never to persisted preferences. */
data class LauncherApp(val componentId: String, val label: String, val icon: Bitmap)
data class SidebarEntry(val item: WorkspaceItem.AppShortcut, val app: LauncherApp)

/** Catalogue of the current profile; only this profile's activities are ever requested. */
class LauncherCatalog(private val context: Context) {
    companion object { private const val MAX_ICON_CACHE_BYTES = 32L * 1024 * 1024 }

    data class Snapshot(val apps: List<LauncherApp>, val entries: List<SidebarEntry>)

    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val user = Process.myUserHandle()
    private val icons = CatalogIconCache<Bitmap>(MAX_ICON_CACHE_BYTES)

    /**
     * Rebuilds labels and order from LauncherApps, reusing decoded icons while the package update
     * timestamp, density and pixel size stay the same. [invalidatePackages] forces a fresh icon for
     * those packages, and [refreshIcons] re-decodes the whole catalogue for an explicit user refresh.
     */
    fun reload(invalidatePackages: Set<String> = emptySet(), refreshIcons: Boolean = false): Snapshot {
        val metrics = context.resources.displayMetrics
        val size = (48 * metrics.density).toInt().coerceIn(48, 192)
        val dpi = metrics.densityDpi
        val versions = packageVersions()
        val used = mutableSetOf<IconKey>()
        val apps = launcher.getActivityList(null, user)
            .filter { it.componentName.packageName != context.packageName }
            .map { activity ->
                val component = activity.componentName.flattenToString()
                val packageName = activity.componentName.packageName
                val key = IconKey(component, dpi, size)
                used += key
                val updated = versions[packageName]
                val stamp = IconStamp(packageUpdated = updated)
                val cached = if (refreshIcons || updated == null || packageName in invalidatePackages) null
                    else icons.get(key, stamp)
                val icon = cached ?: try {
                    activity.getBadgedIcon(dpi).toBitmap(size, size)
                } catch (_: RuntimeException) {
                    context.packageManager.defaultActivityIcon.toBitmap(size, size)
                }
                // Without a readable update time the icon is never cached: a missed callback must
                // not be able to keep a stale icon forever.
                if (cached == null && updated != null) icons.put(key, stamp, icon.allocationByteCount, icon)
                LauncherApp(component, activity.label.toString(), icon)
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        val entries = sidebarEntries(apps, dpi, size, versions, invalidatePackages, refreshIcons, used)
        icons.retain(used)
        return Snapshot(apps, entries)
    }

    /** One binder call per pass instead of one per package; invisible packages fall back to decode. */
    @Suppress("DEPRECATION")
    private fun packageVersions(): Map<String, Long> = try {
        context.packageManager.getInstalledPackages(0).associate { it.packageName to it.lastUpdateTime }
    } catch (_: RuntimeException) {
        emptyMap()
    }

    /** Apps without the sidebar snapshot, for callers that only need the launchable catalogue. */
    fun load(): List<LauncherApp> = reload().apps

    /** A missing launcher activity alone is not evidence of an uninstall (e.g. disabled app). */
    fun missingPackages(componentIds: Set<String>): Set<String> = componentIds
        .map { it.substringBefore('/') }.toSet().filter { packageName ->
            try {
                launcher.getApplicationInfo(packageName, 0, user)
                false
            } catch (_: PackageManager.NameNotFoundException) {
                true
            } catch (_: SecurityException) {
                false
            }
        }.toSet()

    fun launch(componentId: String) {
        val component = requireNotNull(ComponentName.unflattenFromString(componentId))
        launcher.startMainActivity(component, user, null, null)
    }

    /** Public Android shortcuts are visible only while Android grants HOME shortcut-host access. */
    private fun sidebarEntries(apps: List<LauncherApp>, dpi: Int, size: Int, versions: Map<String, Long>,
        invalidatePackages: Set<String>, refreshIcons: Boolean, used: MutableSet<IconKey>): List<SidebarEntry> {
        val activities = apps.map { SidebarEntry(WorkspaceItem.AppShortcut(it.componentId), it) }
        if (!launcher.hasShortcutHostPermission()) return activities
        val shortcuts = try {
            launcher.getShortcuts(LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            ), user).orEmpty().filter { it.isEnabled && it.`package` != context.packageName }.map { info ->
                val component = info.activity?.flattenToString() ?: "${info.`package`}/.Shortcut"
                val item = WorkspaceItem.AppShortcut(component, info.id)
                val key = IconKey("shortcut:$component/${info.id}", dpi, size)
                used += key
                val updated = IconStamp(info.lastChangedTimestamp, versions[info.`package`])
                val cached = if (refreshIcons || updated.packageUpdated == null || info.`package` in invalidatePackages) null
                    else icons.get(key, updated)
                val icon = cached ?: try { launcher.getShortcutIconDrawable(info, dpi)?.toBitmap(size, size) }
                    catch (_: RuntimeException) { null }
                if (cached == null && icon != null && updated.packageUpdated != null) icons.put(key, updated, icon.allocationByteCount, icon)
                val application = apps.firstOrNull { it.componentId.substringBefore('/') == info.`package` }?.label ?: info.`package`
                SidebarEntry(item, LauncherApp(component, "${info.shortLabel ?: info.id} · $application",
                    icon ?: context.packageManager.defaultActivityIcon.toBitmap(size, size)))
            }
        } catch (_: RuntimeException) { emptyList() }
        return (activities + shortcuts).distinctBy { it.item.id }.sortedBy { it.app.label.lowercase(Locale.ROOT) }
    }

    fun launch(item: WorkspaceItem.AppShortcut) {
        val shortcut = item.shortcutId
        if (shortcut == null) launch(item.componentId)
        else launcher.startShortcut(item.componentId.substringBefore('/'), shortcut, null, null, user)
    }

    /** Package events name the packages whose cached icons must be refreshed. */
    fun observe(onChanged: (Set<String>) -> Unit, onRemoved: (String) -> Unit): () -> Unit {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) = changed(setOf(packageName), user)
            override fun onPackageChanged(packageName: String, user: UserHandle) = changed(setOf(packageName), user)
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                if (user == this@LauncherCatalog.user) onRemoved(packageName)
            }
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
                changed(packageNames.toSet(), user)
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
                changed(packageNames.toSet(), user)
            override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<android.content.pm.ShortcutInfo>, user: UserHandle) =
                changed(setOf(packageName), user)
            private fun changed(packages: Set<String>, profile: UserHandle) {
                if (profile == this@LauncherCatalog.user) onChanged(packages)
            }
        }
        launcher.registerCallback(callback, Handler(Looper.getMainLooper()))
        return { launcher.unregisterCallback(callback) }
    }
}
