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

class LauncherCatalog(private val context: Context) {
    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val user = Process.myUserHandle()

    fun load(): List<LauncherApp> {
        val size = (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
        return launcher.getActivityList(null, user)
            .filter { it.componentName.packageName != context.packageName }
            .map { activity ->
                val icon = try {
                    activity.getBadgedIcon(context.resources.displayMetrics.densityDpi)
                        .toBitmap(size, size)
                } catch (_: RuntimeException) {
                    context.packageManager.defaultActivityIcon.toBitmap(size, size)
                }
                LauncherApp(activity.componentName.flattenToString(), activity.label.toString(), icon)
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }

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
    fun loadSidebarEntries(apps: List<LauncherApp>): List<SidebarEntry> {
        val activities = apps.map { SidebarEntry(WorkspaceItem.AppShortcut(it.componentId), it) }
        if (!launcher.hasShortcutHostPermission()) return activities
        val size = (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
        val shortcuts = try {
            launcher.getShortcuts(LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            ), user).orEmpty().filter { it.isEnabled && it.`package` != context.packageName }.map { info ->
                val component = info.activity?.flattenToString() ?: "${info.`package`}/.Shortcut"
                val item = WorkspaceItem.AppShortcut(component, info.id)
                val icon = try { launcher.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)?.toBitmap(size, size) }
                    catch (_: RuntimeException) { null }
                SidebarEntry(item, LauncherApp(component, info.shortLabel?.toString() ?: info.id,
                    icon ?: context.packageManager.defaultActivityIcon.toBitmap(size, size)))
            }
        } catch (_: SecurityException) { emptyList() }
        return (activities + shortcuts).distinctBy { it.item.id }.sortedBy { it.app.label.lowercase(Locale.ROOT) }
    }

    fun launch(item: WorkspaceItem.AppShortcut) {
        val shortcut = item.shortcutId
        if (shortcut == null) launch(item.componentId)
        else launcher.startShortcut(item.componentId.substringBefore('/'), shortcut, null, null, user)
    }

    fun observe(onChanged: () -> Unit, onRemoved: (String) -> Unit): () -> Unit {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) = changed(user)
            override fun onPackageChanged(packageName: String, user: UserHandle) = changed(user)
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                if (user == this@LauncherCatalog.user) onRemoved(packageName)
            }
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
            override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<android.content.pm.ShortcutInfo>, user: UserHandle) = changed(user)
            private fun changed(profile: UserHandle) {
                if (profile == this@LauncherCatalog.user) onChanged()
            }
        }
        launcher.registerCallback(callback, Handler(Looper.getMainLooper()))
        return { launcher.unregisterCallback(callback) }
    }
}
