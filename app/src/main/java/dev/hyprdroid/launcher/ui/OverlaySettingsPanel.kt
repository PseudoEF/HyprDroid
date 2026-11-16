package dev.hyprdroid.launcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.overlay.OverlaySession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One small editor for both panels. Geometry/modules are an explicit draft; overlay opt-in is separate. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverlaySettingsPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val repository = remember(context) { ShellRepository(context) }
    var error by remember { mutableStateOf<String?>(null) }
    val saved by produceState<ShellSettings?>(null, repository) {
        try { repository.settings.collect { value = it } }
        catch (_: java.io.IOException) { error = "Не удалось прочитать настройки панелей" }
    }
    var side by rememberSaveable { mutableStateOf(PanelSide.LEFT) }
    val current = saved?.panels?.get(side)
    var draft by remember(side, current) { mutableStateOf(current ?: PanelsSettings()[side]) }
    var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val running by OverlaySession.running.collectAsState()
    val serviceError by OverlaySession.error.collectAsState()
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = Settings.canDrawOverlays(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun persistPanel() {
        val destination = side
        val value = draft
        scope.launch {
            saving = true
            try {
                repository.setPanel(destination, value); error = null
                val latest = repository.settings.first()
                if (activity != null && latest.overlay.enabled && (latest.panels.left.scope.apps || latest.panels.right.scope.apps)
                    && Settings.canDrawOverlays(context) && !running) OverlaySession.start(activity)
            }
            catch (_: java.io.IOException) { error = "Не удалось сохранить панель. Повторите попытку." }
            finally { saving = false }
        }
    }
    fun toggleOverlay() {
        scope.launch {
            saving = true
            try {
                val latest = repository.settings.first().overlay
                repository.setOverlay(latest.copy(enabled = !latest.enabled))
                if (activity != null) { if (latest.enabled) OverlaySession.stop(activity) else OverlaySession.start(activity) }
                error = null
            } catch (_: java.io.IOException) { error = "Не удалось сохранить включение overlay" }
            finally { saving = false }
        }
    }
    fun move(id: String, delta: Int) {
        val order = draft.modules.toMutableList()
        val index = order.indexOf(id)
        if (index >= 0) { order.add((index + delta).coerceIn(0, order.lastIndex), order.removeAt(index)); draft = draft.copy(modules = order) }
    }
    BackHandler { onClose() }
    Surface(color = Mocha.Base, contentColor = Mocha.Text, modifier = Modifier.fillMaxSize().testTag("overlay-settings")) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Панели · Universal Panels", style = MaterialTheme.typography.headlineSmall)
            Text("Изменения выбранной панели применяются после сохранения. Back отменяет черновик.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelSide.entries.forEach { value -> FilterChip(side == value, { side = value }, enabled = !saving,
                    label = { Text(if (value == PanelSide.LEFT) "Левая" else "Правая") }, modifier = Modifier.testTag("panel-tab-${value.name}")) }
            }
            Text("Где доступна панель", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelScope.entries.forEach { value -> FilterChip(draft.scope == value, { draft = draft.copy(scope = value) }, enabled = !saving,
                    label = { Text(value.title) }, modifier = Modifier.testTag("panel-scope-${value.name}")) }
            }
            PanelSlider("Ширина маркера", draft.markerWidthDp, 1f..12f, !saving) { draft = draft.copy(markerWidthDp = it) }
            PanelSlider("Ширина сенсорной зоны", draft.touchWidthDp, 12f..64f, !saving) { draft = draft.copy(touchWidthDp = it) }
            PanelSlider("Высота зоны", draft.heightDp, 80f..200f, !saving) { draft = draft.copy(heightDp = it) }
            PanelSlider("Положение по высоте", draft.positionPercent, 10f..90f, !saving, "%") { draft = draft.copy(positionPercent = it) }
            PanelSlider("Отступ от края", draft.insetDp, 0f..64f, !saving) { draft = draft.copy(insetDp = it) }
            Text("Видимость маркера", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkerVisibility.entries.forEach { value -> FilterChip(draft.visibility == value, { draft = draft.copy(visibility = value) }, enabled = !saving,
                    label = { Text(value.title) }, modifier = Modifier.testTag("panel-visibility-${value.name}")) }
            }
            Text("Скрытый маркер оставляет сенсорную зону активной. Увеличьте отступ, если системный Back имеет приоритет. Широкая зона может мешать жестам приложения.", style = MaterialTheme.typography.bodySmall)
            Text("Модули · порядок сверху вниз", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            draft.modules.forEachIndexed { index, id ->
                val title = PanelModule.entries.find { it.name == id }?.title ?: "Недоступный модуль: $id"
                Column(Modifier.fillMaxWidth().testTag("panel-module-row-$id")) {
                    Text(title, color = Mocha.Lavender)
                    FlowRow {
                        TextButton(onClick = { move(id, -1) }, enabled = !saving && index > 0, modifier = Modifier.testTag("module-up-$id")) { Text("Выше") }
                        TextButton(onClick = { move(id, 1) }, enabled = !saving && index < draft.modules.lastIndex, modifier = Modifier.testTag("module-down-$id")) { Text("Ниже") }
                        TextButton(onClick = { draft = draft.copy(modules = draft.modules - id) }, enabled = !saving) { Text("Убрать") }
                    }
                }
            }
            if (draft.modules.isEmpty()) Text("Панель пуста. Добавьте модуль ниже.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelModule.entries.filter { it.supports(side) && it.name !in draft.modules }.forEach { module ->
                    AssistChip(onClick = { draft = draft.copy(modules = draft.modules + module.name) }, enabled = !saving,
                        label = { Text("+ ${module.title}") }, modifier = Modifier.testTag("module-add-${module.name}"))
                }
            }
            Button(onClick = ::persistPanel, enabled = current != null && !saving, modifier = Modifier.testTag("save-panel")) { Text("Сохранить панель") }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Поверх приложений", style = MaterialTheme.typography.titleMedium)
            Text("Home работает без разрешения. Для других приложений выберите и сохраните соответствующий режим, затем выдайте разрешение Android и включите службу. Панели не читают чужие окна.", style = MaterialTheme.typography.bodySmall)
            Text(if (allowed) "Разрешение выдано" else "Разрешение не выдано", Modifier.testTag("overlay-permission"))
            OutlinedButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                catch (_: RuntimeException) { error = "Откройте разрешение вручную в настройках Android." }
            }, modifier = Modifier.testTag("overlay-permission-button")) { Text("Разрешение Android") }
            val optedIn = saved?.overlay?.enabled == true
            val hasApps = saved?.panels?.let { it.left.scope.apps || it.right.scope.apps } == true
            Button(onClick = ::toggleOverlay, enabled = !saving && saved != null && activity != null && (optedIn || allowed && hasApps), modifier = Modifier.testTag("overlay-toggle")) {
                Text(if (optedIn) "Отключить overlay" else "Включить overlay")
            }
            if (!hasApps) Text("Сохраните режим «Поверх приложений» или «Везде» хотя бы для одной панели.", style = MaterialTheme.typography.bodySmall)
            Text(if (running) "Служба запущена; внутри HyprDroid используется Home-панель" else "Служба остановлена", Modifier.testTag("overlay-service-status"))
            if (optedIn && !running && allowed && hasApps) TextButton(onClick = { activity?.let { OverlaySession.start(it) } }) { Text("Повторить запуск") }
            Text("Android может скрывать overlay на защищённых экранах. Уведомление службы и кнопка в панели позволяют её отключить. После перезапуска устройства откройте HyprDroid для возобновления. Полноценные Android-виджеты пока не поддерживаются.", style = MaterialTheme.typography.bodySmall)
            (error ?: serviceError)?.let { Text(it, color = Mocha.Red) }
            TextButton(onClick = onClose, modifier = Modifier.testTag("overlay-settings-close")) { Text("Назад") }
        }
    }
}

@Composable
private fun PanelSlider(label: String, value: Int, range: ClosedFloatingPointRange<Float>, enabled: Boolean, unit: String = "dp", change: (Int) -> Unit) {
    Text("$label: $value $unit")
    Slider(value.toFloat(), { change(it.toInt()) }, valueRange = range, enabled = enabled, modifier = Modifier.testTag("panel-slider-$label"))
}
