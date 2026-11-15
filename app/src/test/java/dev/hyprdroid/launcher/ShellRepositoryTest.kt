package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.hyprdroid.launcher.data.DockFullException
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.model.MAX_DOCK_APPS
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun shellPreferencesSurviveRecreationWithoutChangingWorkspaces() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val workspaceFile = directory.resolve("workspaces.preferences_pb")
        val shellFile = directory.resolve("shell.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val workspace = WorkspaceRepository(PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { workspaceFile }))
            workspace.setPinned(2, "example.app/.Main", true)
            workspace.selectWorkspace(2)
            workspace.setGridLayout(false)
            val shell = ShellRepository(PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { shellFile }))
            shell.setFavorite("example.favorite/.Main", true)
            shell.setWallpaper("content://documents/wallpaper")
        } finally { firstScope.coroutineContext[Job]!!.cancelAndJoin() }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val workspace = WorkspaceRepository(PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { workspaceFile })).settings.first()
            assertEquals(setOf("example.app/.Main"), workspace.workspaces[2].pinnedApps)
            assertEquals(2, workspace.selectedWorkspace)
            assertFalse(workspace.gridLayout)
            val shell = ShellRepository(PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { shellFile })).settings.first()
            assertEquals(setOf("example.favorite/.Main"), shell.favorites)
            assertEquals("content://documents/wallpaper", shell.wallpaperUri)
        } finally { secondScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun concurrentDockChangesRespectCapacityAndAllowReplacement() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("shell.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            coroutineScope {
                repeat(12) { index -> launch {
                    try { repository.setFavorite("example.app$index/.Main", true) } catch (_: DockFullException) { }
                } }
            }
            val saved = repository.settings.first().favorites
            assertEquals(MAX_DOCK_APPS, saved.size)
            val removed = saved.first()
            repository.setFavorite(removed, false)
            repository.setFavorite("example.replacement/.Main", true)
            assertEquals(MAX_DOCK_APPS, repository.settings.first().favorites.size)
            assertFalse(removed in repository.settings.first().favorites)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun uninstallCleansOnlyMatchingFavoritesAndSystemWallpaperClearsImage() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("shell.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            repository.setFavorite("example.removed/.Main", true)
            repository.setFavorite("example.removed.extra/.Main", true)
            repository.setWallpaper("content://documents/image")
            repository.removePackages(setOf("example.removed"))
            repository.setWallpaper(null)
            val result = repository.settings.first()
            assertEquals(setOf("example.removed.extra/.Main"), result.favorites)
            assertNull(result.wallpaperUri)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
