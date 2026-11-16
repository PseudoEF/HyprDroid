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
import dev.hyprdroid.launcher.model.PanelSide
import dev.hyprdroid.launcher.model.PanelSettings
import dev.hyprdroid.launcher.model.SidebarModule
import dev.hyprdroid.launcher.model.PendingPanelWidget
import dev.hyprdroid.launcher.model.WidgetBindingStage
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
    private val panels = stringPreferencesKey("panels_configuration")
    private val pendingWidget = stringPreferencesKey("pending_panel_widget")
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
            current.panelConfiguration?.let { configuration ->
                if (safe.positionPercent != current.overlay.positionPercent || safe.heightDp != current.overlay.heightDp || safe.insetDp != current.overlay.insetDp) {
                    data[panels] = PanelsCodec.encode(configuration.copy(right = configuration.right.copy(
                        positionPercent = safe.positionPercent, heightDp = safe.heightDp, insetDp = safe.insetDp)))
                }
            }
        }
    }

    suspend fun setPresentation(value: ShellPresentation) {
        store.edit { data ->
            val current = decode(data)
            current.panelConfiguration?.let { configuration ->
                val old = configuration.right.modules
                val legacy = SidebarModule.entries.map { it.name }.toSet()
                val selected = value.sidebarModules.map { it.name }
                val ordered = old.filter { it !in legacy || it in selected } + selected.filter { it !in old }
                data[panels] = PanelsCodec.encode(configuration.copy(right = configuration.right.copy(modules = ordered)))
            }
            ShellPresentationCodec.write(data, value)
        }
    }

    suspend fun setPanel(side: PanelSide, value: PanelSettings, previous: PanelSettings? = null) {
        store.edit { data ->
            val current = decode(data)
            // Retain widgets added after this UI draft was opened, including late configuration results.
            val added = if (previous == null) emptyList() else current.panels[side].widgets.filter { widget -> previous.widgets.none { it.appWidgetId == widget.appWidgetId } && value.widgets.none { it.appWidgetId == widget.appWidgetId } }
            val merged = value.copy(widgets = value.widgets + added, modules = value.modules + added.map { it.moduleId })
            val next = current.panels.with(side, merged.validated())
            data[panels] = PanelsCodec.encode(next)
            ShellPresentationCodec.write(data, current.presentation.copy(sidebarModules = SidebarModule.entries.filter { it.name in next.right.modules }.toSet()))
        }
    }
    suspend fun savePendingWidget(value: PendingPanelWidget) {
        store.edit { data ->
            val current = decode(data)
            if (current.pendingWidget?.widget?.appWidgetId?.let { it != value.widget.appWidgetId } == true) throw IOException("Another widget is pending")
            data[pendingWidget] = PanelsCodec.encodePending(value)
        }
    }
    suspend fun cancelPendingWidget(id: Int) {
        store.edit { data -> if (decode(data).pendingWidget?.widget?.appWidgetId == id) data.remove(pendingWidget) }
    }
    suspend fun commitPendingWidget(id: Int) {
        store.edit { data ->
            val current = decode(data)
            val pending = current.pendingWidget?.takeIf { it.widget.appWidgetId == id && it.stage == WidgetBindingStage.COMMIT } ?: return@edit
            val panel = current.panels[pending.side]
            val existing = panel.widgets.find { it.appWidgetId == id }
            if (existing != null && existing != pending.widget) throw IOException("Conflicting widget identity")
            if (existing == null) {
                if (pending.widget.moduleId in panel.modules) throw IOException("Conflicting module identity")
                val next = panel.copy(modules = panel.modules + pending.widget.moduleId, widgets = panel.widgets + pending.widget).validated()
                data[panels] = PanelsCodec.encode(current.panels.with(pending.side, next))
            }
            data.remove(pendingWidget)
            ShellPresentationCodec.write(data, current.presentation)
        }
    }
    suspend fun reorderPanelModule(side: PanelSide, id: String, delta: Int) {
        store.edit { data ->
            val current = decode(data)
            val panel = current.panels[side]
            val index = panel.modules.indexOf(id)
            if (index >= 0) {
                val order = panel.modules.toMutableList().apply { add((index + delta).coerceIn(0, lastIndex), removeAt(index)) }
                data[panels] = PanelsCodec.encode(current.panels.with(side, panel.copy(modules = order)))
                ShellPresentationCodec.write(data, current.presentation)
            }
        }
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
            panelConfiguration = preferences[panels]?.let(PanelsCodec::decode),
            pendingWidget = preferences[pendingWidget]?.let(PanelsCodec::decodePending),
        )
    }
}
