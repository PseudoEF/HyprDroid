package dev.hyprdroid.launcher.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.ui.Mocha
import dev.hyprdroid.launcher.workspaceengine.WindowId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class CommandLineKind { SYSTEM, INPUT, OUTPUT }

internal data class CommandLine(val text: String, val kind: CommandLineKind)

internal class CommandPanelState(windowId: WindowId) : ModuleState(windowId) {
    var lines by mutableStateOf(
        listOf(CommandLine("HyprDroid · безопасные команды. Введите help.", CommandLineKind.SYSTEM)),
    )
        private set
    var input by mutableStateOf("")
        private set

    /** True while a [CommandResult.Stream] is being played into [lines]. */
    var streaming by mutableStateOf(false)
        private set

    private var playGeneration = 0

    fun updateInput(value: String) {
        input = value
    }

    /**
     * Applies the command. Immediate results are written to [lines] right away; for a streaming
     * result the echoed input line is added and the pre-computed events are returned for the UI to
     * play with [play]. The processor never starts a process, so this stays fully deterministic.
     */
    fun submit(processor: CommandProcessor): List<CommandEvent>? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        input = ""
        return when (val result = processor.execute(raw)) {
            is CommandResult.Output -> {
                val next = buildList {
                    add(CommandLine("› $raw", CommandLineKind.INPUT))
                    if (result.text.isNotEmpty()) add(CommandLine(result.text, CommandLineKind.OUTPUT))
                }
                lines = (lines + next).takeLast(MAX_LINES)
                null
            }
            CommandResult.Clear -> {
                lines = emptyList()
                null
            }
            is CommandResult.Stream -> {
                lines = (lines + CommandLine("› $raw", CommandLineKind.INPUT)).takeLast(MAX_LINES)
                result.events
            }
        }
    }

    /**
     * Plays stream events into the log. [pause] is injectable so unit tests can run without
     * waiting; production uses the real delay.
     */
    suspend fun play(
        events: List<CommandEvent>,
        pause: suspend (Long) -> Unit = { delay(it) },
    ) {
        // A cancelled older stream must not clear the flag of a newer one.
        val generation = ++playGeneration
        streaming = true
        try {
            for (event in events) {
                when (event) {
                    is CommandEvent.Line -> appendOutput(event.text)
                    is CommandEvent.Progress -> updateLastOutput(event.text)
                    is CommandEvent.Pause -> pause(event.millis.coerceAtLeast(0))
                }
            }
        } finally {
            if (generation == playGeneration) streaming = false
        }
    }

    private fun appendOutput(text: String) {
        lines = (lines + CommandLine(text, CommandLineKind.OUTPUT)).takeLast(MAX_LINES)
    }

    /** Progress replaces the most recent output line, so the bar updates in place. */
    private fun updateLastOutput(text: String) {
        val last = lines.lastOrNull()
        lines = if (last?.kind == CommandLineKind.OUTPUT) {
            lines.dropLast(1) + CommandLine(text, CommandLineKind.OUTPUT)
        } else {
            (lines + CommandLine(text, CommandLineKind.OUTPUT)).takeLast(MAX_LINES)
        }
    }

    private companion object {
        const val MAX_LINES = 200
    }
}

internal object CommandPanelModule : TileModule {
    override val id = "hyprdroid.command"
    override val title = "Команды"
    override val summary = "Безопасная командная панель"
    override val glyph = ModuleGlyph.COMMAND

    override fun createState(windowId: WindowId): ModuleState = CommandPanelState(windowId)

    @Composable
    override fun Content(state: ModuleState, modifier: Modifier) {
        val commandState = state as CommandPanelState
        val registry = LocalTileModuleRegistry.current
        val context = LocalContext.current.applicationContext
        val processor = remember(registry, context) {
            CommandProcessor(
                modules = { registry?.modules?.map { ModuleSummary(it.id, it.title) }.orEmpty() },
                droidfetch = { DroidFetchFormatter.format(DroidFetchSystem.read(context)) },
            )
        }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var streamJob by remember { mutableStateOf<Job?>(null) }

        // Cursor geometry, in root coordinates, converted to panel-local pixels below.
        var panelOrigin by remember { mutableStateOf(Offset.Zero) }
        var logOrigin by remember { mutableStateOf(Offset.Zero) }
        var logCaret by remember { mutableStateOf(Rect.Zero) }

        DisposableEffect(Unit) {
            onDispose { streamJob?.cancel() }
        }

        LaunchedEffect(commandState.lines.size, commandState.input.length) {
            listState.animateScrollToItem(commandState.lines.size)
        }

        val submitCommand: () -> Unit = {
            streamJob?.cancel()
            val events = commandState.submit(processor)
            if (events != null) {
                streamJob = scope.launch { commandState.play(events) }
            }
        }

        val logTarget = CursorTarget(
            x = logOrigin.x - panelOrigin.x + logCaret.left,
            top = logOrigin.y - panelOrigin.y + logCaret.top,
            bottom = logOrigin.y - panelOrigin.y + logCaret.bottom,
        )

        Box(
            modifier.fillMaxSize().onGloballyPositioned { panelOrigin = it.positionInRoot() },
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth()
                        .weight(1f)
                        .testTag("module-command-log-${state.windowId.value}"),
                ) {
                    itemsIndexed(commandState.lines) { index, line ->
                        val isCursorLine = index == commandState.lines.lastIndex
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = when (line.kind) {
                                CommandLineKind.SYSTEM -> Mocha.Subtext
                                CommandLineKind.INPUT -> Mocha.Lavender
                                CommandLineKind.OUTPUT -> Mocha.Text
                            },
                            onTextLayout = if (isCursorLine) {
                                { layout -> logCaret = layout.getCursorRect(layout.layoutInput.text.length) }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .padding(vertical = 1.dp)
                                .then(
                                    if (isCursorLine) {
                                        Modifier.onGloballyPositioned { logOrigin = it.positionInRoot() }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .background(Mocha.Mantle.copy(alpha = 0.7f), RoundedCornerShape(7.dp))
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "›",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = Mocha.Mauve,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            BasicTextField(
                                value = commandState.input,
                                onValueChange = commandState::updateInput,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.labelSmall.copy(
                                    color = Mocha.Text,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                // While streaming, the animated block is the only caret; otherwise
                                // the native caret keeps mid-string editing predictable.
                                cursorBrush = SolidColor(
                                    if (commandState.streaming) Color.Transparent else Mocha.Mauve,
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { submitCommand() }),
                                modifier = Modifier.weight(1f)
                                    .testTag("module-command-input-${state.windowId.value}"),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxWidth()) {
                                        if (commandState.input.isEmpty()) {
                                            Text(
                                                "help",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = Mocha.Subtext,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // The dynamic cursor follows the streamed output; in idle input mode the native
            // caret is visible instead, so editing inside the string stays accurate.
            AnimatedTerminalCursor(
                target = if (commandState.streaming) logTarget else null,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
