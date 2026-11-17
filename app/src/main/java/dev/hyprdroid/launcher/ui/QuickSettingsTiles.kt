package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hyprdroid.launcher.data.ShellRepository
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

/** Identical rendering/actions in topbar, Home panels and WindowManager panels. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickSettingsTile(tile: QuickTile, interactive: Boolean = true, onExternal: () -> Unit = {}) {
    val controller = rememberQuickController()
    val states by controller.states.collectAsStateWithLifecycle()
    val state = states[tile] ?: QuickTileState()
    var expanded by remember { mutableStateOf(false) }
    ShellSurface(Modifier.fillMaxWidth().testTag("quick-tile-${tile.name}")) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .combinedClickable(enabled = interactive, onClickLabel = "Открыть ${tile.title}", onLongClickLabel = "Настройки ${tile.title}",
                    onLongClick = { if (controller.settings(tile)) onExternal() },
                    onClick = {
                        if (state.adjustable && (tile == QuickTile.BRIGHTNESS || tile == QuickTile.VOLUME)) expanded = !expanded
                        else if (controller.tap(tile)) onExternal()
                    })
                .semantics { stateDescription = state.detail; role = Role.Button }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(tile.glyph, style = MaterialTheme.typography.headlineSmall, color = if (state.active == true) Mocha.Mauve else Mocha.Subtext, modifier = Modifier.padding(8.dp))
                Column(Modifier.weight(1f)) { Text(tile.title); Text(state.detail, style = MaterialTheme.typography.bodySmall, color = Mocha.Subtext) }
            }
            if (expanded && state.adjustable) {
                var draft by remember(state.value) { mutableFloatStateOf(state.value ?: 0f) }
                Slider(draft, { draft = it }, enabled = interactive, onValueChangeFinished = { controller.adjust(tile, draft) },
                    modifier = Modifier.testTag("quick-slider-${tile.name}").semantics { contentDescription = tile.title })
            }
        }
    }
}

@Composable
internal fun TopbarQuickSettings(onVisibilityChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { ShellRepository(context) }
    var config by remember { mutableStateOf<QuickSettingsConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(repository) {
        try { repository.quickSettings.collect { config = it; error = null } }
        catch (_: Exception) { error = "Quick Settings недоступны. Откройте редактор через меню." }
    }
    SideEffect { onVisibilityChanged(open) }
    DisposableEffect(Unit) { onDispose { onVisibilityChanged(false) } }
    if (config?.topbar?.isEmpty() != false && error == null) return
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("quick-topbar-open")) { Icon(Icons.Default.Settings, "Quick Settings") }
        DropdownMenu(open, { open = false }, modifier = Modifier.widthIn(min = 240.dp, max = 360.dp).testTag("quick-topbar-popup")) {
            Row(Modifier.fillMaxWidth().sidebarClosingGesture(onClose = { open = false })) {
                Text("Quick Settings", Modifier.weight(1f).padding(12.dp))
                TextButton(onClick = { open = false }) { Text("Закрыть") }
            }
            error?.let { Text(it, Modifier.padding(12.dp)) }
            config?.topbar?.forEach { id ->
                QuickTile.fromModule(id)?.let { QuickSettingsTile(it, onExternal = { open = false }) }
                    ?: Text("Недоступный модуль", Modifier.padding(12.dp))
            }
        }
    }
}
