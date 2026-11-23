package dev.hyprdroid.launcher.modules

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
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
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

internal object CommandPanelModule : TileModule {
    private const val PROMPT_ROW = Long.MIN_VALUE

    override val id = "hyprdroid.command"
    override val title = "Команды"
    override val summary = "Безопасная командная панель"
    override val glyph = ModuleGlyph.COMMAND

    override fun createState(windowId: WindowId): ModuleState = CommandPanelState(windowId)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content(state: ModuleState, modifier: Modifier) {
        val commandState = state as CommandPanelState
        val registry = LocalTileModuleRegistry.current
        val tilePort = LocalTileCommandPort.current
        val context = LocalContext.current.applicationContext
        val history = remember(context) { StoredTerminalHistory(context) }
        val historyEntries by history.navigationCommands.collectAsState(initial = emptyList())
        val navigation = remember { HistoryNavigation() }
        val clipboard = LocalClipboardManager.current
        var pluginWindow by remember { mutableStateOf<PluginWindow?>(null) }
        val processor = remember(registry, context, tilePort) {
            CommandProcessor(
                modules = { registry?.modules?.map { ModuleSummary(it.id, it.title) }.orEmpty() },
                droidfetch = { DroidFetchFormatter.format(DroidFetchSystem.read(context)) },
                history = history,
                tileCommands = tilePort?.let { port ->
                    TileCommands(port, authorizeExternal = { AndroidPackages.manager(context).verifiedHelloTile() }) {
                        AndroidPackages.manager(context).requireDroidFetchTileSample()
                    }
                },
                packageCommands = PackageCommands { AndroidPackages.manager(context) },
                luaPluginCommands = LuaPluginCommands(
                    runPlugin = { AndroidLuaPlugins.run(context, it) },
                    showWindow = { pluginWindow = it },
                ),
            )
        }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var preview by remember { mutableStateOf<List<String>?>(null) }
        var followOutput by remember { mutableStateOf(true) }
        var suggestionsFor by remember { mutableStateOf("" to emptyList<String>()) }
        val input = commandState.input
        val suggestions = suggestionsFor.second.takeIf { suggestionsFor.first == input }.orEmpty()
        val scrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) followOutput = false
                    return Offset.Zero
                }
            }
        }

        val inputScroll = rememberScrollState()
        val inputVisibility = remember { BringIntoViewRequester() }
        val rowPadding = with(LocalDensity.current) { 1.dp.roundToPx() }
        var viewportOrigin by remember { mutableStateOf(Offset.Unspecified) }
        var inputOrigin by remember { mutableStateOf(Offset.Unspecified) }
        var inputLayout by remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
        var inputFocused by remember { mutableStateOf(false) }
        var logLayout by remember { mutableStateOf<Pair<Long, TextLayoutResult>?>(null) }
        val selection = commandState.inputField.selection
        val lastLine = commandState.lines.lastOrNull()

        LaunchedEffect(input, commandState.running) {
            val completion = TerminalCompletion(input)
            suggestionsFor = input to withContext(Dispatchers.IO) {
                val manager by lazy { AndroidPackages.manager(context) }
                val catalog = try {
                    if (completion.packageSource in setOf(CompletionPackages.CATALOG, CompletionPackages.BOTH)) {
                        manager.available().map { it.id }
                    } else emptyList()
                } catch (_: IOException) {
                    emptyList()
                }
                val installed = try {
                    if (completion.packageSource in setOf(CompletionPackages.INSTALLED, CompletionPackages.BOTH)) {
                        manager.installed().map { it.id }
                    } else emptyList()
                } catch (_: IOException) {
                    emptyList()
                }
                completion.suggestions(catalog, installed)
            }
        }

        LaunchedEffect(lastLine, followOutput, suggestions.isNotEmpty()) {
            if (followOutput) listState.scrollToItem(commandState.lines.size)
        }
        LaunchedEffect(inputFocused, input, selection) {
            if (inputFocused && !commandState.running) inputVisibility.bringIntoView()
        }
        LaunchedEffect(input, commandState.running) {
            val commands = parseTerminalInput(input)
            if (!commandState.running && commands.size in 2..BATCH_LIMIT &&
                commands.all { it.length <= COMMAND_LENGTH_LIMIT }
            ) {
                preview = commands
            }
        }

        fun run(commands: List<String>) {
            navigation.reset()
            commandState.inputField.clearText()
            scope.launch { commandState.runCommands(commands, processor::execute, history) }
        }

        val submitCommand: () -> Unit = {
            if (!commandState.running) {
                val commands = parseTerminalInput(commandState.input)
                when {
                    commands.size > BATCH_LIMIT -> commandState.inputError("Максимум $BATCH_LIMIT команд в пакете.")
                    commands.any { it.length > COMMAND_LENGTH_LIMIT } ->
                        commandState.inputError("Команда длиннее $COMMAND_LENGTH_LIMIT символов.")
                    commands.size > 1 -> preview = commands
                    commands.size == 1 -> run(commands)
                }
            }
        }

        val trackingInput = inputFocused && LocalWindowInfo.current.isWindowFocused && selection.collapsed
        val listLayout = listState.layoutInfo
        val rowOffsets = listLayout.visibleItemsInfo.mapNotNull { row ->
            (row.key as? Long)?.let { it to row.offset }
        }.toMap()
        val outputTarget = if (commandState.streaming && lastLine != null) {
            val layout = logLayout?.takeIf { it.first == lastLine.id }?.second
            if (layout != null && layout.layoutInput.text.text == lastLine.text) {
                val y = rowOffsets[lastLine.id]
                y?.let {
                    inlineCursorPlacement(
                        layout.getCursorRect(lastLine.cursorOffset),
                        Offset(0f, (it + rowPadding).toFloat()),
                        listLayout.viewportSize.height,
                        lastLine.id,
                        rowOffsets,
                    )
                }
            } else null
        } else null
        val inputTarget = if (trackingInput && PROMPT_ROW in rowOffsets &&
            inputOrigin.isSpecified && viewportOrigin.isSpecified
        ) {
            val layout = inputLayout?.invoke()
            layout?.takeIf { selection.end <= it.layoutInput.text.length }?.let {
                inlineCursorPlacement(
                    it.getCursorRect(selection.end),
                    inputOrigin - viewportOrigin,
                    listLayout.viewportSize.height,
                    PROMPT_ROW,
                    rowOffsets,
                    inputScroll.value,
                )
            }
        } else null

        Column(modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            if (!followOutput || commandState.remaining > 0) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    if (!followOutput) {
                        TextButton(onClick = { followOutput = true }) { Text("К концу") }
                    }
                    if (commandState.remaining > 0) {
                        TextButton(onClick = commandState::cancelRemaining) { Text("Отменить очередь") }
                    }
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .onGloballyPositioned { viewportOrigin = it.positionInRoot() },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection)
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
                                CommandLineKind.ERROR -> Mocha.Red
                            },
                            onTextLayout = if (isCursorLine) {
                                { layout -> logLayout = line.id to layout }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        )
                    }
                    item(key = PROMPT_ROW) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .testTag("module-command-prompt-${state.windowId.value}"),
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
                                        .bringIntoViewRequester(inputVisibility)
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
                            TextButton(
                                onClick = submitCommand,
                                enabled = !commandState.running && input.isNotBlank(),
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) { Text("Пуск") }
                        }
                    }
                    if (suggestions.isNotEmpty() && !commandState.running) {
                        item(key = "terminal-suggestions") {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .testTag("module-command-suggestions-${state.windowId.value}"),
                            ) {
                                suggestions.forEach { suggestion ->
                                    TextButton(onClick = {
                                        navigation.reset()
                                        commandState.updateInput(suggestion)
                                    }) { Text(suggestion, maxLines = 1) }
                                }
                            }
                        }
                    }
                    if (!commandState.running) {
                        item(key = "terminal-edit-actions") {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                TextButton(enabled = historyEntries.isNotEmpty(), onClick = {
                                    commandState.updateInput(navigation.previous(input, historyEntries))
                                }) { Text("Ранее") }
                                TextButton(onClick = {
                                    commandState.updateInput(navigation.next(input))
                                }) { Text("Позже") }
                                TextButton(onClick = {
                                    clipboard.getText()?.text?.let {
                                        navigation.reset()
                                        commandState.inputField.edit { replace(selection.min, selection.max, it) }
                                    }
                                }) { Text("Вставить") }
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
        }

        preview?.let { commands ->
            AlertDialog(
                onDismissRequest = { preview = null },
                title = { Text("Выполнить ${commands.size} команд?") },
                text = {
                    Text(
                        commands.joinToString("\n"),
                        Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        preview = null
                        run(commands)
                    }) { Text("Выполнить") }
                },
                dismissButton = {
                    TextButton(onClick = { preview = null }) { Text("Отмена") }
                },
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
