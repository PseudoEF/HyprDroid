package dev.hyprdroid.launcher.data

import android.content.Context
import android.util.Log
import dev.hyprdroid.launcher.BuildConfig
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.hyprdroid.launcher.plugins.TILE_STATE_LIMIT
import dev.hyprdroid.launcher.plugins.TileStateV2
import dev.hyprdroid.launcher.plugins.TileStateValue
import dev.hyprdroid.launcher.plugins.isPluginText
import dev.hyprdroid.launcher.workspaceengine.WindowId
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

private val Context.workspaceEngineStore by preferencesDataStore(
    name = "workspace_engine",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal class WorkspaceEngineStore(
    private val store: DataStore<Preferences>,
    private val traceState: (String) -> Unit = {},
) {
    constructor(context: Context) : this(context.applicationContext.workspaceEngineStore, { message ->
        if (BuildConfig.V2_FIXTURE_LUA) Log.d("HyprLuaTile", "v2 state $message")
    })

    suspend fun save(workspaceId: String, engine: WorkspaceEngine, removedTile: WindowId? = null) {
        val serialized = engine.serialize()
        require(removedTile == null || removedTile !in engine.windowsById())
        store.edit { preferences ->
            if (removedTile != null) {
                preferences.asMap().keys.filter { key ->
                    key.name.startsWith(TILE_STATE_PREFIX) && key.name.substringAfterLast('/') == removedTile.value
                }.forEach { preferences.remove(stringPreferencesKey(it.name)) }
            }
            preferences[stringPreferencesKey("engine/$workspaceId")] = serialized
        }
    }

    suspend fun tileState(slot: TileStateSlot): String = readState(store.data.first(), slot)

    suspend fun loadTileStateV2(slot: TileStateSlot, diagnose: Boolean = false): Map<String, TileStateValue> {
        require(slot.schema == 2)
        val raw = tileState(slot)
        val state = TileStateV2.decode(raw)
        if (diagnose) traceState(if (raw.isEmpty()) "restore: slot missing" else "restore: slot present")
        return state
    }

    /** Uses the existing owner check and compare-and-set transaction, with a separate schema slot. */
    suspend fun saveTileStateV2(
        slot: TileStateSlot,
        expected: Map<String, TileStateValue>,
        updated: Map<String, TileStateValue>,
        authorized: () -> Boolean,
    ) {
        require(slot.schema == 2)
        fun serialize(values: Map<String, TileStateValue>) = if (values.isEmpty()) "" else TileStateV2.encode(values)
        val scope = currentCoroutineContext()
        writeTileState(slot, serialize(expected), serialize(updated)) { scope.isActive && authorized() }
        if (expected != updated) traceState("durable commit")
    }

    /** Called only after Package Manager records an explicitly authorized fixture transition. */
    suspend fun migrateFixtureState(packageId: String) {
        require(stateIdentifier.matches(packageId))
        val prefix = "$TILE_STATE_PREFIX$packageId/2/"
        store.edit { preferences ->
            for ((key, value) in preferences.asMap()) {
                if (!key.name.startsWith(prefix) || value !is String) continue
                val id = key.name.removePrefix(prefix)
                if (!stateIdentifier.matches(id)) continue
                TileStateV2.decode(value)
                val target = stringPreferencesKey(TileStateSlot(packageId, 2, WindowId(id), "github-v1").preferenceKey)
                if (preferences[target] == null) preferences[target] = value
                preferences.remove(stringPreferencesKey(key.name))
            }
        }
    }

    /** Core-only reset. The caller supplies the tile slot after explicit user consent. */
    suspend fun clearTileStateV2(slot: TileStateSlot) {
        require(slot.schema == 2)
        store.edit { it.remove(stringPreferencesKey(slot.preferenceKey)) }
    }

    /** Checks ownership and commits the state in the same transaction as layout deletion. */
    suspend fun saveTileState(slot: TileStateSlot, expected: String, updated: String, authorized: () -> Boolean = { true }) {
        if (updated != expected) writeTileState(slot, expected, updated, authorized)
    }

    private suspend fun writeTileState(slot: TileStateSlot, expected: String, updated: String, authorized: () -> Boolean) {
        if (!validState(updated)) throw IOException("Lua Tile state exceeds its limits")
        store.edit { preferences ->
            val belongsToPackage = preferences.asMap().entries.any { (key, value) ->
                key.name.startsWith("engine/") && value is String &&
                    WorkspaceEngine().deserialize(value).windowsById()[slot.windowId]?.moduleId == slot.packageId
            }
            if (!authorized() || !belongsToPackage || readState(preferences, slot) != expected) {
                if (slot.schema == 2) traceState("commit rejected: stale or revoked")
                throw IOException("Lua Tile state changed or its tile was removed")
            }
            if (updated == expected) return@edit
            val key = stringPreferencesKey(slot.preferenceKey)
            if (updated.isEmpty()) {
                preferences.remove(key)
            } else {
                if (preferences[key] == null && preferences.asMap().keys.count { it.name.startsWith(TILE_STATE_PREFIX) } >= MAX_TILE_STATES) {
                    throw IOException("Lua Tile state storage is full")
                }
                preferences[key] = updated
            }
        }
    }

    private fun readState(preferences: Preferences, slot: TileStateSlot): String {
        val value = preferences[stringPreferencesKey(slot.preferenceKey)].orEmpty()
        if (!validState(value)) throw IOException("Invalid saved Lua Tile state")
        return value
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

private const val TILE_STATE_PREFIX = "module-state/v1/"
private const val MAX_TILE_STATES = 64
private val stateIdentifier = Regex("[a-zA-Z0-9_.-]{1,80}")

/** Core resolves this key itself. Lua and Binder never receive it. */
internal data class TileStateSlot(val packageId: String, val schema: Int, val windowId: WindowId, val publisher: String) {
    constructor(packageId: String, schema: Int, windowId: WindowId) : this(packageId, schema, windowId, "")
    init {
        require(stateIdentifier.matches(packageId) && stateIdentifier.matches(windowId.value) && schema > 0)
    }

    init { require(publisher.isEmpty() || stateIdentifier.matches(publisher)) }

    val preferenceKey = "$TILE_STATE_PREFIX$packageId/$schema/" +
        (if (publisher.isEmpty()) "" else "$publisher/") + windowId.value
}

private fun validState(value: String) = value.isPluginText(TILE_STATE_LIMIT) &&
    value.encodeToByteArray().size <= TILE_STATE_LIMIT
