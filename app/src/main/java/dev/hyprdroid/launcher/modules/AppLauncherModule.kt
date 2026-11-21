package dev.hyprdroid.launcher.modules

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.ui.Mocha
import dev.hyprdroid.launcher.workspaceengine.WindowId

internal class AppLauncherState(windowId: WindowId) : ModuleState(windowId) {
    var query by mutableStateOf("")
        private set

    fun updateQuery(value: String) {
        query = value
    }

    fun clearQuery() {
        query = ""
    }
}

internal object AppLauncherModule : TileModule {
    override val id = "hyprdroid.apps"
    override val title = "Приложения"
    override val summary = "Поиск и запуск"
    override val glyph = ModuleGlyph.APPS

    override fun createState(windowId: WindowId): ModuleState = AppLauncherState(windowId)

    @Composable
    override fun Content(state: ModuleState, modifier: Modifier) {
        val launcher = state as AppLauncherState
        val environment = LocalModuleEnvironment.current
        val filtered = remember(environment.apps, launcher.query) {
            val query = launcher.query.trim()
            if (query.isEmpty()) environment.apps
            else environment.apps.filter {
                it.label.contains(query, ignoreCase = true) || it.componentId.contains(query, ignoreCase = true)
            }
        }
        BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp)) {
            val compact = maxWidth < 250.dp || maxHeight < 210.dp
            val iconSize = if (compact) 32.dp else 40.dp
            Column(Modifier.fillMaxSize()) {
                AppSearchField(launcher, state.windowId, compact)
                if (environment.loadingApps) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
                if (environment.catalogError) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Каталог недоступен",
                            style = MaterialTheme.typography.labelSmall,
                            color = Mocha.Red,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = environment.retryApps) { Text("Повторить") }
                    }
                }
                if (environment.apps.isEmpty() && environment.loadingApps) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            if (launcher.query.isBlank()) "Нет доступных приложений" else "Ничего не найдено",
                            style = MaterialTheme.typography.labelSmall,
                            color = Mocha.Subtext,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (compact) 54.dp else 68.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .testTag("module-apps-grid-${state.windowId.value}"),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { it.componentId }) { app ->
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { environment.launchApp(app.componentId) }
                                    .padding(vertical = 2.dp)
                                    .testTag("module-app-${app.componentId}"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Image(
                                    bitmap = app.icon.asImageBitmap(),
                                    contentDescription = app.label,
                                    modifier = Modifier.size(iconSize).clip(RoundedCornerShape(7.dp)),
                                    contentScale = ContentScale.Fit,
                                )
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Mocha.Subtext,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSearchField(state: AppLauncherState, windowId: WindowId, compact: Boolean) {
    BasicTextField(
        value = state.query,
        onValueChange = state::updateQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelSmall.copy(color = Mocha.Text),
        cursorBrush = SolidColor(Mocha.Mauve),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp).testTag("module-apps-search-${windowId.value}"),
        decorationBox = { inner ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(Mocha.Mantle.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = if (compact) 4.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Mocha.Subtext, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.weight(1f)) {
                    if (state.query.isEmpty()) {
                        Text(
                            "Поиск приложений",
                            style = MaterialTheme.typography.labelSmall,
                            color = Mocha.Subtext,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (state.query.isNotEmpty()) {
                    Box(
                        modifier = Modifier.size(18.dp).clip(CircleShape).clickable { state.clearQuery() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Очистить поиск", tint = Mocha.Subtext, modifier = Modifier.size(12.dp))
                    }
                }
            }
        },
    )
}
