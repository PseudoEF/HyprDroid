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

    private fun stream(command: String): List<CommandEvent> {
        val result = processor.execute(command)
        assertTrue("$command must produce a stream", result is CommandResult.Stream)
        return (result as CommandResult.Stream).events
    }

    @Test
    fun helpListsOnlySafeCommands() {
        val text = output("help")
        assertTrue(text.contains("help"))
        assertTrue(text.contains("clear"))
        assertTrue(text.contains("modules"))
        assertTrue(text.contains("date"))
        assertTrue(text.contains("droidfetch"))
        assertTrue(text.contains("demo install"))
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

    @Test
    fun demoInstallStreamsLinesAndInPlaceProgressRuns() {
        val events = stream("demo install")
        assertTrue(events.isNotEmpty())
        assertTrue("first event must be a line", events.first() is CommandEvent.Line)

        var sawLine = false
        val progressRuns = mutableListOf<Int>()
        var currentRun = 0
        for (event in events) {
            when (event) {
                is CommandEvent.Line -> {
                    sawLine = true
                    if (currentRun > 0) {
                        progressRuns += currentRun
                        currentRun = 0
                    }
                }
                is CommandEvent.Progress -> {
                    assertTrue("progress must follow a line", sawLine)
                    currentRun++
                }
                // Pauses do not break an in-place progress run; only a new line does.
                is CommandEvent.Pause -> Unit
            }
        }
        if (currentRun > 0) progressRuns += currentRun

        assertTrue("at least two progress runs, got $progressRuns", progressRuns.size >= 2)
        assertTrue("progress must update one line in place", progressRuns.all { it >= 2 })
    }

    @Test
    fun demoInstallTimingAndTextStaySafe() {
        val events = stream("demo install")

        val pauses = events.filterIsInstance<CommandEvent.Pause>().map { it.millis }
        assertTrue("script must pause between lines", pauses.isNotEmpty())
        pauses.forEach { assertTrue("pause $it outside 60..220", it in 60L..220L) }
        val total = pauses.sum()
        assertTrue("total pause $total outside 2500..3500", total in 2500L..3500L)

        val texts = events.mapNotNull { event ->
            when (event) {
                is CommandEvent.Line -> event.text
                is CommandEvent.Progress -> event.text
                is CommandEvent.Pause -> null
            }
        }
        assertTrue(texts.isNotEmpty())
        texts.forEach { text ->
            assertTrue("blank event text", text.isNotBlank())
            assertFalse("ESC found in event text", text.contains('\u001B'))
        }

        val lastLine = events.filterIsInstance<CommandEvent.Line>().last()
        assertTrue("last line was: ${lastLine.text}", lastLine.text.contains("Готово"))
    }

    @Test
    fun demoWithoutArgumentsPrintsUsageInsteadOfStreaming() {
        val result = processor.execute("demo")
        assertTrue("demo must stay a plain output", result is CommandResult.Output)
        val text = (result as CommandResult.Output).text
        assertTrue(text.contains("demo install"))
        assertTrue(text.contains("shell"))
    }
}
