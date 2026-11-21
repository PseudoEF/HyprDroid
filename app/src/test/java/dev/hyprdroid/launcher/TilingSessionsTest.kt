package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.modules.ModuleRegistry
import dev.hyprdroid.launcher.ui.TilingSessions
import dev.hyprdroid.launcher.ui.TilingState
import dev.hyprdroid.launcher.ui.restoreTiling
import dev.hyprdroid.launcher.ui.sessionFor
import dev.hyprdroid.launcher.ui.withOpenSession
import dev.hyprdroid.launcher.ui.withPublishedSession
import dev.hyprdroid.launcher.ui.withoutSession
import dev.hyprdroid.launcher.workspaceengine.Orientation
import dev.hyprdroid.launcher.workspaceengine.SplitId
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import dev.hyprdroid.launcher.workspaceengine.WorkspaceWindow
import dev.hyprdroid.launcher.workspaceengine.WindowId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression coverage for independent tiling sessions of every workspace. */
class TilingSessionsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun engine(vararg ids: String): WorkspaceEngine {
        val engine = WorkspaceEngine()
        ids.forEachIndexed { index, id ->
            val window = WorkspaceWindow(WindowId(id), ModuleRegistry.UNASSIGNED, 0, 0)
            if (index == 0) assertTrue(engine.addFirstWindow(window))
            else assertTrue(engine.splitWindow(WindowId(ids[index - 1]), Orientation.HORIZONTAL, SplitId("split-$id"), window))
        }
        engine.focusWindow(WindowId(ids.last()))
        return engine
    }

    private fun store() = WorkspaceEngineStore(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { folder.newFolder().resolve("workspace_engine.preferences_pb") },
        ),
    )

    @Test
    fun tilesInBothWorkspacesStayIndependentWhileSwitching() {
        val firstWorkspace = engine("a-1", "a-2")
        val secondWorkspace = engine("b-1")
        var sessions: TilingSessions = emptyMap()

        sessions = sessions.withOpenSession(0, firstWorkspace)
        sessions = sessions.withOpenSession(1, secondWorkspace)

        assertEquals(listOf(WindowId("a-1"), WindowId("a-2")), sessions.sessionFor(0)?.engine?.leaves())
        assertEquals(WindowId("a-2"), sessions.sessionFor(0)?.focusedWindowId)
        assertEquals(listOf(WindowId("b-1")), sessions.sessionFor(1)?.engine?.leaves())
        assertEquals(WindowId("b-1"), sessions.sessionFor(1)?.focusedWindowId)

        // A change in the first workspace is published only there; the second snapshot is untouched.
        val grownFirst = engine("a-1", "a-2", "a-3")
        sessions = sessions.withPublishedSession(0, grownFirst)

        assertEquals(listOf(WindowId("a-1"), WindowId("a-2"), WindowId("a-3")), sessions.sessionFor(0)?.engine?.leaves())
        assertEquals(listOf(WindowId("b-1")), sessions.sessionFor(1)?.engine?.leaves())
        assertEquals(secondWorkspace.serialize(), sessions.sessionFor(1)?.engine?.serialize())
    }

    @Test
    fun closingOneWorkspaceKeepsTheOtherOpen() {
        val sessions = emptyMap<Int, TilingState>()
            .withOpenSession(0, engine("a-1"))
            .withOpenSession(1, engine("b-1", "b-2"))

        val closed = sessions.withoutSession(0)

        assertNull(closed.sessionFor(0))
        assertEquals(listOf(WindowId("b-1"), WindowId("b-2")), closed.sessionFor(1)?.engine?.leaves())
        assertEquals(WindowId("b-2"), closed.sessionFor(1)?.focusedWindowId)
        // The original value is immutable, so the engine tree of workspace 0 is still available.
        assertEquals(listOf(WindowId("a-1")), sessions.sessionFor(0)?.engine?.leaves())
    }

    @Test
    fun publishingDoesNotOpenAClosedWorkspace() {
        val sessions = emptyMap<Int, TilingState>()

        assertEquals(emptyMap<Int, TilingState>(), sessions.withPublishedSession(0, engine("a-1")))
        assertNull(sessions.withPublishedSession(0, engine("a-1")).sessionFor(0))
    }

    @Test
    fun restartRestoresEveryOpenWorkspaceWithItsTreeModulesAndFocus() = runBlocking {
        val store = store()
        val first = engine("a-1", "a-2")
        assertTrue(first.setWindowModule(WindowId("a-2"), "hyprdroid.clock"))
        assertTrue(first.resizeSplit(SplitId("split-a-2"), 0.35f))
        val second = engine("b-1")
        val closed = engine("c-1")
        store.save("0", first)
        store.save("1", second)
        store.save("2", closed)
        store.setOpen("0", true)
        store.setOpen("1", true)
        store.setOpen("2", false)

        val restored = restoreTiling(store.load(), store.openWorkspaces())

        assertEquals(setOf(0, 1, 2), restored.engines.keys)
        assertEquals(setOf(0, 1), restored.sessions.keys)
        assertEquals(first.serialize(), restored.engines.getValue(0).serialize())
        assertEquals(second.serialize(), restored.engines.getValue(1).serialize())
        assertEquals(first.serialize(), restored.sessions.getValue(0).engine.serialize())
        assertEquals(second.serialize(), restored.sessions.getValue(1).engine.serialize())
        assertEquals("hyprdroid.clock", restored.engines.getValue(0).windowsById().getValue(WindowId("a-2")).moduleId)
        assertEquals(WindowId("a-2"), restored.sessions.getValue(0).focusedWindowId)
        assertNull(restored.sessions[2])
    }

    @Test
    fun restoreSkipsMalformedKeysWithoutBlockingOtherWorkspaces() {
        val restored = restoreTiling(
            mapOf("0" to engine("a-1"), "legacy" to engine("x-1")),
            setOf("0", "legacy"),
        )

        assertEquals(setOf(0), restored.engines.keys)
        assertEquals(setOf(0), restored.sessions.keys)
    }
}
