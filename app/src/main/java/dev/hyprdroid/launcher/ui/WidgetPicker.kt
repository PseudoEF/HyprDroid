package dev.hyprdroid.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.hyprdroid.launcher.model.PanelWidget
import dev.hyprdroid.launcher.widgets.PanelWidgetHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class WidgetChoice(val info: AppWidgetProviderInfo, val label: String, val app: String, val icon: android.graphics.Bitmap?)

@Composable
internal fun WidgetPicker(onChoose: (AppWidgetProviderInfo) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val choices by produceState<List<WidgetChoice>?>(null) {
        value = withContext(Dispatchers.IO) {
            try {
                PanelWidgetHost.get(context).manager.getInstalledProvidersForProfile(Process.myUserHandle()).map { info ->
                    WidgetChoice(info, info.loadLabel(context.packageManager),
                        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(info.provider.packageName, 0)).toString() }.getOrDefault(info.provider.packageName),
                        runCatching { info.loadIcon(context, context.resources.displayMetrics.densityDpi)?.toBitmap(96, 96) }.getOrNull())
                }.sortedBy { it.label.lowercase() }
            } catch (_: RuntimeException) { failed = true; emptyList() }
        }
    }
    BackHandler(onBack = onClose)
    Surface(color = Mocha.Base, contentColor = Mocha.Text, modifier = Modifier.fillMaxSize().testTag("widget-picker")) {
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Android-виджеты", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose) { Text("Назад") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Поиск виджетов") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("widget-search"))
            Text("Android запросит разрешение на привязку и настройку, если они нужны поставщику.", style = MaterialTheme.typography.bodySmall)
            if (choices == null) CircularProgressIndicator()
            val matching = choices.orEmpty().filter { it.label.contains(query, true) || it.app.contains(query, true) }
            if (choices != null && matching.isEmpty()) Text(if (failed) "Список виджетов недоступен" else "Виджеты не найдены")
            LazyColumn(Modifier.weight(1f)) {
                items(matching, key = { it.info.provider.flattenToString() }) { choice ->
                    ListItem(headlineContent = { Text(choice.label) }, supportingContent = { Text(choice.app) },
                        leadingContent = { choice.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(48.dp)) } },
                        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Добавить ${choice.label}") { onChoose(choice.info) }
                            .testTag("widget-provider-${choice.info.provider.flattenToString()}"))
                }
            }
        }
    }
}

@Composable
internal fun WidgetSizeControls(widget: PanelWidget, enabled: Boolean, onChange: (PanelWidget) -> Unit) {
    val context = LocalContext.current
    val session = remember { PanelWidgetHost.get(context) }
    val revision by session.revision.collectAsState()
    val info = remember(widget.appWidgetId, revision) { session.info(widget.appWidgetId) }
    Text("Размер виджета · ограничения поставщика учитываются при показе", style = MaterialTheme.typography.bodySmall)
    Text("Ширина: ${widget.widthPercent}%")
    Slider(widget.widthPercent.toFloat(), { onChange(widget.copy(widthPercent = it.toInt())) }, valueRange = 25f..100f,
        enabled = enabled && info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
        modifier = Modifier.testTag("widget-width-${widget.appWidgetId}"))
    Text("Высота: ${widget.heightDp} dp")
    Slider(widget.heightDp.toFloat(), { onChange(widget.copy(heightDp = it.toInt())) }, valueRange = 64f..600f,
        enabled = enabled && info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
        modifier = Modifier.testTag("widget-height-${widget.appWidgetId}"))
    if (info == null) Text("Поставщик или привязка недоступны. Можно удалить эту запись и добавить виджет заново.", color = Mocha.Subtext)
}
