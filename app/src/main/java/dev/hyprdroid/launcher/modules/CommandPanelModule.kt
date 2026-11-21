package dev.hyprdroid.launcher.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.ui.Mocha
import dev.hyprdroid.launcher.workspaceengine.WindowId

internal enum class CommandLineKind { SYSTEM, INPUT, OUTPUT }

internal data class CommandLine(val text: String, val kind: CommandLineKind)

internal class CommandPanelState(windowId: WindowId) : ModuleState(windowId) {
    var lines by mutableStateOf(
        listOf(CommandLine("HyprDroid · безопасные команды. Введите help.", CommandLineKind.SYSTEM)),
    )
        private set
    var input by mutableStateOf("")
        private set

    fun updateInput(value: String) {
        input = value
    }

    fun submit(processor: CommandProcessor) {
        val raw = input.trim()
        if (raw.isEmpty()) return
        input = ""
        when (val result = processor.execute(raw)) {
            is CommandResult.Output -> {
                val next = buildList {
                    add(CommandLine("› $raw", CommandLineKind.INPUT))
                    if (result.text.isNotEmpty()) add(CommandLine(result.text, CommandLineKind.OUTPUT))
                }
                lines = (lines + next).takeLast(MAX_LINES)
            }
            CommandResult.Clear -> lines = emptyList()
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
        var cursorX by remember { mutableStateOf(0f) }
        var cursorTop by remember { mutableStateOf(0f) }
        var cursorBottom by remember { mutableStateOf(0f) }

        LaunchedEffect(commandState.lines.size) {
            if (commandState.lines.isNotEmpty()) listState.scrollToItem(commandState.lines.lastIndex)
        }

        Column(modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("module-command-log-${state.windowId.value}"),
            ) {
                items(commandState.lines) { line ->
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (line.kind) {
                            CommandLineKind.SYSTEM -> Mocha.Subtext
                            CommandLineKind.INPUT -> Mocha.Lavender
                            CommandLineKind.OUTPUT -> Mocha.Text
                        },
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
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
                    cursorBrush = SolidColor(Mocha.Mauve),
                    onTextLayout = { layout ->
                        val rect = layout.getCursorRect(
                            commandState.input.length
                        )
                        cursorX = rect.left
                        cursorTop = rect.top
                        cursorBottom = rect.bottom
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { commandState.submit(processor) }),
                    modifier = Modifier.weight(1f).testTag("module-command-input-${state.windowId.value}"),
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
                            AnimatedTerminalCursor(
                                targetX = cursorX,
                                targetTop = cursorTop,
                                targetBottom = cursorBottom,
                            )
                        }
                    },
                )
            }
        }
    }
}
