package dev.hyprdroid.launcher.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.*
import dev.hyprdroid.launcher.model.*
import java.io.IOException
import org.json.JSONObject

internal object ShellPresentationCodec {
    val schema = intPreferencesKey("schema_version")
    private val mode = stringPreferencesKey("desktop_mode")
    private val dock = stringPreferencesKey("dock_visibility")
    private val modules = stringSetPreferencesKey("sidebar_modules")
    private val minimal = stringPreferencesKey("minimal_ui_configuration")

    fun read(data: Preferences): ShellPresentation {
        val version = data[schema] ?: 1
        if (version !in 1..3) throw IOException("Unsupported shell schema")
        if (version < 3) return ShellPresentation(if (data.asMap().isEmpty()) DesktopMode.CLEAN else DesktopMode.CLASSIC)
        val desktop = DesktopMode.entries.find { it.name == data[mode] } ?: throw IOException("Invalid desktop mode")
        val visibility = DockVisibility.entries.find { it.name == data[dock] } ?: throw IOException("Invalid dock visibility")
        val rawModules = data[modules] ?: throw IOException("Missing sidebar modules")
        val selected = rawModules.map { name -> SidebarModule.entries.find { it.name == name } ?: throw IOException("Unknown sidebar module") }.toSet()
        return try {
            val extra = data[minimal]?.let(::JSONObject)
            if (extra == null) ShellPresentation(desktop, visibility, selected)
            else {
                if (extra.getInt("version") != 1) throw IOException("Unsupported minimal UI schema")
                ShellPresentation(desktop, visibility, selected,
                    ShellButtonVisibility.valueOf(extra.getString("drawerTopbar")), ShellButtonVisibility.valueOf(extra.getString("drawerDock")),
                    extra.getBoolean("sidebarTopbar"), WallpaperScale.valueOf(extra.getString("wallpaperScale")), extra.getBoolean("wallpaperMotion"))
            }
        } catch (error: org.json.JSONException) { throw IOException("Invalid minimal UI settings", error) }
          catch (error: IllegalArgumentException) { throw IOException("Invalid minimal UI settings", error) }
    }

    fun write(data: MutablePreferences, value: ShellPresentation) {
        data[schema] = 3
        data[mode] = value.desktopMode.name
        data[dock] = value.dockVisibility.name
        data[modules] = value.sidebarModules.map { it.name }.toSet()
        data[minimal] = JSONObject().put("version", 1).put("drawerTopbar", value.drawerTopbar.name).put("drawerDock", value.drawerDock.name)
            .put("sidebarTopbar", value.sidebarTopbar).put("wallpaperScale", value.wallpaperScale.name).put("wallpaperMotion", value.wallpaperMotion).toString()
    }
}

class ShellV3Migration(private val legacyWorkspace: Boolean) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = (currentData[ShellPresentationCodec.schema] ?: 1) in 1..2
    override suspend fun migrate(currentData: Preferences): Preferences {
        val existing = legacyWorkspace || currentData.asMap().isNotEmpty()
        return currentData.toMutablePreferences().also {
            ShellPresentationCodec.write(it, ShellPresentation(if (existing) DesktopMode.CLASSIC else DesktopMode.CLEAN))
        }.toPreferences()
    }
    override suspend fun cleanUp() = Unit
}
