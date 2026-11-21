package dev.hyprdroid.launcher.modules

import dev.hyprdroid.launcher.workspaceengine.LayoutArea
import dev.hyprdroid.launcher.workspaceengine.Orientation
import dev.hyprdroid.launcher.workspaceengine.SplitId
import dev.hyprdroid.launcher.workspaceengine.WindowId
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import dev.hyprdroid.launcher.workspaceengine.WorkspaceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileModuleEngineTest {
    private val area = LayoutArea(1000, 800)

    private fun engineWithTwoWindows(): WorkspaceEngine {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(WorkspaceWindow(WindowId("w-1"), ModuleRegistry.UNASSIGNED, 0, 0))
        engine.splitWindow(
            WindowId("w-1"),
            Orientation.HORIZONTAL,
            SplitId("s-1"),
            WorkspaceWindow(WindowId("w-2"), ModuleRegistry.UNASSIGNED, 120, 80),
        )
        engine.resizeSplit(SplitId("s-1"), 0.3f)
        return engine
    }

    @Test
    fun moduleCanBeChosenAtRuntime() {
        val engine = WorkspaceEngine()
        engine.addFirstWindow(WorkspaceWindow(WindowId("w-1"), ModuleRegistry.UNASSIGNED, 0, 0))

        assertTrue(engine.setWindowModule(WindowId("w-1"), "hyprdroid.clock"))
        assertEquals("hyprdroid.clock", engine.windowsById().getValue(WindowId("w-1")).moduleId)
    }

    @Test
    fun switchingModuleKeepsTreeRatiosAndGeometry() {
        val engine = engineWithTwoWindows()
        val before = engine.calculateLayout(area, emptyMap())
        val beforeLeaves = engine.leaves()

        assertTrue(engine.setWindowModule(WindowId("w-2"), "hyprdroid.apps"))

        val after = engine.calculateLayout(area, emptyMap())
        assertEquals(before, after)
        assertEquals(beforeLeaves, engine.leaves())
        assertEquals("hyprdroid.apps", engine.windowsById().getValue(WindowId("w-2")).moduleId)
        assertEquals(120, engine.windowsById().getValue(WindowId("w-2")).minWidthDp)
        assertEquals(80, engine.windowsById().getValue(WindowId("w-2")).minHeightDp)
        assertFalse(engine.setWindowModule(WindowId("w-2"), "  "))
        assertFalse(engine.setWindowModule(WindowId("missing"), "hyprdroid.clock"))
    }

    @Test
    fun selectedModulesSurviveSerializationRoundTrip() {
        val engine = engineWithTwoWindows()
        engine.setWindowModule(WindowId("w-1"), "hyprdroid.clock")
        engine.setWindowModule(WindowId("w-2"), "hyprdroid.command")

        val restored = WorkspaceEngine().deserialize(engine.serialize())

        assertEquals("hyprdroid.clock", restored.windowsById().getValue(WindowId("w-1")).moduleId)
        assertEquals("hyprdroid.command", restored.windowsById().getValue(WindowId("w-2")).moduleId)
        assertEquals(engine.calculateLayout(area, emptyMap()), restored.calculateLayout(area, emptyMap()))
    }

    @Test
    fun unknownModuleIdSurvivesRestoreAndShowsPickerState() {
        val engine = engineWithTwoWindows()
        engine.setWindowModule(WindowId("w-2"), "legacy.removed.module")

        val restored = WorkspaceEngine().deserialize(engine.serialize())
        val registry = ModuleRegistry(listOf(TestModuleForEngine("hyprdroid.clock")))

        assertEquals("legacy.removed.module", restored.windowsById().getValue(WindowId("w-2")).moduleId)
        assertNull(registry.find(restored.windowsById().getValue(WindowId("w-2")).moduleId))
        assertNull(registry.createState("legacy.removed.module", WindowId("w-2")))
    }

    @Test
    fun closingOneOfSeveralInstancesKeepsTheOther() {
        val engine = engineWithTwoWindows()
        engine.setWindowModule(WindowId("w-1"), "hyprdroid.command")
        engine.setWindowModule(WindowId("w-2"), "hyprdroid.command")

        assertTrue(engine.removeWindow(WindowId("w-1")))

        assertEquals(listOf(WindowId("w-2")), engine.leaves())
        assertEquals("hyprdroid.command", engine.windowsById().getValue(WindowId("w-2")).moduleId)
        assertNull(engine.windowsById()[WindowId("w-1")])
    }
}

private class TestModuleForEngine(override val id: String) : TileModule {
    override val title = id
    override val summary = "test"
    override val glyph = ModuleGlyph.CLOCK

    override fun createState(windowId: WindowId): ModuleState = object : ModuleState(windowId) {}

    @androidx.compose.runtime.Composable
    override fun Content(state: ModuleState, modifier: androidx.compose.ui.Modifier) = Unit
}
