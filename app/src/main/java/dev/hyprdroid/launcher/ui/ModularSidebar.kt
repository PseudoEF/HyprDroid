package dev.hyprdroid.launcher.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.hyprdroid.launcher.model.WorkspaceItem
import dev.hyprdroid.launcher.model.SidebarModule
import dev.hyprdroid.launcher.model.PanelSide
import dev.hyprdroid.launcher.model.PanelSettings
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.widgets.PanelWidgetHost
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

// Typed chrome keys cannot collide with persisted shortcut IDs.
private enum class SidebarChrome { STATUS, TITLE, SEARCH, EMPTY }
// Serializable value keys are Bundle-saveable and cannot collide with raw shortcut IDs.
private data class PanelModuleKey(val id: String) : java.io.Serializable

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModularSidebar(
    state: WorkspaceUiState, onClose: () -> Unit, onLaunch: (WorkspaceItem.AppShortcut) -> Unit,
    onPin: (WorkspaceItem.AppShortcut, Boolean) -> Unit, onRemove: (String) -> Unit,
    onReorder: (String, Int) -> Unit, onPlaceBefore: (String, String?, () -> Unit) -> Unit,
    panelModifier: Modifier = Modifier,
    onStopOverlay: (() -> Unit)? = null,
    side: PanelSide = PanelSide.RIGHT,
    allowPanelEditing: Boolean = true,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var resetToken by remember { mutableIntStateOf(0) }
    val entries = remember(state.sidebarEntries) { state.sidebarEntries.associateBy { it.item.id } }
    val storedItems = state.shell?.sidebar.orEmpty()
    val enabled = state.shell != null && !state.shellError
    val context = LocalContext.current
    val repository = remember(context) { ShellRepository(context) }
    val scope = rememberCoroutineScope()
    var moduleEditing by remember { mutableStateOf(false) }
    var editorBase by remember { mutableStateOf(PanelSettings()) }
    var editorDraft by remember { mutableStateOf(PanelSettings()) }
    var initialModule by remember { mutableStateOf<String?>(null) }
    var savingModules by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    fun editModules(id: String? = null) {
        state.shell?.panels?.get(side)?.let {
            editorBase = it; editorDraft = it; initialModule = id; moduleEditing = true; editing = false; adding = false
        }
    }
    fun saveModules() {
        val value = editorDraft
        scope.launch {
            savingModules = true
            try {
                repository.setPanel(side, value, editorBase)
                PanelWidgetHost.get(context).cleanup(repository)
                moduleEditing = false; editorError = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { editorError = "Не удалось сохранить. Черновик оставлен; повторите попытку." }
            finally { savingModules = false }
        }
    }
    if (allowPanelEditing) BackHandler(moduleEditing) { if (!savingModules) moduleEditing = false }
    val modules = state.shell?.panels?.get(side)?.modules.orEmpty()
    val androidWidgets = state.shell?.panels?.get(side)?.widgets.orEmpty().associateBy { it.moduleId }
    val tag = if (side == PanelSide.RIGHT) "sidebar" else "left-panel"
    val closeLabel = if (side == PanelSide.RIGHT) "Закрыть Sidebar" else "Закрыть левую панель"
    val closeSign = if (side == PanelSide.RIGHT) 1f else -1f
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val dismiss = { keyboard?.hide(); focus.clearFocus(force = true); onClose() }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    val grid = rememberLazyGridState()
    val drag = remember(grid) { WorkspaceDragState(grid, reserveRemoveTarget = false) }
    val move by rememberUpdatedState(onPlaceBefore)
    val haptics = LocalHapticFeedback.current
    drag.gridDensity = LocalDensity.current.density
    drag.onActive = { if (it) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    drag.onDrop = { id, before -> move(id, before) { resetToken++ } }
    LaunchedEffect(storedItems, resetToken) { drag.finish(false); drag.items = storedItems }
    LaunchedEffect(editing, adding, enabled) { if (!editing || adding || !enabled) drag.finish(false) }
    LaunchedEffect(adding) { grid.requestScrollToItem(0) }
    DisposableEffect(drag) { onDispose { drag.finish(false) } }
    DragAutoScroll(drag, with(LocalDensity.current) { 56.dp.toPx() })

    BoxWithConstraints(Modifier.fillMaxSize().imePadding().testTag("$tag-overlay")) {
        val heightLimit = maxHeight * .8f
        val panelWidth = (maxWidth * (if (moduleEditing) editorDraft.widthPercent else state.shell?.panels?.get(side)?.widthPercent ?: 80) / 100f).coerceAtMost(600.dp)
        Box(Modifier.fillMaxSize().background(Mocha.Base.copy(alpha = .45f)).testTag("$tag-scrim")
            .clickable(onClickLabel = closeLabel, onClick = dismiss))
        Surface(color = Mocha.Mantle.copy(alpha = .97f), contentColor = Mocha.Text,
            shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Mocha.Mauve.copy(alpha = .45f)),
            modifier = Modifier.align(if (side == PanelSide.RIGHT) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft).safeDrawingPadding().padding(12.dp)
                .width(panelWidth).heightIn(max = heightLimit)
                .animateContentSize(tween(180)).then(panelModifier).testTag(tag)
                .sidebarClosingGesture(enabled = !editing && !moduleEditing && drag.draggedId == null, sign = closeSign, onClose = dismiss)) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.testTag("$tag-header").sidebarClosingGesture(sign = closeSign, onClose = dismiss), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (side == PanelSide.RIGHT) "Sidebar" else "Левая панель", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = dismiss) { Icon(Icons.Default.Close, closeLabel) }
                }
                onStopOverlay?.let { stop -> TextButton(onClick = stop, modifier = Modifier.testTag("stop-overlay")) { Text("Отключить overlay") } }
                if (moduleEditing) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("inline-panel-editor")) {
                        Text("Предпросмотр · изменения не сохранены", style = MaterialTheme.typography.labelSmall)
                        PanelWidthControl(editorDraft, !savingModules) { editorDraft = it }
                        PanelModuleEditor(editorDraft, { editorDraft = it }, !savingModules, initialModule, inlinePreview = true)
                    }
                    editorError?.let { Text(it, color = Mocha.Red) }
                    FlowRow {
                        Button(onClick = ::saveModules, enabled = !savingModules, modifier = Modifier.testTag("inline-panel-save")) { Text("Сохранить") }
                        TextButton(onClick = { moduleEditing = false }, enabled = !savingModules, modifier = Modifier.testTag("inline-panel-cancel")) { Text("Отмена") }
                    }
                } else {
                if (allowPanelEditing) TextButton(onClick = { editModules() }, enabled = enabled && !editing,
                    modifier = Modifier.testTag("edit-panel-modules")) { Text("Редактировать панель") }
                if (LocalGlobalWidgetSurface.current && androidWidgets.isNotEmpty()) Text("Android может ограничивать действия виджетов в overlay. Привязка и настройка доступны в Home.", style = MaterialTheme.typography.bodySmall, color = Mocha.Subtext)
                if ("QUICK_LAUNCH" in modules && side == PanelSide.RIGHT) FlowRow {
                    TextButton(onClick = { editing = !editing; adding = false }, enabled = enabled) { Text(if (editing) "Готово" else "Редактировать") }
                    if (editing) TextButton(onClick = { adding = !adding }, enabled = enabled) { Text(if (adding) "К панели" else "Добавить ярлык") }
                }
                if (editing && !adding) Text("Удерживайте для переноса · касание открывает действия", style = MaterialTheme.typography.bodySmall, color = Mocha.Subtext)
                if (!enabled) Text("Хранилище панели недоступно. Повторите чтение через меню оболочки.")
                LazyVerticalGrid(columns = GridCells.Adaptive(80.dp), state = grid,
                    userScrollEnabled = drag.draggedId == null,
                    modifier = Modifier.weight(1f, fill = false).testTag(if (adding) "sidebar-picker" else "sidebar-items")
                        .workspaceDrag(drag, editing && !adding && enabled),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (module in modules) {
                    if (module == "QUICK_LAUNCH" && side == PanelSide.RIGHT) {
                        item(key = SidebarChrome.TITLE, span = { GridItemSpan(maxLineSpan) }) {
                            Text("Quick Launch", style = MaterialTheme.typography.labelLarge, color = Mocha.Subtext, modifier = Modifier.testTag("sidebar-quick-launch"))
                        }
                        if (adding) {
                            item(key = SidebarChrome.SEARCH, span = { GridItemSpan(maxLineSpan) }) {
                                Column {
                                    OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                                        label = { Text("Поиск ярлыков") }, modifier = Modifier.fillMaxWidth())
                                    Text("Ярлыки Android доступны домашнему экрану HyprDroid.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            val candidates = state.sidebarEntries.filter { it.app.label.contains(search, ignoreCase = true) }
                            if (candidates.isEmpty()) item(key = SidebarChrome.EMPTY, span = { GridItemSpan(maxLineSpan) }) { Text(if (state.loadingApps) "Загрузка…" else "Доступных ярлыков нет") }
                            items(candidates, key = { it.item.id }) { entry ->
                                AppTile(entry.app, entry.app.componentId, { onPin(entry.item, storedItems.none { it.id == entry.item.id }) }, emptyList(),
                                    selected = storedItems.any { it.id == entry.item.id }, iconSizeDp = 40, tileMinHeightDp = 88,
                                    modifier = Modifier.testTag("sidebar-choice-${entry.item.id}"))
                            }
                        } else {
                            if (drag.items.isEmpty()) item(key = SidebarChrome.EMPTY, span = { GridItemSpan(maxLineSpan) }) { Text("Панель пуста. Нажмите «Редактировать», чтобы добавить приложения или ярлыки.") }
                            itemsIndexed(drag.items, key = { _, item -> item.id }) { index, item ->
                                val shortcut = item as? WorkspaceItem.AppShortcut
                                val dragging = drag.draggedId == item.id
                                val actions = listOf(
                                    AppMenuAction("Выше", enabled && index > 0) { onReorder(item.id, -1) },
                                    AppMenuAction("Ниже", enabled && index < drag.items.lastIndex) { onReorder(item.id, 1) },
                                    AppMenuAction("Убрать из Sidebar", enabled) { onRemove(item.id) },
                                )
                                AppTile(entries[item.id]?.app, if (item is WorkspaceItem.WidgetPlaceholder) item.title else "Ярлык недоступен",
                                    { if (!editing && shortcut != null) onLaunch(shortcut) }, actions,
                                    iconSizeDp = 40, tileMinHeightDp = 88, dragEditing = editing, editorMenuOnTap = true, selected = dragging,
                                    modifier = Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                                        .then(if (!dragging) Modifier.animateItem() else Modifier)
                                        .graphicsLayer {
                                            val offset = drag.translation(item.id)
                                            translationX = offset.x.toFloat(); translationY = offset.y.toFloat()
                                            scaleX = if (dragging) 1.06f else 1f; scaleY = scaleX
                                        }.semantics {
                                            customActions = actions.filter { it.enabled }.map { action -> CustomAccessibilityAction(action.label) { action.onClick(); true } }
                                        }.testTag("sidebar-item-${item.id}"))
                            }
                        }
                    } else if (!adding && !editing) item(key = PanelModuleKey(module), span = { GridItemSpan(maxLineSpan) }) {
                        androidWidgets[module]?.let { PanelAndroidWidget(it,
                            onEdit = if (allowPanelEditing) ({ editModules(module) }) else null, interactive = allowPanelEditing) } ?: PanelStatusModule(module)
                    }
                    }
                    if (modules.isEmpty()) item(key = SidebarChrome.EMPTY, span = { GridItemSpan(maxLineSpan) }) { Text("Модули скрыты. Настройте их через меню верхней панели.") }
                }
                }
            }
        }
    }
}
