package dev.hyprdroid.launcher.modules

import androidx.compose.ui.text.TextRange
import dev.hyprdroid.launcher.workspaceengine.WindowId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPanelStreamTest {
    private val processor = CommandProcessor()
    private val noPause: suspend (Long) -> Unit = {}

    private fun panel() = CommandPanelState(WindowId("w-stream"))

    @Test
    fun submitReturnsEventsAndEchoesInputOnce() {
        val state = panel()
        state.updateInput("demo install")

        val events = state.submit(processor)
        assertNotNull("demo install must stream", events)
        assertTrue(events!!.isNotEmpty())
        assertEquals("", state.input)

        val echoes = state.lines.filter { it.kind == CommandLineKind.INPUT }
        assertEquals(1, echoes.size)
        assertTrue(echoes.single().text.contains("demo install"))
    }

    @Test
    fun playAppendsOnlyLineEventsAndFinishes() = runBlocking {
        val state = panel()
        state.updateInput("demo install")
        val events = state.submit(processor)!!
        val before = state.lines.size
        val expectedLines = events.count { it is CommandEvent.Line }

        state.play(events, noPause)

        assertFalse(state.streaming)
        assertEquals(before + expectedLines, state.lines.size)
        assertTrue(
            state.lines.any {
                it.kind == CommandLineKind.OUTPUT && it.text.contains("Готово")
            },
        )
    }

    @Test
    fun streamingFlagIsTrueDuringPause() = runBlocking {
        val state = panel()
        state.updateInput("demo install")
        val events = state.submit(processor)!!

        var observedDuringPause: Boolean? = null
        state.play(events) { observedDuringPause = state.streaming }

        assertTrue("streaming must be true inside pause", observedDuringPause == true)
        assertFalse(state.streaming)
    }

    @Test
    fun consecutiveProgressReplacesTheSameLine() = runBlocking {
        val state = panel()
        val before = state.lines.size

        state.play(
            listOf(
                CommandEvent.Line("a"),
                CommandEvent.Progress("b"),
                CommandEvent.Progress("c"),
            ),
            noPause,
        )

        assertEquals(before + 1, state.lines.size)
        assertEquals("c", state.lines.last().text)
        assertEquals(CommandLineKind.OUTPUT, state.lines.last().kind)
    }

    @Test
    fun progressAsFirstEventAppendsInsteadOfCorruptingEcho() = runBlocking {
        val state = panel()
        state.updateInput("demo install")
        state.submit(processor)!!
        val echo = state.lines.last()
        assertEquals(CommandLineKind.INPUT, echo.kind)
        val before = state.lines.size

        state.play(listOf(CommandEvent.Progress("0%")), noPause)

        assertEquals(before + 1, state.lines.size)
        assertEquals("0%", state.lines.last().text)
        assertEquals(CommandLineKind.OUTPUT, state.lines.last().kind)
        assertEquals(echo, state.lines[state.lines.size - 2])
    }

    @Test
    fun progressPreservesRowIdentityAndCarriesCaretOffset() = runBlocking {
        val state = panel()
        val observed = mutableListOf<CommandLine>()
        state.play(
            listOf(
                CommandEvent.Progress("[---]", 1),
                CommandEvent.Pause(1),
                CommandEvent.Progress("[##-]", 3),
                CommandEvent.Pause(1),
            ),
        ) { observed += state.lines.last() }

        assertEquals(listOf(1, 3), observed.map { it.cursorOffset })
        assertEquals(observed.first().id, observed.last().id)
    }

    @Test
    fun editingInsideInputKeepsRealSelection() {
        val state = panel()
        state.updateInput("abde")
        state.inputField.edit {
            selection = TextRange(2)
            replace(2, 2, "c")
        }
        assertEquals("abcde", state.input)
        assertEquals(TextRange(3), state.inputField.selection)

        state.inputField.edit {
            selection = TextRange(1, 4)
            replace(selection.min, selection.max, "")
        }
        assertEquals("ae", state.input)
        assertEquals(TextRange(1), state.inputField.selection)
    }

    @Test
    fun logStopsAtTwoHundredLines() = runBlocking {
        val state = panel()
        val events = (1..250).map { CommandEvent.Line("line-$it") }

        state.play(events, noPause)

        assertEquals(200, state.lines.size)
        assertEquals("line-250", state.lines.last().text)
    }

    @Test
    fun failureInPauseStillClearsStreamingFlag() = runBlocking {
        val state = panel()

        val result = runCatching {
            state.play(
                listOf(CommandEvent.Line("partial"), CommandEvent.Pause(10)),
            ) {
                throw IllegalStateException("cancelled")
            }
        }

        assertTrue(result.isFailure)
        assertFalse(state.streaming)
        assertEquals("partial", state.lines.last().text)
    }

    @Test
    fun panelsRemainIndependent() = runBlocking {
        val streamed = panel()
        val untouched = panel()
        val untouchedLines = untouched.lines.toList()

        streamed.updateInput("demo install")
        streamed.play(streamed.submit(processor)!!, noPause)

        assertTrue(streamed.lines.any { it.text.contains("Готово") })
        assertEquals(untouchedLines, untouched.lines.toList())
        assertEquals(1, untouched.lines.size)
        assertFalse(untouched.lines.any { it.text.contains("Готово") })
        assertFalse(untouched.streaming)
        assertEquals("", untouched.input)
    }
}
