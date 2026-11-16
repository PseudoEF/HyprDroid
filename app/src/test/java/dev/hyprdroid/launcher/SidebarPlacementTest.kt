package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SidebarPlacementTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun dropUsesLatestRecordsPreservesShortcutPayloadAndSurvivesRestart() = runBlocking {
        val file = directory.newFolder().resolve("shell.preferences_pb")
        val a = WorkspaceItem.AppShortcut("example.app/.Main")
        val b = WorkspaceItem.AppShortcut("example.app/.Main", "public-shortcut-id")
        val c = WorkspaceItem.AppShortcut("example.other/.Main")
        val future = WorkspaceItem.Unsupported("module:status", "{\"type\":\"future\",\"id\":\"module:status\",\"payload\":\"keep\"}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            store.edit {
                ShellPresentationCodec.write(it, ShellPresentation(DesktopMode.CLASSIC, DockVisibility.HIDDEN))
                it[stringPreferencesKey("sidebar_items")] = WorkspaceCodec.encodeItems(listOf(a, b, future))
            }
            val repository = ShellRepository(store)
            repository.setFavorite(a.componentId, true)
            repository.setWallpaper("content://keep")
            repository.setSidebarItem(c, true) // An insertion during a draft must not be lost.
            repository.placeSidebarBefore(b.id, a.id)
            assertEquals(listOf(b, a, future, c), repository.settings.first().sidebar)
            repository.removeSidebarItem(a.id)
            repository.placeSidebarBefore(b.id, a.id) // Stale anchor: no resurrection or destructive replacement.
            repository.placeSidebarBefore("removed", c.id)
            assertEquals(listOf(b, future, c), repository.settings.first().sidebar)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = next, produceFile = { file }))
            val saved = repository.settings.first()
            assertEquals(listOf(b, future, c), saved.sidebar)
            assertEquals(setOf(a.componentId), saved.favorites)
            assertEquals("content://keep", saved.wallpaperUri)
            assertEquals(ShellPresentation(DesktopMode.CLASSIC, DockVisibility.HIDDEN), saved.presentation)
        } finally { next.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
