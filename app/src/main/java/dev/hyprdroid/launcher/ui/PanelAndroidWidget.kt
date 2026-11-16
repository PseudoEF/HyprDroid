package dev.hyprdroid.launcher.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.SizeF
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
import dev.hyprdroid.launcher.widgets.PanelWidgetHost

val LocalGlobalWidgetSurface = staticCompositionLocalOf { false }

/** RemoteViews rendering remains inside the existing panel module slot. */
@Composable
internal fun PanelAndroidWidget(widget: PanelWidget) {
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
        onDispose { lifecycle.removeObserver(observer); if (held) session.release() }
    }
    val info = remember(widget.appWidgetId, revision, generation) { session.info(widget.appWidgetId)?.takeIf {
        it.provider.flattenToString() == widget.provider && it.profile == Process.myUserHandle()
    } }
    ShellSurface(Modifier.fillMaxWidth().testTag("android-widget-${widget.appWidgetId}")) {
        Column(Modifier.padding(8.dp)) {
            Text(widget.title.ifBlank { "Android-виджет" }, style = MaterialTheme.typography.labelMedium, color = Mocha.Subtext)
            if (info == null || failed || hostError != null) {
                Text(hostError ?: "Виджет недоступен: поставщик удалён/отключён или привязка отозвана. Запись сохранена.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { failed = false; session.retry() }) { Text("Повторить") }
                Text("Удалить или добавить заново можно в настройках панелей Home.", style = MaterialTheme.typography.bodySmall)
            } else if (active) {
                val density = LocalDensity.current.density
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val minWidth = (if (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0) info.minResizeWidth else info.minWidth) / density
                    val limitWidth = if (Build.VERSION.SDK_INT >= 31 && info.maxResizeWidth > 0) minOf(maxWidth.value, info.maxResizeWidth / density) else maxWidth.value
                    val width = (maxWidth.value * widget.widthPercent / 100).coerceAtLeast(minOf(minWidth, limitWidth)).coerceAtMost(limitWidth)
                    val minHeight = (if (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0) info.minResizeHeight else info.minHeight) / density
                    val requestedHeight = if (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL == 0) info.minHeight / density else widget.heightDp.toFloat()
                    val maxHeight = if (Build.VERSION.SDK_INT >= 31 && info.maxResizeHeight > 0) (info.maxResizeHeight / density).coerceAtMost(600f) else 600f
                    val height = requestedHeight.coerceAtLeast(minHeight.coerceAtMost(maxHeight)).coerceIn(64f, maxHeight.coerceAtLeast(64f))
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
                }
            } else Text("Загрузка виджета…", style = MaterialTheme.typography.bodySmall)
        }
    }
}
