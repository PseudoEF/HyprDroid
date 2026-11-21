package dev.hyprdroid.launcher.modules

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.hyprdroid.launcher.workspaceengine.WindowId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class TestState(windowId: WindowId) : ModuleState(windowId) {
    val history = mutableListOf<String>()
    var disposed = false

    override fun dispose() {
        disposed = true
    }
}

private class TestModule(
    override val id: String,
    override val title: String = id,
) : TileModule {
    override val summary = "test module"
    override val glyph = ModuleGlyph.COMMAND

    override fun createState(windowId: WindowId): ModuleState = TestState(windowId)

    @Composable
    override fun Content(state: ModuleState, modifier: Modifier) = Unit
}

class TileModuleRegistryTest {
    @Test
    fun registryKeepsOrderAndFindsModulesById() {
        val first = TestModule("first", "Первый")
        val second = TestModule("second", "Второй")
        val registry = ModuleRegistry(listOf(first, second))

        assertEquals(listOf("first", "second"), registry.modules.map { it.id })
        assertSame(first, registry.find("first"))
        assertSame(second, registry.find("second"))
    }

    @Test
    fun registryRejectsDuplicateAndBlankIds() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleRegistry(listOf(TestModule("same"), TestModule("same")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModuleRegistry(listOf(TestModule("")))
        }
    }

    @Test
    fun unknownAndUnassignedIdsDoNotResolveToAModule() {
        val registry = ModuleRegistry(listOf(TestModule("clock")))
        assertNull(registry.find("missing"))
        assertNull(registry.find(null))
        assertNull(registry.find("   "))
        assertNull(registry.find(ModuleRegistry.UNASSIGNED))
        assertNull(registry.createState("missing", WindowId("w-1")))
    }

    @Test
    fun eachInstanceGetsItsOwnStateBoundToItsWindow() {
        val registry = ModuleRegistry(listOf(TestModule("clock")))
        val first = registry.createState("clock", WindowId("w-1")) as TestState
        val second = registry.createState("clock", WindowId("w-2")) as TestState

        assertEquals(WindowId("w-1"), first.windowId)
        assertEquals(WindowId("w-2"), second.windowId)
        first.history += "only-first"
        assertTrue(second.history.isEmpty())
    }

    @Test
    fun disposingOneInstanceLeavesTheOtherAlive() {
        val registry = ModuleRegistry(listOf(TestModule("clock")))
        val first = registry.createState("clock", WindowId("w-1")) as TestState
        val second = registry.createState("clock", WindowId("w-2")) as TestState

        first.dispose()

        assertTrue(first.disposed)
        assertFalse(second.disposed)
        assertTrue(second.history.isEmpty())
    }
}
