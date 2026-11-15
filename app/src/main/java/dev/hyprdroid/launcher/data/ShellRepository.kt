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
    val settings = store.data.map(::decode)

    suspend fun setFavorite(component: String, enabled: Boolean) {
        store.edit { preferences ->
            val current = decode(preferences)
            val apps = current.favorites
            if (enabled && component !in apps && apps.size >= MAX_DOCK_APPS) throw DockFullException()
            preferences[favorites] = if (enabled) apps + component else apps - component
            preferences[version] = 1
        }
    }

    suspend fun setWallpaper(uri: String?) {
        store.edit { preferences ->
            decode(preferences) // Never overwrite a newer schema.
            if (uri == null) preferences.remove(wallpaper) else preferences[wallpaper] = uri
            preferences[version] = 1
        }
    }

    suspend fun removePackages(packages: Set<String>) {
        store.edit { preferences ->
            preferences[favorites] = decode(preferences).favorites.filterNot { it.substringBefore('/') in packages }.toSet()
            preferences[version] = 1
        }
    }

    private fun decode(preferences: Preferences): ShellSettings {
        if ((preferences[version] ?: 1) != 1) throw IOException("Unsupported shell schema")
        return ShellSettings(
            favorites = preferences[favorites].orEmpty().toSet(),
            wallpaperUri = preferences[wallpaper],
        )
    }
}
