package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.hyprdroid.launcher.data.TileStateSlot
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.workspaceengine.WindowId
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import dev.hyprdroid.launcher.workspaceengine.WorkspaceWindow
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalTileStateStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun savedValuesSurviveDataStoreRestartAndCloseRemovesOnlyItsOwnSlot(): Unit = runBlocking {
        val file = folder.newFolder().resolve("workspace_engine.preferences_pb")
        val one = TileStateSlot("hello-tile", 1, WindowId("one"))
        val two = TileStateSlot("hello-tile", 1, WindowId("two"))
        val firstJob = SupervisorJob()
        val first = open(file, firstJob)
        first.save("0", engine(one))
        first.save("1", engine(two))
        first.saveTileState(one, "", "9")
        first.saveTileState(two, "", "2")
        firstJob.cancelAndJoin()

        val secondJob = SupervisorJob()
        try {
            val second = open(file, secondJob)
            assertEquals("9", second.tileState(one))
            assertEquals("2", second.tileState(two))
            assertEquals("", second.tileState(one.copy(schema = 2)))
            assertEquals("", second.tileState(one.copy(packageId = "other")))
            second.save("0", WorkspaceEngine())
            assertEquals("", second.tileState(one))
            assertEquals("2", second.tileState(two))
            assertThrows(IOException::class.java) { runBlocking { second.saveTileState(one, "", "late") } }
        } finally {
            secondJob.cancelAndJoin()
        }
    }

    @Test
    fun rejectsStaleSnapshotsForeignOwnersAndOversizeUtf8(): Unit = runBlocking {
        val job = SupervisorJob()
        try {
            val store = open(folder.newFolder().resolve("engine.preferences_pb"), job)
            val slot = TileStateSlot("hello-tile", 1, WindowId("one"))
            store.save("0", engine(slot))
            store.saveTileState(slot, "", "3")
            assertThrows(IOException::class.java) { runBlocking { store.saveTileState(slot, "", "4") } }
            assertThrows(IOException::class.java) { runBlocking { store.saveTileState(slot, "3", "я".repeat(257)) } }
            assertThrows(IOException::class.java) { runBlocking { store.saveTileState(slot.copy(packageId = "other"), "", "5") } }
            assertEquals("3", store.tileState(slot))
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun open(file: java.io.File, job: kotlinx.coroutines.Job) = WorkspaceEngineStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }),
    )

    private fun engine(slot: TileStateSlot) = WorkspaceEngine().apply {
        addFirstWindow(WorkspaceWindow(slot.windowId, slot.packageId, 0, 0))
    }
}
