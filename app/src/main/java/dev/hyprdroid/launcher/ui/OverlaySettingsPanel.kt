package dev.hyprdroid.launcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.overlay.OverlaySession
import dev.hyprdroid.launcher.widgets.LocalWidgetBinding
import dev.hyprdroid.launcher.widgets.PanelWidgetHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class PanelCategory(val title: String) { CONTENT("Содержимое"), APPEARANCE("Внешний вид"), GESTURES("Жесты"), OVERLAY("Overlay") }

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
    var category by rememberSaveable { mutableStateOf(PanelCategory.CONTENT) }
    val current = saved?.panels?.get(side)
    var draft by remember(side, current) { mutableStateOf(current ?: PanelsSettings()[side]) }
    var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val running by OverlaySession.running.collectAsState()
    val serviceError by OverlaySession.error.collectAsState()
    var saving by remember { mutableStateOf(false) }
    var widgetPicker by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    val binding = LocalWidgetBinding.current
    val widgetBusy = binding?.busy?.collectAsState()?.value ?: false
    val widgetError = binding?.error?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = Settings.canDrawOverlays(context) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    fun persistPanel() {
        val destination = side; val value = draft; val base = current
        scope.launch {
            saving = true
            try {
                repository.setPanel(destination, value, base)
                PanelWidgetHost.get(context).cleanup(repository)
                error = null
                val latest = repository.settings.first()
                if (activity != null && latest.overlay.enabled && (latest.panels.left.scope.apps || latest.panels.right.scope.apps)
                    && allowed && !running) OverlaySession.start(activity)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Не удалось сохранить панель. Черновик оставлен; повторите попытку." }
            finally { saving = false }
        }
    }
    fun toggleOverlay() { scope.launch {
        saving = true
        try {
            val latest = repository.settings.first().overlay
            repository.setOverlay(latest.copy(enabled = !latest.enabled))
            if (activity != null) { if (latest.enabled) OverlaySession.stop(activity) else OverlaySession.start(activity) }
            error = null
        } catch (_: java.io.IOException) { error = "Не удалось сохранить включение overlay" }
        finally { saving = false }
    } }
    BackHandler { if (preview) preview = false else if (!saving) onClose() }
    Surface(color = Mocha.Base, contentColor = Mocha.Text, modifier = Modifier.fillMaxSize().testTag("overlay-settings")
        .then(if (preview || widgetPicker) Modifier.clearAndSetSemantics { } else Modifier)) {
        Column(Modifier.safeDrawingPadding().padding(12.dp)) {
            Text("Настройки панелей", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .sidebarClosingGesture(enabled = !saving && !preview && !widgetPicker, onClose = onClose).testTag("panel-settings-dismiss-gesture"))
            FlowRow {
                PanelSide.entries.forEach { value -> FilterChip(side == value, { side = value }, enabled = !saving && !widgetBusy,
                    label = { Text(if (value == PanelSide.LEFT) "Левая" else "Правая") }, modifier = Modifier.testTag("panel-tab-${value.name}")) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                PanelCategory.entries.forEach { value -> FilterChip(category == value, { category = value },
                    label = { Text(value.title) }, modifier = Modifier.testTag("panel-category-${value.name}")) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("panel-settings-content")) {
                when (category) {
                    PanelCategory.CONTENT -> {
                        key(side) { PanelModuleEditor(draft, { draft = it }, !saving && !widgetBusy) }
                        FlowRow {
                            PanelModule.entries.filter { it.supports(side) && it.name !in draft.modules }.forEach { module ->
                                AssistChip(onClick = { draft = draft.copy(modules = draft.modules + module.name) }, enabled = !saving && draft.modules.size < 64,
                                    label = { Text("+ ${module.title}") }, modifier = Modifier.testTag("module-add-${module.name}"))
                            }
                        }
                        if (binding != null) {
                            val pending = saved?.pendingWidget
                            if (pending != null) {
                                Text("Незавершённое добавление: ${pending.widget.title}")
                                TextButton(onClick = binding::resume, enabled = !widgetBusy, modifier = Modifier.testTag("resume-widget")) { Text("Продолжить добавление") }
                                TextButton(onClick = binding::cancel, enabled = !widgetBusy, modifier = Modifier.testTag("cancel-widget")) { Text("Отменить добавление") }
                            } else {
                                OutlinedButton(onClick = { widgetPicker = true }, enabled = current != null && draft == current && !saving && !widgetBusy && draft.widgets.size < 32 && draft.modules.size < 64,
                                    modifier = Modifier.testTag("add-android-widget")) { Text("Добавить Android-виджет") }
                                if (draft != current) Text("Перед добавлением виджета сохраните черновик панели.", style = MaterialTheme.typography.bodySmall)
                            }
                            widgetError?.let { Text(it, color = Mocha.Red) }
                        }
                    }
                    PanelCategory.APPEARANCE -> {
                        PanelWidthControl(draft, !saving) { draft = it }
                        Text("Видимость маркера", style = MaterialTheme.typography.titleMedium)
                        FlowRow { MarkerVisibility.entries.forEach { value -> FilterChip(draft.visibility == value, { draft = draft.copy(visibility = value) }, enabled = !saving,
                            label = { Text(value.title) }, modifier = Modifier.testTag("panel-visibility-${value.name}")) } }
                        PanelSlider("Ширина маркера", draft.markerWidthDp, 1f..12f, !saving) { draft = draft.copy(markerWidthDp = it) }
                    }
                    PanelCategory.GESTURES -> {
                        Text("Открытие панели", style = MaterialTheme.typography.titleMedium)
                        FlowRow { PanelActivation.entries.forEach { mode ->
                            FilterChip(draft.activation == mode, { draft = draft.copy(activation = mode) }, enabled = !saving,
                                label = { Text(mode.title) }, modifier = Modifier.testTag("panel-activation-${mode.name}"))
                        } }
                        PanelSlider("Длина свайпа", draft.swipeThresholdDp, 24f..96f, !saving) { draft = draft.copy(swipeThresholdDp = it) }
                        PanelSlider("Ширина сенсорной зоны", draft.touchWidthDp, 12f..64f, !saving) { draft = draft.copy(touchWidthDp = it) }
                        PanelSlider("Высота зоны", draft.heightDp, 80f..200f, !saving) { draft = draft.copy(heightDp = it) }
                        PanelSlider("Положение по высоте", draft.positionPercent, 10f..90f, !saving, "%") { draft = draft.copy(positionPercent = it) }
                        PanelSlider("Отступ от края", draft.insetDp, 0f..64f, !saving) { draft = draft.copy(insetDp = it) }
                        SettingsDetails("О жестах и Android Back") {
                            Text("Скрытый маркер сохраняет сенсорную зону. Увеличьте отступ при конфликте с Back; широкая зона может мешать приложению. В Home кнопки имеют приоритет. В overlay касание внутри узкого окна зоны не передаётся приложению под ним, даже в Swipe only; за его границами касания не перехватываются.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    PanelCategory.OVERLAY -> {
                        Text("Где доступна панель", style = MaterialTheme.typography.titleMedium)
                        FlowRow { PanelScope.entries.forEach { value -> FilterChip(draft.scope == value, { draft = draft.copy(scope = value) }, enabled = !saving,
                            label = { Text(value.title) }, modifier = Modifier.testTag("panel-scope-${value.name}")) } }
                        Text("Home работает без overlay-разрешения. Для других приложений сохраните режим, выдайте разрешение Android и включите службу.")
                        Text(if (allowed) "Разрешение выдано" else "Разрешение не выдано", Modifier.testTag("overlay-permission"))
                        OutlinedButton(onClick = { try { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                            catch (_: RuntimeException) { error = "Откройте разрешение вручную в настройках Android." } }, modifier = Modifier.testTag("overlay-permission-button")) { Text("Разрешение Android") }
                        val optedIn = saved?.overlay?.enabled == true
                        val hasApps = saved?.panels?.let { it.left.scope.apps || it.right.scope.apps } == true
                        Button(onClick = ::toggleOverlay, enabled = !saving && saved != null && activity != null && (optedIn || allowed && hasApps), modifier = Modifier.testTag("overlay-toggle")) { Text(if (optedIn) "Отключить overlay" else "Включить overlay") }
                        if (!hasApps) Text("Сохраните режим «Поверх приложений» или «Везде» хотя бы для одной панели.")
                        Text(if (running) "Служба запущена" else "Служба остановлена", Modifier.testTag("overlay-service-status"))
                        if (optedIn && !running && allowed && hasApps) TextButton(onClick = { activity?.let { OverlaySession.start(it) } }) { Text("Повторить запуск") }
                        Text("Android может скрывать overlay и ограничивать действия виджетов. Привязка и настройка поставщика доступны в Home.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            (error ?: serviceError)?.let { Text(it, color = Mocha.Red) }
            Text(if (draft == current) "Сохранено" else "Есть несохранённые изменения · Back отменяет", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("panel-save-status"))
            FlowRow {
                Button(onClick = ::persistPanel, enabled = current != null && !saving && !widgetBusy, modifier = Modifier.testTag("save-panel")) { Text("Сохранить панель") }
                TextButton(onClick = { preview = true }, enabled = current != null, modifier = Modifier.testTag("preview-panel")) { Text("Предпросмотр") }
                TextButton(onClick = onClose, enabled = !saving, modifier = Modifier.testTag("overlay-settings-close")) { Text("Назад") }
            }
        }
    }
    if (preview && saved != null) {
        ModularSidebar(WorkspaceUiState(shell = saved!!.copy(panelConfiguration = saved!!.panels.with(side, draft))), { preview = false }, {}, { _, _ -> }, {}, { _, _ -> }, { _, _, done -> done() },
            side = side, allowPanelEditing = false)
    }
    if (widgetPicker && binding != null) WidgetPicker(onClose = { widgetPicker = false }, onChoose = { info -> widgetPicker = false; binding.add(side, info) })
}

@Composable
internal fun PanelWidthControl(draft: PanelSettings, enabled: Boolean, onChange: (PanelSettings) -> Unit) {
    Text("Ширина панели: ${draft.widthPercent}%", style = MaterialTheme.typography.titleSmall)
    ShellSlider(draft.widthPercent.toFloat(), { onChange(draft.copy(widthPercent = it.toInt())) }, valueRange = 50f..100f,
        enabled = enabled, modifier = Modifier.testTag("panel-width"))
}
@Composable
private fun PanelSlider(label: String, value: Int, range: ClosedFloatingPointRange<Float>, enabled: Boolean, unit: String = "dp", change: (Int) -> Unit) {
    Text("$label: $value $unit")
    ShellSlider(value.toFloat(), { change(it.toInt()) }, valueRange = range, enabled = enabled, modifier = Modifier.testTag("panel-slider-$label"))
}
