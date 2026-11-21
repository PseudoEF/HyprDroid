package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.modules.ModuleRegistry
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import dev.hyprdroid.launcher.workspaceengine.WorkspaceWindow
import dev.hyprdroid.launcher.workspaceengine.WindowId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TileModulePersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun chosenModulesSurviveTheEngineStore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = WorkspaceEngineStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { folder.newFolder().resolve("workspace_engine.preferences_pb") },
            ),
        )
        val engine = WorkspaceEngine()
        engine.addFirstWindow(WorkspaceWindow(WindowId("w-1"), ModuleRegistry.UNASSIGNED, 0, 0))
        engine.setWindowModule(WindowId("w-1"), "hyprdroid.clock")
        store.save("0", engine)

        val restored = store.load().getValue("0")

        assertEquals("hyprdroid.clock", restored.windowsById().getValue(WindowId("w-1")).moduleId)
        assertEquals(listOf(WindowId("w-1")), restored.leaves())
    }

    @Test
    fun unknownModuleIdStillLoadsAsPickerState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = WorkspaceEngineStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { folder.newFolder().resolve("workspace_engine.preferences_pb") },
            ),
        )
        val engine = WorkspaceEngine()
        engine.addFirstWindow(WorkspaceWindow(WindowId("w-1"), "removed.module", 0, 0))
        store.save("0", engine)

        val restored = store.load().getValue("0")

        assertEquals("removed.module", restored.windowsById().getValue(WindowId("w-1")).moduleId)
    }
}
