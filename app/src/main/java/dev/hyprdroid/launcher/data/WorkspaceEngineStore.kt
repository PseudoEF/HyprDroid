package dev.hyprdroid.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import kotlinx.coroutines.flow.first

private val Context.workspaceEngineStore by preferencesDataStore(
    name = "workspace_engine",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal class WorkspaceEngineStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.workspaceEngineStore)

    suspend fun save(workspaceId: String, engine: WorkspaceEngine) {
        store.edit { preferences ->
            preferences[stringPreferencesKey("engine/$workspaceId")] = engine.serialize()
        }
    }

    suspend fun isOpen(workspaceId: String): Boolean =
        store.data.first()[booleanPreferencesKey("tiling/$workspaceId/open")] ?: false

    /** All workspace ids whose tiling surface was left open before the process restarted. */
    suspend fun openWorkspaces(): Set<String> {
        val preferences = store.data.first()
        return preferences.asMap().entries.mapNotNull { (key, value) ->
            val name = key.name
            if (value != true || !name.startsWith("tiling/") || !name.endsWith("/open")) return@mapNotNull null
            name.removePrefix("tiling/").removeSuffix("/open")
        }.toSet()
    }

    suspend fun setOpen(workspaceId: String, open: Boolean) {
        store.edit { preferences ->
            preferences[booleanPreferencesKey("tiling/$workspaceId/open")] = open
        }
    }

    suspend fun load(): Map<String, WorkspaceEngine> {
        val preferences = store.data.first()
        val engines = mutableMapOf<String, WorkspaceEngine>()
        for (entry in preferences.asMap().entries) {
            val key = entry.key.name
            if (key.startsWith("engine/")) {
                engines[key.substring("engine/".length)] = WorkspaceEngine().deserialize(entry.value as String)
            }
        }
        return engines
    }
}
