package dev.hyprdroid.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.hyprdroid.launcher.model.WORKSPACE_COUNT
import dev.hyprdroid.launcher.model.Workspace
import dev.hyprdroid.launcher.model.WorkspaceSettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.workspaceStore by preferencesDataStore(
    name = "workspaces",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** All mutations read the latest persisted state inside DataStore's atomic transaction. */
class WorkspaceRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.workspaceStore)

    private val schema = intPreferencesKey("schema_version")
    private val selected = intPreferencesKey("selected_workspace")
    private val grid = booleanPreferencesKey("grid_layout")
    private fun pins(id: Int) = stringSetPreferencesKey("workspace_${id}_apps")

    val settings: Flow<WorkspaceSettings> = store.data.map(::decode)

    suspend fun setPinned(workspaceId: Int, componentId: String, pinned: Boolean) = update {
        it.withPinned(workspaceId, componentId, pinned)
    }

    suspend fun removePackages(packages: Set<String>) = update { it.withoutPackages(packages) }

    suspend fun selectWorkspace(id: Int) {
        require(id in 0 until WORKSPACE_COUNT)
        update { it.copy(selectedWorkspace = id) }
    }

    suspend fun setGridLayout(enabled: Boolean) = update { it.copy(gridLayout = enabled) }

    private suspend fun update(transform: (WorkspaceSettings) -> WorkspaceSettings) {
        store.edit { preferences ->
            val result = transform(decode(preferences))
            preferences[schema] = 1
            preferences[selected] = result.selectedWorkspace
            preferences[grid] = result.gridLayout
            result.workspaces.forEach { preferences[pins(it.id)] = it.pinnedApps }
        }
    }

    private fun decode(preferences: Preferences): WorkspaceSettings {
        val version = preferences[schema] ?: 1
        if (version != 1) throw IOException("Unsupported workspace schema: $version")
        return WorkspaceSettings(
            workspaces = List(WORKSPACE_COUNT) { id ->
                Workspace(id, preferences[pins(id)].orEmpty().filter { component ->
                    component.substringBefore('/').isNotBlank() && component.substringAfter('/', "").isNotBlank()
                }.toSet())
            },
            selectedWorkspace = (preferences[selected] ?: 0).coerceIn(0, WORKSPACE_COUNT - 1),
            gridLayout = preferences[grid] ?: true,
        )
    }
}
