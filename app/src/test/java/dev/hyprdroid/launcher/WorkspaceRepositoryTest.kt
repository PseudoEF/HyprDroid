package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.hyprdroid.launcher.data.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun pinsAndPreferencesSurviveStoreRecreation() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("workspace.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = WorkspaceRepository(PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }))
            repository.setPinned(0, "example.first/.Main", true)
            repository.setPinned(2, "example.second/.Main", true)
            repository.selectWorkspace(2)
            repository.setGridLayout(false)
        } finally {
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
        }
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val restored = WorkspaceRepository(PreferenceDataStoreFactory.create(scope = nextScope, produceFile = { file })).settings.first()
            assertEquals(setOf("example.first/.Main"), restored.workspaces[0].pinnedApps)
            assertTrue(restored.workspaces[1].pinnedApps.isEmpty())
            assertEquals(setOf("example.second/.Main"), restored.workspaces[2].pinnedApps)
            assertEquals(2, restored.selectedWorkspace)
            assertFalse(restored.gridLayout)
        } finally {
            nextScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun concurrentPinningDoesNotLoseUpdatesAndRemovalIsLocal() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("workspace.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = WorkspaceRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            coroutineScope {
                repeat(20) { index -> launch { repository.setPinned(0, "example.app$index/.Main", true) } }
                launch { repository.setPinned(1, "example.app0/.Main", true) }
            }
            assertEquals(20, repository.settings.first().workspaces[0].pinnedApps.size)
            repository.setPinned(0, "example.app0/.Main", false)
            val state = repository.settings.first()
            assertEquals(19, state.workspaces[0].pinnedApps.size)
            assertEquals(setOf("example.app0/.Main"), state.workspaces[1].pinnedApps)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun uninstallRemovesAllActivitiesOnlyForThatPackage() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("workspace.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = WorkspaceRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            repository.setPinned(0, "example.removed/.Main", true)
            repository.setPinned(1, "example.removed/.Other", true)
            repository.setPinned(1, "example.removed.extra/.Main", true)
            repository.removePackages(setOf("example.removed"))
            val state = repository.settings.first()
            assertTrue(state.workspaces[0].pinnedApps.isEmpty())
            assertEquals(setOf("example.removed.extra/.Main"), state.workspaces[1].pinnedApps)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
