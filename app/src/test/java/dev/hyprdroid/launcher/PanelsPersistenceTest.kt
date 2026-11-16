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
import java.io.IOException

class PanelsPersistenceTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun legacyOverlayAndShortcutsSurviveIndependentPanelEditsAndRestart() = runBlocking {
        val file = directory.newFolder().resolve("shell.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val shortcut = WorkspaceItem.AppShortcut("example.app/.Main", "public-id")
        val left = PanelSettings(PanelScope.APPS, 7, 42, 180, 70, 18, MarkerVisibility.HIDDEN, listOf("BATTERY", "CLOCK", "DATE"))
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            repository.setPresentation(ShellPresentation(DesktopMode.CLASSIC, DockVisibility.AUTO, setOf(SidebarModule.QUICK_LAUNCH)))
            repository.setOverlay(OverlaySettings(true, 67, 144, 24))
            repository.setSidebarItem(shortcut, true)
            repository.setFavorite(shortcut.componentId, true)
            repository.setWallpaper("content://keep")
            val legacy = repository.settings.first()
            assertNull(legacy.panelConfiguration)
            assertEquals(PanelScope.EVERYWHERE, legacy.panels.right.scope)
            assertEquals(144, legacy.panels.right.heightDp)
            assertEquals(67, legacy.panels.right.positionPercent)
            assertEquals(listOf("QUICK_LAUNCH"), legacy.panels.right.modules)
            repository.setPanel(PanelSide.LEFT, left)
            assertEquals(legacy.panels.right, repository.settings.first().panels.right)
            val right = legacy.panels.right.copy(modules = listOf("QUICK_LAUNCH", "future:local-widget", "SYSTEM_STATUS"))
            repository.setPanel(PanelSide.RIGHT, right)
            repository.reorderPanelModule(PanelSide.RIGHT, "SYSTEM_STATUS", -1)
            assertEquals(listOf("QUICK_LAUNCH", "SYSTEM_STATUS", "future:local-widget"), repository.settings.first().panels.right.modules)
            repository.setPresentation(legacy.presentation) // Legacy module toggle must retain unknown IDs and ordering.
            assertEquals(listOf("QUICK_LAUNCH", "future:local-widget"), repository.settings.first().panels.right.modules)
            assertEquals(legacy.overlay, repository.settings.first().overlay)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val saved = ShellRepository(PreferenceDataStoreFactory.create(scope = next, produceFile = { file })).settings.first()
            assertEquals(left, saved.panels.left)
            assertEquals(listOf(shortcut), saved.sidebar)
            assertEquals(setOf(shortcut.componentId), saved.favorites)
            assertEquals("content://keep", saved.wallpaperUri)
            assertTrue(saved.overlay.enabled)
            assertEquals(listOf("QUICK_LAUNCH", "future:local-widget"), saved.panels.right.modules)
        } finally { next.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun concurrentSideEditsRetainEachOtherAndBoundsAreClamped() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = directory.newFolder().resolve("shell.preferences_pb")
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            coroutineScope {
                launch { repository.setPanel(PanelSide.LEFT, PanelSettings(PanelScope.APPS, markerWidthDp = 99, touchWidthDp = -1)) }
                launch { repository.setPanel(PanelSide.RIGHT, PanelSettings(PanelScope.EVERYWHERE, visibility = MarkerVisibility.HIDDEN)) }
            }
            val saved = repository.settings.first()
            assertEquals(PanelScope.APPS, saved.panels.left.scope)
            assertEquals(12, saved.panels.left.markerWidthDp)
            assertEquals(12, saved.panels.left.touchWidthDp)
            assertEquals(PanelScope.EVERYWHERE, saved.panels.right.scope)
            assertEquals(MarkerVisibility.HIDDEN, saved.panels.right.visibility)
            assertFalse(saved.overlay.enabled)
            assertEquals(DesktopMode.CLEAN, saved.presentation.desktopMode)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun unknownPanelSchemaIsNeverOverwritten() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = directory.newFolder().resolve("shell.preferences_pb")
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val raw = """{"version":99,"payload":"keep"}"""
            store.edit { it[stringPreferencesKey("panels_configuration")] = raw }
            val repository = ShellRepository(store)
            try { repository.setOverlay(OverlaySettings()); fail("Unknown configuration must be preserved") } catch (_: IOException) { }
            assertEquals(raw, store.data.first()[stringPreferencesKey("panels_configuration")])
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
