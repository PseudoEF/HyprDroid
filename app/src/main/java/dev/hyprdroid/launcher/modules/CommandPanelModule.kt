package dev.hyprdroid.launcher.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.packages.AndroidPackages
import dev.hyprdroid.launcher.plugins.AndroidLuaPlugins
import dev.hyprdroid.launcher.plugins.PluginWindow
import dev.hyprdroid.launcher.ui.Mocha
import dev.hyprdroid.launcher.workspaceengine.WindowId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class CommandLineKind { SYSTEM, INPUT, OUTPUT }

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
        return when (val result = processor.execute(raw)) {
            is CommandResult.Output -> {
                val next = buildList {
                    add(newLine("› $raw", CommandLineKind.INPUT))
                    if (result.text.isNotEmpty()) add(newLine(result.text, CommandLineKind.OUTPUT))
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
                    is CommandEvent.Progress -> updateLastOutput(event)
                    is CommandEvent.Pause -> pause(event.millis.coerceAtLeast(0))
                    is CommandEvent.Operation -> event.run().forEach(::appendOutput)
                }
            }
        } finally {
            if (generation == playGeneration) streaming = false
        }
    }

    private fun appendOutput(text: String) {
        lines = (lines + newLine(text, CommandLineKind.OUTPUT)).takeLast(MAX_LINES)
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
    }
}

internal object CommandPanelModule : TileModule {
    private const val INPUT_ROW = "terminal-input"

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
        var pluginWindow by remember { mutableStateOf<PluginWindow?>(null) }
        val processor = remember(registry, context) {
            CommandProcessor(
                modules = { registry?.modules?.map { ModuleSummary(it.id, it.title) }.orEmpty() },
                droidfetch = { DroidFetchFormatter.format(DroidFetchSystem.read(context)) },
                packageCommands = PackageCommands { AndroidPackages.manager(context) },
                luaPluginCommands = LuaPluginCommands(
                    runPlugin = { AndroidLuaPlugins.run(context, it) },
                    showWindow = { pluginWindow = it },
                ),
            )
        }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var streamJob by remember { mutableStateOf<Job?>(null) }

        val inputScroll = rememberScrollState()
        val rowPadding = with(LocalDensity.current) { 1.dp.roundToPx() }
        var viewportOrigin by remember { mutableStateOf(Offset.Unspecified) }
        var inputOrigin by remember { mutableStateOf(Offset.Unspecified) }
        var inputLayout by remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
        var inputFocused by remember { mutableStateOf(false) }
        var logLayout by remember { mutableStateOf<Pair<Long, TextLayoutResult>?>(null) }
        val selection = commandState.inputField.selection
        val lastLine = commandState.lines.lastOrNull()

        DisposableEffect(Unit) {
            onDispose { streamJob?.cancel() }
        }

        LaunchedEffect(lastLine, commandState.input) {
            listState.animateScrollToItem(commandState.lines.size)
        }

        val submitCommand: () -> Unit = {
            streamJob?.cancel()
            val events = commandState.submit(processor)
            if (events != null) {
                streamJob = scope.launch { commandState.play(events) }
            }
        }

        val outputTarget = if (commandState.streaming && lastLine != null) {
            val listLayout = listState.layoutInfo
            val layout = logLayout?.takeIf { it.first == lastLine.id }?.second
            val item = listLayout.visibleItemsInfo.lastOrNull { it.key == lastLine.id }
            if (layout != null && item != null && layout.layoutInput.text.text == lastLine.text) {
                viewportCaret(
                    layout.getCursorRect(lastLine.cursorOffset),
                    Offset(0f, (item.offset + rowPadding).toFloat()),
                )?.takeIf { it.bottom > 0 && it.top < listLayout.viewportSize.height }?.let { target ->
                    CursorPlacement(
                        target,
                        row = lastLine.id,
                        rowOffsets = listLayout.visibleItemsInfo.mapNotNull { row ->
                            (row.key as? Long)?.let { it to row.offset }
                        }.toMap(),
                    )
                }
            } else {
                null
            }
        } else {
            null
        }
        val trackingInput = inputFocused && LocalWindowInfo.current.isWindowFocused && selection.collapsed
        val inputVisible = trackingInput && listState.layoutInfo.visibleItemsInfo.any { it.key == INPUT_ROW }
        val inputTarget = if (inputVisible && inputOrigin.isSpecified && viewportOrigin.isSpecified) {
            val layout = inputLayout?.invoke()
            layout?.takeIf { selection.end <= it.layoutInput.text.length }?.let {
                val scroll = inputScroll.value
                viewportCaret(it.getCursorRect(selection.end), inputOrigin - viewportOrigin, scroll)
                    ?.let { target -> CursorPlacement(target, inputScroll = scroll) }
            }
        } else {
            null
        }

        Box(
            modifier.fillMaxSize()
                .padding(horizontal = 6.dp)
                .clipToBounds()
                .onGloballyPositioned { viewportOrigin = it.positionInRoot() },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .testTag("module-command-log-${state.windowId.value}"),
            ) {
                itemsIndexed(commandState.lines, key = { _, line -> line.id }) { index, line ->
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
                            { layout -> logLayout = line.id to layout }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    )
                }
                item(key = INPUT_ROW) {
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
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            BasicTextField(
                                state = commandState.inputField,
                                lineLimits = TextFieldLineLimits.SingleLine,
                                scrollState = inputScroll,
                                textStyle = MaterialTheme.typography.labelSmall.copy(
                                    color = Mocha.Text,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                cursorBrush = SolidColor(
                                    if (commandState.streaming || inputTarget == null) Mocha.Mauve
                                    else Color.Transparent,
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                onKeyboardAction = { submitCommand() },
                                onTextLayout = { getResult -> inputLayout = getResult },
                                modifier = Modifier.weight(1f)
                                    .onFocusChanged { inputFocused = it.isFocused }
                                    .onGloballyPositioned { inputOrigin = it.positionInRoot() }
                                    .testTag("module-command-input-${state.windowId.value}"),
                                decorator = { inner ->
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

            AnimatedTerminalCursor(
                placement = if (commandState.streaming) outputTarget else inputTarget,
                tracking = commandState.streaming || trackingInput,
                modifier = Modifier.matchParentSize(),
            )
        }

        pluginWindow?.let { window ->
            AlertDialog(
                onDismissRequest = { pluginWindow = null },
                title = { Text(window.title) },
                text = {
                    Text(
                        window.text,
                        Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { pluginWindow = null }) { Text("Закрыть") }
                },
            )
        }
    }
}
