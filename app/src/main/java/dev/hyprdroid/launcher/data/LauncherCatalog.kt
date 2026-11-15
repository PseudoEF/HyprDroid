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

/** Icons and labels belong to the current catalogue, never to persisted preferences. */
data class LauncherApp(val componentId: String, val label: String, val icon: Bitmap)

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

    fun observe(onChanged: () -> Unit, onRemoved: (String) -> Unit): () -> Unit {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) = changed(user)
            override fun onPackageChanged(packageName: String, user: UserHandle) = changed(user)
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                if (user == this@LauncherCatalog.user) onRemoved(packageName)
            }
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
            private fun changed(profile: UserHandle) {
                if (profile == this@LauncherCatalog.user) onChanged()
            }
        }
        launcher.registerCallback(callback, Handler(Looper.getMainLooper()))
        return { launcher.unregisterCallback(callback) }
    }
}
