package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.*
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.data.WorkspaceV2Migration
import dev.hyprdroid.launcher.model.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceV2Test {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val a = "example.a/.Main"
    private val b = "example.b/.Main"
    private val c = "example.c/.Main"

    @Test fun migrateV1PreservesPinsSelectionAndLayoutInEveryWorkspace() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("workspaces.preferences_pb")
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = oldScope, produceFile = { file })
            store.edit {
                it[intPreferencesKey("schema_version")] = 1
                it[intPreferencesKey("selected_workspace")] = 2
                it[booleanPreferencesKey("grid_layout")] = false
                it[stringSetPreferencesKey("workspace_0_apps")] = setOf(b, a)
                it[stringSetPreferencesKey("workspace_1_apps")] = setOf(c)
            }
        } finally { oldScope.coroutineContext[Job]!!.cancelAndJoin() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(migrations = listOf(WorkspaceV2Migration()), scope = scope, produceFile = { file })
            val state = WorkspaceRepository(store).settings.first()
            assertEquals(2, state.selectedWorkspace)
            assertEquals(listOf(a, b), state.workspaces[0].items.filterIsInstance<WorkspaceItem.AppShortcut>().map { it.componentId })
            assertEquals(setOf(c), state.workspaces[1].pinnedApps)
            assertTrue(state.workspaces[2].items.isEmpty())
            assertTrue(state.workspaces.all { !it.appearance.gridLayout })
            assertEquals(2, store.data.first()[intPreferencesKey("schema_version")])
            assertEquals(setOf(a, b), store.data.first()[stringSetPreferencesKey("workspace_0_apps")])
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun reorderMoveAndIndependentAppearanceSurviveRestart() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("workspaces.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val appearance = WorkspaceAppearance(false, 64, GridDensity.RELAXED, 20)
        try {
            val repo = WorkspaceRepository(PreferenceDataStoreFactory.create(scope = first, produceFile = { file }))
            for (app in listOf(a, b, c)) repo.setPinned(0, app, true)
            repo.reorder(0, "app:$c", -2)
            repo.setPinned(1, b, true)
            repo.moveItem(0, 1, "app:$b") // Existing target pin is retained once, at its old position.
            repo.setAppearance(0, appearance)
        } finally { first.coroutineContext[Job]!!.cancelAndJoin() }
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val state = WorkspaceRepository(PreferenceDataStoreFactory.create(scope = next, produceFile = { file })).settings.first()
            assertEquals(listOf("app:$c", "app:$a"), state.workspaces[0].items.map { it.id })
            assertEquals(listOf("app:$b"), state.workspaces[1].items.map { it.id })
            assertEquals(appearance, state.workspaces[0].appearance)
            assertEquals(WorkspaceAppearance(), state.workspaces[1].appearance)
        } finally { next.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun futureItemsRoundTripAndConflictingMoveDoesNotLoseEitherRecord() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("workspaces.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val source = JSONObject().put("id", "custom:one").put("type", "future-widget").put("payload", JSONObject().put("label", "source"))
            val target = JSONObject(source.toString()).put("payload", JSONObject().put("label", "target"))
            store.edit {
                it[intPreferencesKey("schema_version")] = 2
                it[stringPreferencesKey("workspace_0_items")] = JSONArray().put(source).toString()
                it[stringPreferencesKey("workspace_1_items")] = JSONArray().put(target).toString()
            }
            val repo = WorkspaceRepository(store)
            repo.setPinned(0, a, true)
            repo.reorder(0, "app:$a", -1)
            try { repo.moveItem(0, 1, "custom:one"); fail("Expected a non-destructive conflict") } catch (_: WorkspaceMoveConflictException) { }
            repo.removePackages(setOf("example.a"))
            val state = repo.settings.first()
            val raw = (state.workspaces[0].items.single() as WorkspaceItem.Unsupported).record
            assertEquals("source", JSONObject(raw).getJSONObject("payload").getString("label"))
            val other = (state.workspaces[1].items.single() as WorkspaceItem.Unsupported).record
            assertEquals("target", JSONObject(other).getJSONObject("payload").getString("label"))
            repo.moveItem(0, 2, "custom:one")
            assertEquals("custom:one", repo.settings.first().workspaces[2].items.single().id)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun unsupportedSchemaAndMalformedRecordsAreNotOverwritten() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("workspaces.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val repo = WorkspaceRepository(store)
            store.edit { it[intPreferencesKey("schema_version")] = 3 }
            try { repo.setPinned(0, a, true); fail("Unknown schema must be read-only") } catch (_: IOException) { }
            assertEquals(3, store.data.first()[intPreferencesKey("schema_version")])
            store.edit {
                it[intPreferencesKey("schema_version")] = 2
                it[stringPreferencesKey("workspace_0_items")] = "broken-json"
            }
            try { repo.setPinned(0, a, true); fail("Malformed data must be retained") } catch (_: IOException) { }
            assertEquals("broken-json", store.data.first()[stringPreferencesKey("workspace_0_items")])
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun concurrentMovesAndPinsRemainSerializable() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("workspaces.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = WorkspaceRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            repo.setPinned(0, a, true)
            coroutineScope {
                launch { repo.moveItem(0, 1, "app:$a") }
                repeat(15) { n -> launch { repo.setPinned(0, "example.extra$n/.Main", true) } }
            }
            val state = repo.settings.first()
            assertEquals(15, state.workspaces[0].items.size)
            assertEquals(setOf(a), state.workspaces[1].pinnedApps)
            assertEquals(15, state.workspaces[0].items.map { it.id }.distinct().size)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
