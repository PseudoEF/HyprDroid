package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.data.WorkspaceEngineStore
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
import androidx.datastore.preferences.core.PreferenceDataStoreFactory

class WorkspaceEngineStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun saveAndLoadRoundTripEngineState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = WorkspaceEngineStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { folder.newFolder().resolve("workspace_engine.preferences_pb") }))
        val engine = WorkspaceEngine()
        engine.addFirstWindow(WorkspaceWindow(WindowId("w-1"), "com.example/.Main", 48, 48))
        store.save("default", engine)
        val loaded = store.load()
        assertEquals(1, loaded.size)
        val placed = loaded.getValue("default").calculateLayout(dev.hyprdroid.launcher.workspaceengine.LayoutArea(320, 480), mapOf(WindowId("w-1") to dev.hyprdroid.launcher.workspaceengine.WindowMin(0, 0)))
        assertEquals(1, placed.size)
    }
}
