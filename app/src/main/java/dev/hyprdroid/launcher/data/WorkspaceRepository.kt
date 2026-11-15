package dev.hyprdroid.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn

private val Context.workspaceStore by preferencesDataStore(
    name = "workspaces",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { listOf(WorkspaceV2Migration()) },
)

class WorkspaceRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.workspaceStore)
    val settings = store.data.map(WorkspaceCodec::decode).flowOn(Dispatchers.Default)

    suspend fun setPinned(workspaceId: Int, componentId: String, pinned: Boolean) = update { it.withPinned(workspaceId, componentId, pinned) }
    suspend fun removePackages(packages: Set<String>) = update { it.withoutPackages(packages) }
    suspend fun removeItem(workspace: Int, id: String) = update { it.removeItem(workspace, id) }
    suspend fun reorder(workspace: Int, id: String, delta: Int) = update { it.reorder(workspace, id, delta) }
    suspend fun placeBefore(workspace: Int, id: String, before: String?) = update { it.placeBefore(workspace, id, before) }
    suspend fun moveItem(from: Int, to: Int, id: String) = update { it.moveItem(from, to, id) }
    suspend fun setAppearance(workspace: Int, appearance: WorkspaceAppearance) = update { it.withAppearance(workspace, appearance) }
    suspend fun selectWorkspace(id: Int) {
        require(id in 0 until WORKSPACE_COUNT)
        update { it.copy(selectedWorkspace = id) }
    }
    suspend fun setGridLayout(enabled: Boolean) = update {
        it.withAppearance(it.selectedWorkspace, it.workspaces[it.selectedWorkspace].appearance.copy(gridLayout = enabled))
    }
    suspend fun setGridLayout(workspace: Int, enabled: Boolean) = update {
        it.withAppearance(workspace, it.workspaces[workspace].appearance.copy(gridLayout = enabled))
    }

    private suspend fun update(transform: (WorkspaceSettings) -> WorkspaceSettings) {
        store.edit { data -> WorkspaceCodec.encode(transform(WorkspaceCodec.decode(data)), data) }
    }
}
