package dev.hyprdroid.launcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.overlay.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TopbarSettingsPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { ShellRepository(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf<TopbarSettings?>(null) }
    var enabled by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var rulesOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun close() { if (!saving) { if (dirty) confirm = true else onClose() } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        try {
            draft = repository.topbar.first(); enabled = repository.settings.first().overlay.enabled
            apps = withContext(Dispatchers.IO) { LauncherCatalog(context).load().associate { it.componentId.substringBefore('/') to it.label } }
        } catch (_: Exception) { error = "Не удалось прочитать TopBar. Данные не изменены." }
    }
    BackHandler { if (rulesOpen) rulesOpen = false else close() }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Отменить изменения TopBar?") },
        confirmButton = { TextButton(onClick = onClose) { Text("Отменить изменения") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Продолжить") } })
    Surface(Modifier.fillMaxSize().testTag("topbar-settings"), color = Mocha.Base, contentColor = Mocha.Text) {
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (rulesOpen) "TopBar · приложения" else "TopBar overlay", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { if (rulesOpen) rulesOpen = false else close() }, enabled = !saving) { Text(if (rulesOpen) "Готово" else "Закрыть") }
            }
            val value = draft
            if (rulesOpen && value != null) {
                OutlinedTextField(query, { query = it }, label = { Text("Поиск приложения") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f)) {
                    val packages = (apps.keys + value.appRules.keys).sortedBy { apps[it] ?: it }.filter { (apps[it] ?: it).contains(query, true) || it.contains(query, true) }
                    items(packages, key = { it }) { pkg -> Column(Modifier.padding(vertical = 8.dp)) {
                        Text(apps[pkg] ?: "Недоступно: $pkg")
                        Row(Modifier.horizontalScroll(rememberScrollState())) { AppBarRule.entries.forEach { rule ->
                            FilterChip(selected = (value.appRules[pkg] ?: AppBarRule.AUTO) == rule, onClick = {
                                draft = value.copy(appRules = if (rule == AppBarRule.AUTO) value.appRules - pkg else value.appRules + (pkg to rule)); dirty = true
                            }, label = { Text(rule.title) }, modifier = Modifier.padding(end = 4.dp).testTag("topbar-rule-$pkg-${rule.name}"))
                        } }
                    } }
                }
            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (value != null) {
                    Text("На Home панель видна всегда. Глобальные режимы требуют добровольного разрешения overlay; выключатель общий с боковыми панелями.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Overlay-служба", Modifier.weight(1f)); Switch(enabled, { enabled = it; dirty = true }, modifier = Modifier.testTag("topbar-master"))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) { TopbarMode.entries.forEach { mode ->
                        FilterChip(value.mode == mode, { draft = value.copy(mode = mode); dirty = true }, label = { Text(mode.title) },
                            modifier = Modifier.padding(end = 4.dp).testTag("topbar-mode-${mode.name}"))
                    } }
                    Text("Auto-hide: сворачивание после 6 с, возврат маленькой кнопкой или через уведомление. «Показать 20 с» — временное ручное переопределение, включая исключения приложений.")
                    Text("Ширина ${value.widthPercent}%")
                    Slider(value.widthPercent.toFloat(), { draft = value.copy(widthPercent = it.toInt()); dirty = true }, valueRange = 50f..100f)
                    Text("Минимальная высота ${value.heightDp} dp")
                    Slider(value.heightDp.toFloat(), { draft = value.copy(heightDp = it.toInt()); dirty = true }, valueRange = 48f..80f)
                    Text("Непрозрачность ${value.opacityPercent}%")
                    Slider(value.opacityPercent.toFloat(), { draft = value.copy(opacityPercent = it.toInt()); dirty = true }, valueRange = 50f..100f)
                    Text("Предпросмотр")
                    Box(Modifier.fillMaxWidth(value.widthPercent / 100f).heightIn(min = value.heightDp.dp).graphicsLayer(alpha = value.opacityPercent / 100f)) {
                        Waybar(0, {}, emptyList(), Modifier.fillMaxWidth(), home = false, onHome = {}, onMenuRequest = {})
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Auto: скрывать Game/Video", Modifier.weight(1f)); Switch(value.contextAware, { draft = value.copy(contextAware = it); dirty = true }) }
                    TextButton(onClick = { rulesOpen = true }) { Text("Видимость для приложений · ${value.appRules.size} исключений") }
                    if (value.manuallyHidden) TextButton(onClick = { draft = value.copy(manuallyHidden = false); dirty = true }) { Text("Восстановить TopBar") }
                    Text("Полноэкранность/видео в чужом окне надёжно не определяются. Auto скрывает целиком приложения заявленной категории Game/Video, а не только fullscreen. Для браузеров и неизвестных категорий используйте исключения и ручное скрытие.")
                }
                // Permission status is refreshed after returning from Android settings.
                key(revision) {
                    Text("Overlay: ${if (Settings.canDrawOverlays(context)) "разрешён" else "не выдан"}; Usage Access: ${if (ForegroundHints(context).permitted()) "разрешён" else "не выдан"}")
                }
                TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) } }) { Text("Разрешение поверх приложений") }
                TextButton(onClick = { openSystemSettings(context, Settings.ACTION_USAGE_ACCESS_SETTINGS) }) { Text("Доступ к статистике для Auto/исключений") }
                Text("Usage Access используется только для текущего пакета/категории в памяти. Без него Auto не знает активное приложение. При Always hide и неизвестном пакете панель скрывается консервативно; восстановление и Home доступны.")
                if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { context.startActivity(Intent(context, dev.hyprdroid.launcher.quicksettings.TilePermissionActivity::class.java).putExtra("notifications", true)) }) { Text("Разрешить уведомление восстановления") }
                Text("При блокировке/выключении экрана overlay убирается. Клавиатура не теряет фокус; сигнал её видимости ограничен собственными Insets. Если уведомления отключены, вернуть панель можно через Home.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Button(onClick = { val next = draft ?: return@Button; saving = true; scope.launch {
                try {
                    repository.setTopbar { next.validated() }
                    repository.setOverlay(repository.settings.first().overlay.copy(enabled = enabled))
                    if (enabled && Settings.canDrawOverlays(context)) (context as? Activity)?.let { OverlaySession.start(it) }
                    if (!enabled) (context as? Activity)?.let { OverlaySession.stop(it) }
                    dirty = false
                    if (enabled && !Settings.canDrawOverlays(context)) error = "Настройки сохранены. Выдайте разрешение overlay в Android."
                } catch (_: Exception) { error = "Не удалось сохранить TopBar; повторите попытку." }
                finally { saving = false }
            } }, enabled = draft != null && dirty && !saving, modifier = Modifier.fillMaxWidth().testTag("topbar-save")) { Text(if (saving) "Сохранение…" else "Сохранить") }
        }
    }
}
