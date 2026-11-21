package dev.hyprdroid.launcher.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.ui.Mocha
import dev.hyprdroid.launcher.workspaceengine.WindowId

/**
 * Renders one module instance inside a tile. The header switches modules without touching the
 * layout geometry; unknown and unassigned ids show the same neutral picker.
 */
@Composable
internal fun ModuleHost(
    windowId: WindowId,
    moduleId: String,
    registry: ModuleRegistry,
    onSelectModule: (String) -> Unit,
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {},
) {
    val module = registry.find(moduleId)
    Column(modifier.fillMaxSize()) {
        ModuleHeader(windowId, module, registry, onSelectModule, headerActions)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (module == null) {
                ModulePicker(windowId, moduleId, registry, onSelectModule)
            } else {
                val state = remember(windowId.value, module.id) { module.createState(windowId) }
                DisposableEffect(state) { onDispose { state.dispose() } }
                module.Content(state, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ModuleHeader(
    windowId: WindowId,
    module: TileModule?,
    registry: ModuleRegistry,
    onSelectModule: (String) -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }
                    .padding(start = 6.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
                    .testTag("tiling-module-menu-${windowId.value}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModuleGlyph(module?.glyph, tint = if (module == null) Mocha.Subtext else Mocha.Lavender)
                Text(
                    text = module?.title ?: "Выбрать модуль",
                    style = MaterialTheme.typography.labelSmall,
                    color = Mocha.Subtext,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Сменить модуль",
                    tint = Mocha.Subtext,
                    modifier = Modifier.width(14.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                registry.modules.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { ModuleGlyph(item.glyph) },
                        onClick = {
                            expanded = false
                            onSelectModule(item.id)
                        },
                        modifier = Modifier.testTag("tiling-module-option-${item.id}"),
                    )
                }
            }
        }
        actions()
    }
}

/** Compact one-tap picker used for fresh tiles and for ids that no longer exist. */
@Composable
private fun ModulePicker(
    windowId: WindowId,
    moduleId: String,
    registry: ModuleRegistry,
    onSelectModule: (String) -> Unit,
) {
    val unknown = moduleId.isNotBlank() && moduleId != ModuleRegistry.UNASSIGNED
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(
            text = if (unknown) "Модуль недоступен. Выберите другой." else "Выберите модуль",
            style = MaterialTheme.typography.labelSmall,
            color = if (unknown) Mocha.Red else Mocha.Subtext,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("module-picker-${windowId.value}"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(registry.modules, key = { it.id }) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Mocha.Mantle.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .clickable { onSelectModule(item.id) }
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                        .testTag("tiling-module-pick-${item.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ModuleGlyph(item.glyph)
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = Mocha.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = Mocha.Subtext,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModuleGlyph(
    glyph: ModuleGlyph?,
    modifier: Modifier = Modifier,
    tint: Color = Mocha.Lavender,
) {
    val symbol = when (glyph) {
        ModuleGlyph.CLOCK -> "◷"
        ModuleGlyph.SYSTEM -> "▤"
        ModuleGlyph.APPS -> "▦"
        ModuleGlyph.COMMAND -> "›_"
        null -> "·"
    }
    Text(
        text = symbol,
        modifier = modifier.widthIn(min = 16.dp),
        color = tint,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
    )
}
