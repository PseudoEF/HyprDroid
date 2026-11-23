package dev.hyprdroid.launcher.modules

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hyprdroid.launcher.workspaceengine.WindowId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal enum class CommandLineKind { SYSTEM, INPUT, OUTPUT, ERROR }

internal data class CommandLine(
    val text: String,
    val kind: CommandLineKind,
    val cursorOffset: Int = text.length,
    val id: Long = 0,
)

internal class CommandPanelState(windowId: WindowId) : ModuleState(windowId) {
    var lines by mutableStateOf(
        listOf(CommandLine("HyprDroid · безопасные команды. Введите help.", CommandLineKind.SYSTEM)),
    )
        private set
    val inputField = TextFieldState()
    val input: String get() = inputField.text.toString()

    /** True while a [CommandResult.Stream] is being played into [lines]. */
    var streaming by mutableStateOf(false)
        private set

    var running by mutableStateOf(false)
        private set
    var remaining by mutableStateOf(0)
        private set
    private var stopRequested = false

    fun cancelRemaining() {
        stopRequested = true
    }

    fun inputError(text: String) {
        appendOutput(text, CommandLineKind.ERROR)
    }

    suspend fun runCommands(
        commands: List<String>,
        execute: (String) -> CommandResult,
        history: TerminalHistory,
        pause: suspend (Long) -> Unit = { delay(it) },
    ) {
        if (running || commands.isEmpty()) return
        running = true
        stopRequested = false
        try {
            for ((index, command) in commands.withIndex()) {
                currentCoroutineContext().ensureActive()
                remaining = commands.lastIndex - index
                if (!command.equals("history", true) && !command.matches(HISTORY_CLEAR)) {
                    try {
                        history.record(command)
                    } catch (_: IOException) {
                        inputError("Не удалось сохранить команду в истории.")
                    }
                }
                val failed = try {
                    val result = execute(command)
                    val events = applyResult(command, result)
                    when (result) {
                        is CommandResult.Output -> result.failed
                        is CommandResult.Stream -> !play(checkNotNull(events), pause)
                        CommandResult.Clear -> false
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    inputError("Не удалось выполнить команду «$command».")
                    true
                }
                if (remaining > 0 && (stopRequested || failed)) {
                    val reason = if (failed) "ошибка команды" else "отмена пользователем"
                    appendOutput("Пакет остановлен ($reason). Пропущено команд: $remaining.")
                    break
                }
            }
        } finally {
            remaining = 0
            running = false
        }
    }

    private var playGeneration = 0
    private var lineSequence = 0L

    fun updateInput(value: String) {
        inputField.setTextAndPlaceCursorAtEnd(value)
    }

    /**
     * Applies the command. Immediate results are written to [lines] right away; for a streaming
     * result the echoed input line is added and the pre-computed events are returned for the UI to
     * play with [play]. Any asynchronous work happens during playback.
     */
    fun submit(processor: CommandProcessor): List<CommandEvent>? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        inputField.clearText()
        return applyResult(raw, processor.execute(raw))
    }

    private fun applyResult(raw: String, result: CommandResult): List<CommandEvent>? =
        when (result) {
            is CommandResult.Output -> {
                val next = buildList {
                    add(newLine("› $raw", CommandLineKind.INPUT))
                    if (result.text.isNotEmpty()) {
                        add(newLine(result.text, if (result.failed) CommandLineKind.ERROR else CommandLineKind.OUTPUT))
                    }
                }
                lines = (lines + next).takeLast(MAX_LINES)
                null
            }
            CommandResult.Clear -> {
                lines = emptyList()
                null
            }
            is CommandResult.Stream -> {
                lines = (lines + newLine("› $raw", CommandLineKind.INPUT)).takeLast(MAX_LINES)
                result.events
            }
        }

    /**
     * Plays stream events into the log. [pause] is injectable so unit tests can run without
     * waiting; production uses the real delay.
     */
    suspend fun play(
        events: List<CommandEvent>,
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        // A cancelled older stream must not clear the flag of a newer one.
        val generation = ++playGeneration
        streaming = true
        try {
            for (event in events) {
                when (event) {
                    is CommandEvent.Line -> appendOutput(event.text)
                    is CommandEvent.Progress -> updateLastOutput(event)
                    is CommandEvent.Pause -> pause(event.millis.coerceAtLeast(0))
                    is CommandEvent.Operation -> {
                        val result = event.run()
                        val kind = if (result.failed) CommandLineKind.ERROR else CommandLineKind.OUTPUT
                        result.lines.forEach { appendOutput(it, kind) }
                        if (result.failed) return false
                    }
                }
            }
            return true
        } finally {
            if (generation == playGeneration) streaming = false
        }
    }

    private fun appendOutput(text: String, kind: CommandLineKind = CommandLineKind.OUTPUT) {
        lines = (lines + newLine(text, kind)).takeLast(MAX_LINES)
    }

    /** Progress replaces the last row in place, including the caret's offset inside the bar. */
    private fun updateLastOutput(event: CommandEvent.Progress) {
        val last = lines.lastOrNull()
        val caret = event.cursorOffset.coerceIn(0, event.text.length)
        lines = if (last?.kind == CommandLineKind.OUTPUT) {
            lines.dropLast(1) + last.copy(text = event.text, cursorOffset = caret)
        } else {
            (lines + newLine(event.text, CommandLineKind.OUTPUT, caret)).takeLast(MAX_LINES)
        }
    }

    private fun newLine(text: String, kind: CommandLineKind, caret: Int = text.length) =
        CommandLine(text, kind, caret, ++lineSequence)

    private companion object {
        const val MAX_LINES = 200
        val HISTORY_CLEAR = Regex("history\\s+clear", RegexOption.IGNORE_CASE)
    }
}
