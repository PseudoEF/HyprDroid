package dev.hyprdroid.launcher.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.SizeF
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hyprdroid.launcher.model.PanelWidget
import dev.hyprdroid.launcher.model.PanelWidgetLimits
import dev.hyprdroid.launcher.model.fitPanelWidget
import dev.hyprdroid.launcher.widgets.PanelWidgetHost

val LocalGlobalWidgetSurface = staticCompositionLocalOf { false }

/** RemoteViews rendering remains inside the existing panel module slot. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun PanelAndroidWidget(widget: PanelWidget, onEdit: (() -> Unit)? = null,
    interactive: Boolean = true, onResize: ((PanelWidget) -> Unit)? = null) {
    val context = LocalContext.current
    val session = remember { PanelWidgetHost.get(context) }
    val revision by session.revision.collectAsState()
    val hostError by session.error.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var failed by remember(widget.appWidgetId, revision) { mutableStateOf(false) }
    DisposableEffect(session, lifecycle) {
        var held = false
        fun sync() {
            val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (visible && !held) { held = true; session.acquire(); generation++; active = true }
            else if (!visible && held) { held = false; active = false; session.release() }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer); sync()
        onDispose { lifecycle.removeObserver(observer); active = false; if (held) session.release() }
    }
    val info = remember(widget.appWidgetId, revision, generation) { session.info(widget.appWidgetId)?.takeIf {
        it.provider.flattenToString() == widget.provider && it.profile == Process.myUserHandle()
    } }
    ShellSurface(Modifier.fillMaxWidth().testTag("android-widget-${widget.appWidgetId}")) {
        Column(Modifier.padding(8.dp)) {
            Text(widget.title.ifBlank { "Android-виджет" }, style = MaterialTheme.typography.labelMedium, color = Mocha.Subtext,
                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).testTag("widget-title-${widget.appWidgetId}")
                    .then(if (onEdit != null) Modifier.combinedClickable(onClick = {}, onLongClick = onEdit, onLongClickLabel = "Редактировать виджет") else Modifier))
            if (info == null || failed || hostError != null) {
                Text(hostError ?: "Виджет недоступен: поставщик удалён/отключён или привязка отозвана. Запись сохранена.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { failed = false; session.retry() }) { Text("Повторить") }
                Text("Удалить или добавить заново можно в настройках панелей Home.", style = MaterialTheme.typography.bodySmall)
            } else if (active) {
                val density = LocalDensity.current.density
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val availableWidth = maxWidth.value
                    val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
                    val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
                    val limits = PanelWidgetLimits(
                        (if (horizontal && info.minResizeWidth > 0) info.minResizeWidth else info.minWidth) / density,
                        (if (vertical && info.minResizeHeight > 0) info.minResizeHeight else info.minHeight) / density,
                        if (Build.VERSION.SDK_INT >= 31) info.maxResizeWidth / density else 0f,
                        if (Build.VERSION.SDK_INT >= 31) info.maxResizeHeight / density else 0f, horizontal, vertical)
                    val fitted = fitPanelWidget(widget, maxWidth.value, limits)
                    val width = fitted.width
                    val height = fitted.height
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (fitted.tooNarrow) Text("Панель уже минимальной ширины поставщика. Увеличьте ширину панели.", style = MaterialTheme.typography.bodySmall)
                    Box {
                    key(widget.appWidgetId, generation, revision) {
                        AndroidView(factory = { visual ->
                            try { session.host.createView(visual, widget.appWidgetId, info) }
                            catch (_: RuntimeException) { failed = true; android.appwidget.AppWidgetHostView(visual) }
                        }, modifier = Modifier.width(width.dp).height(height.dp).testTag("widget-view-${widget.appWidgetId}"),
                            onReset = null, onRelease = { it.removeAllViews() }, update = { view ->
                                val size = width.toInt() to height.toInt()
                                if (view.tag != size) {
                                    try {
                                        val options = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) }
                                        if (Build.VERSION.SDK_INT >= 31) view.updateAppWidgetSize(options, listOf(SizeF(width, height)))
                                        else @Suppress("DEPRECATION") view.updateAppWidgetSize(options, size.first, size.second, size.first, size.second)
                                        view.tag = size
                                    } catch (_: RuntimeException) { failed = true }
                                }
                            })
                    }
                    if (!interactive) Box(Modifier.matchParentSize().clickable(onClickLabel = "Предпросмотр виджета", onClick = {}))
                    if (onResize != null && (horizontal || vertical)) Box(Modifier.align(Alignment.BottomEnd)) {
                        WidgetResizeHandle(widget, availableWidth, horizontal, vertical, onResize)
                    }
                    }
                    }
                }
            } else Text("Загрузка виджета…", style = MaterialTheme.typography.bodySmall)
        }
    }
}
