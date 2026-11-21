package dev.hyprdroid.launcher.modules

import dev.hyprdroid.launcher.workspaceengine.WindowId
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandProcessorTest {
    private val fixedTime = LocalDateTime.of(2026, 11, 21, 14, 5, 9)
    private val processor = CommandProcessor(
        modules = {
            listOf(
                ModuleSummary("hyprdroid.clock", "Часы"),
                ModuleSummary("hyprdroid.command", "Команды"),
            )
        },
        now = { fixedTime },
    )

    private fun output(command: String): String {
        val result = processor.execute(command)
        assertTrue("$command must produce output", result is CommandResult.Output)
        return (result as CommandResult.Output).text
    }

    @Test
    fun helpListsOnlySafeCommands() {
        val text = output("help")
        assertTrue(text.contains("help"))
        assertTrue(text.contains("clear"))
        assertTrue(text.contains("modules"))
        assertTrue(text.contains("date"))
        assertFalse(text.contains("root"))
    }

    @Test
    fun clearRequestsLogReset() {
        assertEquals(CommandResult.Clear, processor.execute("clear"))
        assertEquals(CommandResult.Clear, processor.execute("  ClEaR  "))
    }

    @Test
    fun modulesListsRegistryEntries() {
        val text = output("modules")
        assertTrue(text.contains("hyprdroid.clock"))
        assertTrue(text.contains("Часы"))
        assertTrue(text.contains("hyprdroid.command"))
    }

    @Test
    fun dateUsesInjectedClock() {
        assertEquals("21.11.2026 14:05:09", output("date"))
    }

    @Test
    fun unknownCommandsAreRejectedWithoutShellFallback() {
        val text = output("sh -c 'id'")
        assertTrue(text.startsWith("Неизвестная команда:"))
        assertTrue(text.contains("Это не shell"))
        assertEquals("", output("   "))
    }

    @Test
    fun commandPanelStatesAreIndependentAndClearLocally() {
        val first = CommandPanelState(WindowId("w-1"))
        val second = CommandPanelState(WindowId("w-2"))

        first.updateInput("date")
        first.submit(processor)
        second.updateInput("help")
        second.submit(processor)

        assertTrue(first.lines.any { it.text.contains("21.11.2026") })
        assertFalse(second.lines.any { it.text.contains("21.11.2026") })
        assertEquals(WindowId("w-1"), first.windowId)
        assertEquals("", first.input)

        first.updateInput("clear")
        first.submit(processor)
        assertTrue(first.lines.isEmpty())
        assertFalse(second.lines.isEmpty())
    }
}
