package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun TopbarOverlayMenu(actions: List<AppMenuAction>, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Mocha.Base.copy(alpha = .5f)).clickable(onClick = onClose))
        ShellSurface(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp).widthIn(max = 460.dp).fillMaxWidth().heightIn(max = 480.dp)) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                Text("HyprDroid · TopBar", style = MaterialTheme.typography.titleMedium)
                actions.forEach { action -> TextButton(onClick = action.onClick, enabled = action.enabled, modifier = Modifier.fillMaxWidth()) { Text(action.label) } }
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        }
    }
}
