package dev.hyprdroid.launcher.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.*
import dev.hyprdroid.launcher.model.*
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException

/** v2 retains v1 keys as a migration snapshot; all new writes use ordered typed records. */
internal object WorkspaceCodec {
    val schema = intPreferencesKey("schema_version")
    private val selected = intPreferencesKey("selected_workspace")
    private val legacyGrid = booleanPreferencesKey("grid_layout")
    private fun key(id: Int, suffix: String) = "workspace_${id}_$suffix"

    fun decode(data: Preferences): WorkspaceSettings {
        val version = data[schema] ?: 1
        if (version !in 1..2) throw IOException("Unsupported workspace schema: $version")
        return try {
            WorkspaceSettings(
                workspaces = List(WORKSPACE_COUNT) { id ->
                    if (version == 1) Workspace(
                        id, data[stringSetPreferencesKey(key(id, "apps"))].orEmpty()
                            .filter(::validComponent).sorted().map { WorkspaceItem.App(it) },
                        WorkspaceAppearance(gridLayout = data[legacyGrid] ?: true),
                    ) else Workspace(
                        id, decodeItems(data[stringPreferencesKey(key(id, "items"))] ?: "[]"),
                        WorkspaceAppearance(
                            gridLayout = data[booleanPreferencesKey(key(id, "grid"))] ?: true,
                            iconSizeDp = data[intPreferencesKey(key(id, "icon_size"))] ?: 48,
                            density = GridDensity.entries.find { it.name == data[stringPreferencesKey(key(id, "density"))] } ?: GridDensity.COMPACT,
                            spacingDp = data[intPreferencesKey(key(id, "spacing"))] ?: 8,
                        ).validated(),
                    )
                },
                selectedWorkspace = (data[selected] ?: 0).coerceIn(0, WORKSPACE_COUNT - 1),
            )
        } catch (error: JSONException) { throw IOException("Invalid workspace records", error) }
    }

    fun encode(settings: WorkspaceSettings, data: MutablePreferences) {
        data[schema] = 2
        data[selected] = settings.selectedWorkspace
        settings.workspaces.forEach { workspace ->
            val id = workspace.id
            val array = JSONArray()
            workspace.items.forEach { item -> array.put(when (item) {
                is WorkspaceItem.App -> JSONObject().put("type", "app").put("id", item.id).put("component", item.componentId)
                is WorkspaceItem.Unsupported -> JSONObject(item.record)
            }) }
            data[stringPreferencesKey(key(id, "items"))] = array.toString()
            data[booleanPreferencesKey(key(id, "grid"))] = workspace.appearance.gridLayout
            data[intPreferencesKey(key(id, "icon_size"))] = workspace.appearance.iconSizeDp
            data[stringPreferencesKey(key(id, "density"))] = workspace.appearance.density.name
            data[intPreferencesKey(key(id, "spacing"))] = workspace.appearance.spacingDp
        }
    }

    private fun decodeItems(raw: String): List<WorkspaceItem> {
        val array = JSONArray(raw)
        val items = List(array.length()) { index ->
            val record = array.getJSONObject(index)
            val id = record.getString("id")
            if (id.isBlank()) throw IOException("Blank workspace item ID")
            if (record.getString("type") == "app") {
                val component = record.getString("component")
                if (!validComponent(component) || id != "app:$component") throw IOException("Invalid application identity")
                WorkspaceItem.App(component)
            } else WorkspaceItem.Unsupported(id, record.toString())
        }
        if (items.map { it.id }.distinct().size != items.size) throw IOException("Duplicate workspace item IDs")
        return items
    }
}

class WorkspaceV2Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = (currentData[WorkspaceCodec.schema] ?: 1) == 1
    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().also {
        WorkspaceCodec.encode(WorkspaceCodec.decode(currentData), it)
    }.toPreferences()
    override suspend fun cleanUp() = Unit
}
