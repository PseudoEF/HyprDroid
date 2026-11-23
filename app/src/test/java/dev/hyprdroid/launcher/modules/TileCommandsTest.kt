package dev.hyprdroid.launcher.modules

import dev.hyprdroid.launcher.packages.DROIDFETCH_SAMPLE_ID
import dev.hyprdroid.launcher.packages.PackageException
import dev.hyprdroid.launcher.workspaceengine.WindowId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileCommandsTest {
    @Test
    fun addChecksTrustBeforeCreatingAndRemovalUsesCoreResult() = runBlocking {
        val created = mutableListOf<WindowId>()
        val tile = WindowId("example-tile")
        var authorized = false
        val commands = TileCommands(
            TileCommandPort(
                add = { created.add(tile); tile },
                list = { created.toList() },
                remove = { created.remove(it) },
            ),
            authorize = { if (!authorized) throw PackageException("Сначала установите образец") },
        )

        val wrong = commands.execute(listOf("add", "hello-tile")) as CommandResult.Output
        assertTrue(wrong.failed)

        val request = commands.execute(listOf("add", DROIDFETCH_SAMPLE_ID)) as CommandResult.Stream
        val operation = request.events.single() as CommandEvent.Operation
        assertTrue(operation.run().failed)
        assertTrue(created.isEmpty())
        authorized = true
        assertFalse(operation.run().failed)
        assertEquals(listOf(tile), created)
        assertFalse((commands.execute(listOf("remove", tile.value)) as CommandResult.Output).failed)
        assertTrue((commands.execute(listOf("remove", tile.value)) as CommandResult.Output).failed)
    }
}
