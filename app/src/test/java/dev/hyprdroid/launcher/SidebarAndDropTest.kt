package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SidebarAndDropTest {
    @get:Rule val temporary = TemporaryFolder()
    private val a = WorkspaceItem.AppShortcut("example.a/.Main")
    private val b = WorkspaceItem.AppShortcut("example.b/.Main")
    private val c = WorkspaceItem.AppShortcut("example.c/.Main", "public-shortcut")

    @Test fun dropKeepsConcurrentItemsAndDoesNotResurrectRemovedApps() {
        val widget = WorkspaceItem.WidgetPlaceholder("widget:test", "Clock")
        val items = listOf(a, widget, b, c)
        assertEquals(listOf(widget, b, a, c), placeItemBefore(items, a.id, c.id))
        assertEquals(items, placeItemBefore(items, "removed", b.id))
        assertEquals(items, placeItemBefore(items, a.id, "missing-anchor"))
        assertEquals(listOf(widget, b, c, a), placeItemBefore(items, a.id, null))
        assertTrue(placeItemBefore(emptyList(), a.id, null).isEmpty())
    }

    @Test fun schemaOneKeepsDockWallpaperAndSidebarSurvivesRecreation() = runBlocking {
        val file = temporary.newFolder().resolve("shell.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = first, produceFile = { file })
            store.edit {
                it[intPreferencesKey("schema_version")] = 1
                it[stringSetPreferencesKey("favorites")] = setOf(a.componentId)
                it[stringPreferencesKey("wallpaper_uri")] = "content://image/one"
            }
            val repo = ShellRepository(store)
            assertTrue(repo.settings.first().sidebar.isEmpty())
            for (item in listOf(a, b, c)) repo.setSidebarItem(item, true)
            repo.setSidebarItem(c, true)
            repo.reorderSidebar(c.id, -2)
            repo.removePackages(setOf("example.b"))
        } finally { first.coroutineContext[Job]!!.cancelAndJoin() }
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = ShellRepository(PreferenceDataStoreFactory.create(scope = next, produceFile = { file }))
            val state = repo.settings.first()
            assertEquals(listOf(c, a), state.sidebar)
            assertEquals(setOf(a.componentId), state.favorites)
            assertEquals("content://image/one", state.wallpaperUri)
            repo.removeSidebarItem(c.id)
            repo.removeSidebarItem(a.id)
            repo.reorderSidebar(a.id, 1)
            assertTrue(repo.settings.first().sidebar.isEmpty())
        } finally { next.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun corruptOrNewerSidebarDoesNotOverwriteRecords() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = temporary.newFolder().resolve("shell.preferences_pb")
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val key = stringPreferencesKey("sidebar_items")
            val repo = ShellRepository(store)
            for (schema in listOf(2, 4)) {
                store.edit { it[intPreferencesKey("schema_version")] = schema; it[key] = "broken" }
                try { repo.setFavorite(a.componentId, true); fail("Must reject invalid storage") } catch (_: IOException) { }
                assertEquals("broken", store.data.first()[key])
            }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun widgetAndUnknownPayloadRoundTripAsData() {
        val records = listOf(a, c, WorkspaceItem.WidgetPlaceholder("widget:clock", "Часы"),
            WorkspaceItem.Unsupported("future:test", "{\"type\":\"future\",\"id\":\"future:test\",\"script\":\"never execute\"}"))
        val result = WorkspaceCodec.decodeItems(WorkspaceCodec.encodeItems(records))
        assertEquals(records.take(3), result.take(3))
        assertEquals("never execute", org.json.JSONObject((result.last() as WorkspaceItem.Unsupported).record).getString("script"))
    }
}
