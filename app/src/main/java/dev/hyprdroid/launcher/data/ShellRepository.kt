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
import dev.hyprdroid.launcher.model.ShellPresentation
import dev.hyprdroid.launcher.model.OverlaySettings
import androidx.datastore.preferences.core.booleanPreferencesKey
import dev.hyprdroid.launcher.model.placeItemBefore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import org.json.JSONException
import java.io.IOException
import kotlinx.coroutines.flow.map

private val Context.shellStore by preferencesDataStore(
    name = "shell",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { listOf(ShellV3Migration(InstallationHistory.capture(it))) },
)

private fun shellStoreFor(context: Context): DataStore<Preferences> {
    InstallationHistory.capture(context)
    return context.applicationContext.shellStore
}

class DockFullException : IOException("Dock is full")

class ShellRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(shellStoreFor(context))
    private val version = intPreferencesKey("schema_version")
    private val favorites = stringSetPreferencesKey("favorites")
    private val wallpaper = stringPreferencesKey("wallpaper_uri")
    private val sidebar = stringPreferencesKey("sidebar_items")
    private val overlayEnabled = booleanPreferencesKey("overlay_enabled")
    private val overlayPosition = intPreferencesKey("overlay_position")
    private val overlayHeight = intPreferencesKey("overlay_height")
    private val overlayInset = intPreferencesKey("overlay_inset")
    val settings = store.data.map(::decode).flowOn(Dispatchers.Default)

    suspend fun setOverlay(value: OverlaySettings) {
        val safe = value.validated()
        store.edit { data ->
            val current = decode(data) // Refuse unknown schemas; leave all existing records intact.
            ShellPresentationCodec.write(data, current.presentation)
            data[overlayEnabled] = safe.enabled
            data[overlayPosition] = safe.positionPercent
            data[overlayHeight] = safe.heightDp
            data[overlayInset] = safe.insetDp
        }
    }

    suspend fun setPresentation(value: ShellPresentation) {
        store.edit { data -> decode(data); ShellPresentationCodec.write(data, value) }
    }

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
    suspend fun placeSidebarBefore(id: String, before: String?) = updateSidebar { placeItemBefore(it, id, before) }
    suspend fun reorderSidebar(id: String, delta: Int) = updateSidebar { items ->
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) items else items.toMutableList().apply {
            add((index.toLong() + delta).coerceIn(0, lastIndex.toLong()).toInt(), removeAt(index))
        }
    }
    private suspend fun updateSidebar(transform: (List<WorkspaceItem>) -> List<WorkspaceItem>) {
        store.edit { data ->
            val current = decode(data)
            data[sidebar] = WorkspaceCodec.encodeItems(transform(current.sidebar))
            ShellPresentationCodec.write(data, current.presentation)
        }
    }

    suspend fun setFavorite(component: String, enabled: Boolean) {
        store.edit { preferences ->
            val current = decode(preferences)
            val apps = current.favorites
            if (enabled && component !in apps && apps.size >= MAX_DOCK_APPS) throw DockFullException()
            preferences[favorites] = if (enabled) apps + component else apps - component
            ShellPresentationCodec.write(preferences, current.presentation)
        }
    }

    suspend fun setWallpaper(uri: String?) {
        store.edit { preferences ->
            val current = decode(preferences) // Never overwrite a newer schema.
            if (uri == null) preferences.remove(wallpaper) else preferences[wallpaper] = uri
            ShellPresentationCodec.write(preferences, current.presentation)
        }
    }

    suspend fun removePackages(packages: Set<String>) {
        store.edit { preferences ->
            val current = decode(preferences)
            preferences[favorites] = current.favorites.filterNot { it.substringBefore('/') in packages }.toSet()
            preferences[sidebar] = WorkspaceCodec.encodeItems(current.sidebar.filterNot {
                it is WorkspaceItem.AppShortcut && it.componentId.substringBefore('/') in packages
            })
            ShellPresentationCodec.write(preferences, current.presentation)
        }
    }

    private fun decode(preferences: Preferences): ShellSettings {
        if ((preferences[version] ?: 1) !in 1..3) throw IOException("Unsupported shell schema")
        return ShellSettings(
            favorites = preferences[favorites].orEmpty().toSet(),
            wallpaperUri = preferences[wallpaper],
            sidebar = try { WorkspaceCodec.decodeItems(preferences[sidebar] ?: "[]") }
                catch (error: JSONException) { throw IOException("Invalid sidebar records", error) },
            presentation = ShellPresentationCodec.read(preferences),
            overlay = OverlaySettings(preferences[overlayEnabled] ?: false, preferences[overlayPosition] ?: 50,
                preferences[overlayHeight] ?: 160, preferences[overlayInset] ?: 0).validated(),
        )
    }
}
