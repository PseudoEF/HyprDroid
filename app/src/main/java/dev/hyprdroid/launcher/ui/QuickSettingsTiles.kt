package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.quicksettings.QuickSettingsController

@Composable
internal fun rememberQuickController(): QuickSettingsController {
    val context = LocalContext.current
    val controller = remember(context) { QuickSettingsController.get(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        var observing = false
        fun update() {
            val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (visible != observing) { observing = visible; if (visible) controller.acquire() else controller.release() }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); if (observing) controller.release() }
    }
    return controller
}

/** One renderer/controller for the shade, Home panels, previews and overlay. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickSettingsTile(tile: QuickTile, interactive: Boolean = true, onExternal: () -> Unit = {}) {
    if (tile == QuickTile.BRIGHTNESS || tile == QuickTile.VOLUME) {
        QuickSettingsSlider(tile, interactive, onExternal); return
    }
    val controller = rememberQuickController()
    val states by controller.states.collectAsStateWithLifecycle()
    val state = states[tile] ?: QuickTileState()
    ShellSurface(Modifier.fillMaxWidth().testTag("quick-tile-${tile.name}")) {
        Column(Modifier.fillMaxWidth().combinedClickable(enabled = interactive,
            onClickLabel = tile.title, onLongClickLabel = "Настройки ${tile.title}",
            onLongClick = { if (controller.settings(tile)) onExternal() },
            onClick = { if (controller.tap(tile)) onExternal() })
            .semantics { stateDescription = state.detail; role = Role.Button }
            .heightIn(min = 84.dp).padding(12.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tile.glyph, color = if (state.active == true) Mocha.Mauve else Mocha.Subtext, modifier = Modifier.clearAndSetSemantics {})
                Text(tile.title, style = MaterialTheme.typography.labelLarge)
            }
            Text(state.detail, style = MaterialTheme.typography.bodySmall, color = Mocha.Subtext)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickSettingsSlider(tile: QuickTile, interactive: Boolean = true, onExternal: () -> Unit = {}) {
    val controller = rememberQuickController()
    val states by controller.states.collectAsStateWithLifecycle()
    val state = states[tile] ?: QuickTileState()
    ShellSurface(Modifier.fillMaxWidth().testTag("quick-tile-${tile.name}")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(enabled = interactive,
                onClick = { if (controller.tap(tile)) onExternal() },
                onLongClickLabel = "Настройки ${tile.title}", onLongClick = { if (controller.settings(tile)) onExternal() }), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tile.title, style = MaterialTheme.typography.labelLarge)
                    Text(state.detail, style = MaterialTheme.typography.bodySmall, color = Mocha.Subtext)
                }
                IconButton(onClick = { if (controller.settings(tile)) onExternal() }, enabled = interactive) {
                    Icon(Icons.Default.Settings, "Системные настройки ${tile.title}")
                }
            }
            ShellSlider(state.value ?: 0f, {}, enabled = interactive && state.adjustable,
                onCommit = { controller.adjust(tile, it) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("quick-slider-${tile.name}").semantics { contentDescription = tile.title })
            if (tile == QuickTile.BRIGHTNESS) SettingsDetails("О шкале яркости") {
                Text("Оценка API · шкала Android может отличаться", style = MaterialTheme.typography.labelSmall, color = Mocha.Subtext)
            }
        }
    }
}

/** Two columns, no nested scrolling; range controls are separate, never duplicate grid tiles. */
@Composable
internal fun QuickSettingsContent(modules: List<String>, interactive: Boolean = true, onExternal: () -> Unit = {}) {
    val controls = modules.filter { it == QuickTile.BRIGHTNESS.moduleId || it == QuickTile.VOLUME.moduleId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        controls.forEach { QuickSettingsSlider(requireNotNull(QuickTile.fromModule(it)), interactive, onExternal) }
        Column(Modifier.testTag("quick-grid"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            modules.filterNot { it in controls }.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { id -> Box(Modifier.weight(1f)) {
                        QuickTile.fromModule(id)?.let { QuickSettingsTile(it, interactive, onExternal) }
                            ?: Text("Недоступный модуль", Modifier.padding(12.dp))
                    } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
