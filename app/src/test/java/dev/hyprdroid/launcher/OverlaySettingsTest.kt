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

class OverlaySettingsTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun defaultsAreDisabledAndGeometrySurvivesRestartWithoutChangingShellRecords() = runBlocking {
        val file = directory.newFolder().resolve("shell.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val shortcut = WorkspaceItem.AppShortcut("example.app/.Main", "keep-id")
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            assertEquals(OverlaySettings(), repository.settings.first().overlay)
            repository.setSidebarItem(shortcut, true)
            repository.setFavorite(shortcut.componentId, true)
            repository.setWallpaper("content://keep-image")
            repository.setPresentation(ShellPresentation(DesktopMode.CLASSIC, DockVisibility.HIDDEN))
            val before = repository.settings.first()
            repository.setOverlay(OverlaySettings(true, 999, 999, -10))
            assertEquals(before.copy(overlay = OverlaySettings(true, 80, 200, 0)), repository.settings.first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = next, produceFile = { file }))
            assertEquals(OverlaySettings(true, 80, 200, 0), repository.settings.first().overlay)
            repository.setOverlay(repository.settings.first().overlay.copy(enabled = false))
            assertFalse(repository.settings.first().overlay.enabled)
            assertEquals(listOf(shortcut), repository.settings.first().sidebar)
        } finally { next.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun newOverlayFieldsDoNotChangeFreshCleanDefault() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = directory.newFolder().resolve("shell.preferences_pb")
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            repository.setOverlay(OverlaySettings(heightDp = 80))
            assertEquals(DesktopMode.CLEAN, repository.settings.first().presentation.desktopMode)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
