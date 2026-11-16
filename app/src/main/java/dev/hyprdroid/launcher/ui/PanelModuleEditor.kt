package dev.hyprdroid.launcher.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.model.*

internal fun PanelSettings.moduleTitle(id: String) = widgets.find { it.moduleId == id }?.title
    ?: PanelModule.entries.find { it.name == id }?.title ?: "Недоступный модуль"
internal fun PanelSettings.moveModule(id: String, delta: Int): PanelSettings {
    val index = modules.indexOf(id)
    if (index < 0) return this
    return copy(modules = modules.toMutableList().apply { add((index + delta).coerceIn(0, lastIndex), removeAt(index)) })
}

/** A compact list: at most one detail section. Shared by settings and the actual panel editor. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun PanelModuleEditor(draft: PanelSettings, onChange: (PanelSettings) -> Unit, enabled: Boolean,
    initialSelection: String? = null, inlinePreview: Boolean = false) {
    var selected by rememberSaveable { mutableStateOf(initialSelection) }
    if (draft.modules.isEmpty()) Text("Панель пуста. Добавьте модуль в настройках содержимого.")
    draft.modules.forEachIndexed { index, id ->
        val expanded = selected == id
        val bringIntoView = remember(id) { BringIntoViewRequester() }
        LaunchedEffect(expanded) { if (expanded) { withFrameNanos { }; bringIntoView.bringIntoView() } }
        Column(Modifier.fillMaxWidth().animateContentSize().testTag("panel-module-row-$id")) {
            ListItem(headlineContent = { Text(draft.moduleTitle(id), maxLines = 2) },
                supportingContent = { if (expanded) Text("Выбран · настройки раскрыты") },
                trailingContent = { Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null) },
                modifier = Modifier.bringIntoViewRequester(bringIntoView).clickable(enabled, onClickLabel = "Настройки ${draft.moduleTitle(id)}") { selected = if (expanded) null else id }
                    .testTag("module-select-$id"))
            if (expanded) Column(Modifier.padding(horizontal = 8.dp).testTag("module-details-$id")) {
                FlowRow {
                    IconButton(onClick = { onChange(draft.moveModule(id, -1)) }, enabled = enabled && index > 0,
                        modifier = Modifier.testTag("module-up-$id")) { Icon(Icons.Default.KeyboardArrowUp, "Выше") }
                    IconButton(onClick = { onChange(draft.moveModule(id, 1)) }, enabled = enabled && index < draft.modules.lastIndex,
                        modifier = Modifier.testTag("module-down-$id")) { Icon(Icons.Default.KeyboardArrowDown, "Ниже") }
                    TextButton(onClick = { onChange(draft.copy(modules = draft.modules - id, widgets = draft.widgets.filterNot { it.moduleId == id })); selected = null },
                        enabled = enabled, modifier = Modifier.testTag("module-remove-$id")) { Text("Убрать") }
                }
                val widget = draft.widgets.find { it.moduleId == id }
                if (widget != null) {
                    val resize: (PanelWidget) -> Unit = { next -> onChange(draft.copy(widgets = draft.widgets.map { if (it.appWidgetId == next.appWidgetId) next else it })) }
                    if (inlinePreview) {
                        Text("Предпросмотр · действия виджета отключены", style = MaterialTheme.typography.labelSmall)
                        PanelAndroidWidget(widget, interactive = false, onResize = if (enabled) resize else null)
                    }
                    WidgetSizeControls(widget, enabled, resize)
                } else if (inlinePreview) PanelStatusModule(id)
            }
            HorizontalDivider()
        }
    }
}
