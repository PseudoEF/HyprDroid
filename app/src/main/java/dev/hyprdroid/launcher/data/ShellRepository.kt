package dev.hyprdroid.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.hyprdroid.launcher.model.MAX_DOCK_APPS
import dev.hyprdroid.launcher.model.ShellSettings
import dev.hyprdroid.launcher.model.WorkspaceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import org.json.JSONException
import java.io.IOException
import kotlinx.coroutines.flow.map

private val Context.shellStore by preferencesDataStore(
    name = "shell",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class DockFullException : IOException("Dock is full")

class ShellRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.shellStore)
    private val version = intPreferencesKey("schema_version")
    private val favorites = stringSetPreferencesKey("favorites")
    private val wallpaper = stringPreferencesKey("wallpaper_uri")
    private val sidebar = stringPreferencesKey("sidebar_items")
    val settings = store.data.map(::decode).flowOn(Dispatchers.Default)

    suspend fun setSidebarItem(item: WorkspaceItem.AppShortcut, pinned: Boolean) {
        require(dev.hyprdroid.launcher.model.validComponent(item.componentId))
        require(item.shortcutId == null || item.shortcutId.isNotBlank())
        updateSidebar { items -> when {
            !pinned -> items.filterNot { it.id == item.id }
            items.any { it.id == item.id } -> items
            else -> items + item
        } }
    }

    suspend fun removeSidebarItem(id: String) = updateSidebar { it.filterNot { item -> item.id == id } }
    suspend fun reorderSidebar(id: String, delta: Int) = updateSidebar { items ->
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) items else items.toMutableList().apply {
            add((index.toLong() + delta).coerceIn(0, lastIndex.toLong()).toInt(), removeAt(index))
        }
    }
    private suspend fun updateSidebar(transform: (List<WorkspaceItem>) -> List<WorkspaceItem>) {
        store.edit { data ->
            data[sidebar] = WorkspaceCodec.encodeItems(transform(decode(data).sidebar))
            data[version] = 2
        }
    }

    suspend fun setFavorite(component: String, enabled: Boolean) {
        store.edit { preferences ->
            val current = decode(preferences)
            val apps = current.favorites
            if (enabled && component !in apps && apps.size >= MAX_DOCK_APPS) throw DockFullException()
            preferences[favorites] = if (enabled) apps + component else apps - component
            preferences[version] = 2
        }
    }

    suspend fun setWallpaper(uri: String?) {
        store.edit { preferences ->
            decode(preferences) // Never overwrite a newer schema.
            if (uri == null) preferences.remove(wallpaper) else preferences[wallpaper] = uri
            preferences[version] = 2
        }
    }

    suspend fun removePackages(packages: Set<String>) {
        store.edit { preferences ->
            val current = decode(preferences)
            preferences[favorites] = current.favorites.filterNot { it.substringBefore('/') in packages }.toSet()
            preferences[sidebar] = WorkspaceCodec.encodeItems(current.sidebar.filterNot {
                it is WorkspaceItem.AppShortcut && it.componentId.substringBefore('/') in packages
            })
            preferences[version] = 2
        }
    }

    private fun decode(preferences: Preferences): ShellSettings {
        if ((preferences[version] ?: 1) !in 1..2) throw IOException("Unsupported shell schema")
        return ShellSettings(
            favorites = preferences[favorites].orEmpty().toSet(),
            wallpaperUri = preferences[wallpaper],
            sidebar = try { WorkspaceCodec.decodeItems(preferences[sidebar] ?: "[]") }
                catch (error: JSONException) { throw IOException("Invalid sidebar records", error) },
        )
    }
}
