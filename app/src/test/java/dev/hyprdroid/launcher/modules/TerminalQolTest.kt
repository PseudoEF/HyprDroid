package dev.hyprdroid.launcher.modules

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.hyprdroid.launcher.workspaceengine.WindowId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalQolTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun inputParsesLfAndCrlfWithoutEmptyCommands() {
        assertEquals(
            listOf("hypr search droidfetch", "hypr list --installed", "history"),
            parseTerminalInput("  hypr search droidfetch\r\n\n\t\r\nhypr list --installed\n history  "),
        )
        assertEquals(emptyList<String>(), parseTerminalInput("\n \r\n"))
        assertEquals(BATCH_LIMIT + 1, parseTerminalInput("date\n".repeat(BATCH_LIMIT + 2)).size)
    }

    @Test
    fun queueWaitsForStreamingOperationAndKeepsOrder() = runBlocking {
        val panel = panel()
        val history = MemoryHistory()
        val timeline = mutableListOf<String>()
        panel.runCommands(listOf("first", "second"), execute = { command ->
            timeline += command
            if (command == "first") {
                CommandResult.Stream(listOf(CommandEvent.Progress("[#-]", 2), CommandEvent.Pause(1),
                    CommandEvent.Operation {
                        timeline += "completed"
                        CommandOperationResult(listOf("first output"))
                    },
                ))
            } else CommandResult.Output("second output")
        }, history = history, pause = {
            assertEquals(listOf("first"), timeline)
            assertTrue(panel.streaming)
            assertEquals(2, panel.lines.last().cursorOffset)
        })

        assertEquals(listOf("first", "completed", "second"), timeline)
        assertEquals(listOf("first", "second"), history.entries())
        assertEquals(
            listOf("› first", "[#-]", "first output", "› second", "second output"),
            panel.lines.drop(1).map { it.text },
        )
        assertFalse(panel.running)
        assertFalse(panel.streaming)
    }

    @Test
    fun failedStreamingOperationStopsBeforeNextCommand() = runBlocking {
        val panel = panel()
        val history = MemoryHistory()
        panel.runCommands(listOf("first", "second"), execute = { command ->
            assertEquals("first", command)
            CommandResult.Stream(listOf(
                CommandEvent.Operation { CommandOperationResult(listOf("Пакет не найден."), failed = true) },
                CommandEvent.Line("must not run"),
            ))
        }, history = history)

        assertEquals(listOf("first"), history.entries())
        assertEquals("Пакет не найден.", panel.lines.single { it.kind == CommandLineKind.ERROR }.text)
        assertTrue(panel.lines.last().text.contains("ошибка команды"))
        assertFalse(panel.lines.any { it.text == "must not run" })
    }

    @Test
    fun unknownCommandStopsBatchAndIsMarkedAsAnError() = runBlocking {
        val panel = panel()
        val history = MemoryHistory()
        panel.runCommands(listOf("not-a-command", "date"), CommandProcessor()::execute, history)
        assertEquals(listOf("not-a-command"), history.entries())
        assertTrue(panel.lines.single { it.kind == CommandLineKind.ERROR }.text.contains("Неизвестная команда"))
    }

    @Test
    fun cancellationKeepsCurrentCommandAndSkipsAllRemaining() = runBlocking {
        val panel = panel()
        val history = MemoryHistory()
        panel.runCommands(listOf("first", "second", "third"), execute = { command ->
            assertEquals("first", command)
            CommandResult.Stream(listOf(CommandEvent.Pause(1), CommandEvent.Line("finished")))
        }, history = history, pause = {
            assertEquals(2, panel.remaining)
            panel.cancelRemaining()
        })

        assertEquals(listOf("first"), history.entries())
        assertEquals("finished", panel.lines[panel.lines.lastIndex - 1].text)
        assertTrue(panel.lines.last().text.contains("Пропущено команд: 2"))
        assertEquals(0, panel.remaining)
        assertFalse(panel.running)
    }

    @Test
    fun historyPersistsDeduplicatesLimitsAndClearsAcrossReopen() = runBlocking {
        val file = folder.root.resolve("history.preferences_pb")
        suspend fun useHistory(block: suspend (StoredTerminalHistory) -> Unit) {
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { file },
            )
            try {
                block(StoredTerminalHistory(store))
            } finally {
                job.cancelAndJoin()
            }
        }

        useHistory { history ->
            history.record("old")
            repeat(HISTORY_LIMIT) { history.record("command $it") }
            history.record("command ${HISTORY_LIMIT - 1}")
        }
        useHistory { history ->
            val panel = panel()
            val processor = CommandProcessor(history = history)
            val expected = List(HISTORY_LIMIT) { "command $it" }
            assertEquals(expected, history.entries())
            panel.runCommands(listOf("history"), processor::execute, history)
            assertEquals(expected, panel.lines.takeLast(HISTORY_LIMIT).map { it.text })
            panel.runCommands(listOf("history  clear"), processor::execute, history)
            assertEquals(emptyList<String>(), history.entries())
        }
        useHistory { assertEquals(emptyList<String>(), it.entries()) }
    }

    @Test
    fun navigationRestoresDraftAndStartsOverAfterEdit() {
        val nav = HistoryNavigation()
        val entries = listOf("clear", "date")
        assertEquals("date", nav.previous("draft", entries))
        assertEquals("clear", nav.previous("date", entries))
        assertEquals("date", nav.next("clear"))
        assertEquals("draft", nav.next("date"))
        assertEquals("date", nav.previous("edited", entries))
        assertEquals("edited", nav.next("date"))
    }

    @Test
    fun suggestionsUseCommandContextAndCorrectPackageSource() {
        fun suggest(input: String) = TerminalCompletion(input).suggestions(
            catalog = listOf("droidfetch", "weather"),
            installed = listOf("clock", "droidfetch"),
        )
        assertEquals(listOf("hypr install"), suggest("hypr ins"))
        assertEquals(listOf("hypr list --installed"), suggest("hypr list --ins"))
        assertEquals(listOf("hypr install droidfetch"), suggest("HYPR install dro"))
        assertEquals(listOf("hypr remove clock", "hypr remove droidfetch"), suggest("hypr remove "))
        assertEquals(CompletionPackages.INSTALLED, TerminalCompletion("hypr remove ").packageSource)
        assertEquals(emptyList<String>(), suggest("hypr remove wea"))
        assertEquals(listOf("hypr info clock"), suggest("hypr info cl"))
        assertEquals(emptyList<String>(), suggest("hypr ins\ndate"))
    }

    private fun panel() = CommandPanelState(WindowId("terminal-qol-test"))

    private class MemoryHistory : TerminalHistory {
        private var history = emptyList<String>()

        override suspend fun entries() = history
        override suspend fun record(command: String) {
            history = appendHistory(history, command)
        }
        override suspend fun clear() {
            history = emptyList()
        }
    }
}
