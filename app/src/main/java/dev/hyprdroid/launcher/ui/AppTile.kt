package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hyprdroid.launcher.data.LauncherApp

data class AppMenuAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(
    app: LauncherApp?, component: String, onClick: () -> Unit, menu: List<AppMenuAction>,
    modifier: Modifier = Modifier, list: Boolean = false, compact: Boolean = false,
    selected: Boolean = false, onRemove: (() -> Unit)? = null,
    iconSizeDp: Int = 48,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val label = app?.label ?: component.substringBefore('/')
    Box(modifier) {
        val clickModifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                role = Role.Button, onClick = onClick, onClickLabel = label,
                onLongClick = { menuOpen = true }, onLongClickLabel = "Действия: $label",
            )
        if (list) {
            Surface(color = Mocha.Mantle.copy(alpha = .86f), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Mocha.Outline.copy(alpha = .5f))) {
                Row(clickModifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppImage(app, Modifier.size(iconSizeDp.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(label, modifier = Modifier.weight(1f).padding(end = if (onRemove != null) 28.dp else 0.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Column(
                clickModifier.heightIn(min = if (compact) 52.dp else 108.dp).padding(4.dp)
                    .then(if (compact) Modifier.semantics { contentDescription = label } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = RoundedCornerShape(if (compact) 16.dp else 20.dp),
                    color = Mocha.Mantle.copy(alpha = if (compact) .1f else .8f),
                    border = if (compact) null else BorderStroke(1.dp, if (selected) Mocha.Mauve else Mocha.Outline.copy(alpha = .45f)),
                ) {
                    Box(Modifier.size(if (compact) 44.dp else (iconSizeDp + 16).dp), contentAlignment = Alignment.Center) {
                        AppImage(app, Modifier.size(if (compact) 38.dp else iconSizeDp.dp))
                    }
                }
                if (!compact) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (app == null) "$label · недоступно" else label,
                        modifier = Modifier.background(Mocha.Mantle.copy(alpha = .86f), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (selected && onRemove == null) {
            Surface(color = Mocha.Mauve, shape = RoundedCornerShape(10.dp), modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(Icons.Default.Check, "Закреплено", tint = Mocha.Base, modifier = Modifier.padding(3.dp).size(16.dp))
            }
        }
        onRemove?.let { remove ->
            IconButton(onClick = remove, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = Mocha.Surface) {
                    Icon(Icons.Default.Close, "Убрать $label из пространства", tint = Mocha.Red, modifier = Modifier.padding(4.dp).size(16.dp))
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            menu.forEach { action ->
                DropdownMenuItem(text = { Text(action.label) }, enabled = action.enabled, onClick = { menuOpen = false; action.onClick() })
            }
        }
    }
}

@Composable
fun AppImage(app: LauncherApp?, modifier: Modifier = Modifier) {
    if (app != null) Image(app.icon.asImageBitmap(), contentDescription = null, modifier = modifier)
    else Icon(Icons.Default.Home, contentDescription = null, tint = Mocha.Subtext, modifier = modifier)
}
