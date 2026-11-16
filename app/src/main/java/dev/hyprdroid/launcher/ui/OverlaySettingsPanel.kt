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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.model.OverlaySettings
import dev.hyprdroid.launcher.model.ShellSettings
import dev.hyprdroid.launcher.overlay.OverlaySession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun OverlaySettingsPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val repository = remember(context) { ShellRepository(context) }
    var storageError by remember { mutableStateOf<String?>(null) }
    val saved by produceState<ShellSettings?>(null, repository) {
        try { repository.settings.collect { value = it } }
        catch (_: java.io.IOException) { storageError = "Не удалось прочитать настройки overlay" }
    }
    val current = saved?.overlay
    var draft by remember(current) { mutableStateOf(current ?: OverlaySettings()) }
    var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val running by OverlaySession.running.collectAsState()
    val serviceError by OverlaySession.error.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = Settings.canDrawOverlays(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun save(value: OverlaySettings, changeSession: Boolean) {
        scope.launch {
            saving = true
            try {
                // Geometry changes read the current enabled state rather than an obsolete UI draft.
                val latest = repository.settings.first().overlay
                repository.setOverlay(if (changeSession) value else value.copy(enabled = latest.enabled))
                error = null
                if (changeSession && activity != null) {
                    if (value.enabled) OverlaySession.start(activity) else OverlaySession.stop(activity)
                }
            } catch (_: java.io.IOException) { error = "Не удалось сохранить настройки. Повторите попытку." }
            finally { saving = false }
        }
    }
    BackHandler { onClose() }
    Surface(color = Mocha.Base, contentColor = Mocha.Text, modifier = Modifier.fillMaxSize().testTag("overlay-settings")) {
        Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("Sidebar поверх приложений", style = MaterialTheme.typography.headlineSmall)
            Text("Эксперимент · v0.6.2", color = Mocha.Mauve)
            Text("Правая зона открывает Quick Shell вне HyprDroid. Слева остаётся системный Back. В самом лаунчере работает прежняя Sidebar.", Modifier.padding(vertical = 12.dp))
            Text("Панель отключена по умолчанию. Для работы нужны разрешение «Поверх других приложений» и foreground-служба с уведомлением. Панель не читает экран и не определяет открытые приложения.")
            Text("Если Android скрывает уведомление, отключение доступно в самой панели, здесь и в системном списке активных приложений.", style = MaterialTheme.typography.bodySmall)
            Text(if (allowed) "Разрешение выдано" else "Разрешение не выдано", modifier = Modifier.padding(top = 12.dp).testTag("overlay-permission"))
            OutlinedButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                catch (_: RuntimeException) { error = "Откройте разрешение вручную в настройках Android." }
            }, modifier = Modifier.testTag("overlay-permission-button")) { Text("Разрешение Android") }
            Text(if (running) "Служба запущена · зона скрыта внутри HyprDroid" else "Служба остановлена", Modifier.testTag("overlay-service-status"))
            Button(enabled = !saving && current != null && activity != null && (current.enabled || allowed),
                onClick = { save(draft.copy(enabled = !current!!.enabled), true) }, modifier = Modifier.testTag("overlay-toggle")) {
                Text(if (current?.enabled == true) "Отключить панель" else "Включить панель")
            }
            if (current?.enabled == true && !running && allowed) TextButton(onClick = { activity?.let { OverlaySession.start(it) } }) { Text("Повторить запуск") }
            Text("Высота зоны: ${draft.heightDp} dp")
            Slider(draft.heightDp.toFloat(), { draft = draft.copy(heightDp = it.toInt()) }, valueRange = 80f..200f, enabled = !saving)
            Text("Положение по высоте: ${draft.positionPercent}%")
            Slider(draft.positionPercent.toFloat(), { draft = draft.copy(positionPercent = it.toInt()) }, valueRange = 20f..80f, enabled = !saving)
            Text("Отступ от правого края: ${draft.insetDp} dp")
            Slider(draft.insetDp.toFloat(), { draft = draft.copy(insetDp = it.toInt()) }, valueRange = 0f..48f, enabled = !saving)
            Text("При 0 dp пробуем исключить Back только в зоне. Если Android отдаёт жест системе, увеличьте отступ или коснитесь видимого маркера. Остальная часть края сохраняет Back.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { save(draft, false) }, enabled = !saving && current != null) { Text("Сохранить зону") }
            Text("Некоторые приложения и системные экраны скрывают overlay. На экране блокировки панель не показывается. После перезагрузки или остановки Android откройте HyprDroid для возобновления. Запуск ярлыков зависит от разрешений Android.", style = MaterialTheme.typography.bodySmall)
            (error ?: storageError ?: serviceError)?.let { Text(it, color = Mocha.Red) }
            TextButton(onClick = onClose, modifier = Modifier.testTag("overlay-settings-close")) { Text("Назад") }
        }
    }
}
