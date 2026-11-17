package dev.hyprdroid.launcher.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hyprdroid.launcher.data.SessionLauncher
import dev.hyprdroid.launcher.model.WORKSPACE_COUNT
import java.text.DateFormat
import java.util.Date

@Composable
fun WorkspaceSessionsScreen(initialWorkspace: Int, onClose: () -> Unit, model: SessionsViewModel = viewModel()) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val apps by model.apps.collectAsStateWithLifecycle()
    val events by model.events.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull() }
    val launcher = remember(activity) { activity?.let(::SessionLauncher) }
    var workspace by rememberSaveable { mutableIntStateOf(initialWorkspace) }
    var search by rememberSaveable { mutableStateOf("") }
    var picker by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize().blockHomeBehind().testTag("workspace-sessions"), color = Mocha.Base, contentColor = Mocha.Text) {
        LazyColumn(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth().sidebarClosingGesture(onClose = onClose)) {
                Text("Workspace Sessions · эксперимент", modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Закрыть") }
            } }
            item { Text("Назначения независимы от иконок рабочего стола. Изменения сохраняются сразу. Переключение workspace не останавливает и не запускает приложения автоматически.") }
            item { Row { Text("Включить сессии", Modifier.weight(1f)); Switch(settings?.enabled == true, model::enabled, enabled = settings != null, modifier = Modifier.testTag("sessions-enabled")) } }
            item {
                Row {
                    repeat(WORKSPACE_COUNT) { index ->
                        TextButton(onClick = { workspace = index }) { Text(if (workspace == index) "[${index + 1}]" else "${index + 1}") }
                    }
                }
            }
            val value = settings
            if (value != null) {
                val assigned = value.apps[workspace]
                items(assigned, key = { "assigned:$it" }) { component ->
                    val app = apps.find { it.componentId == component }
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text(app?.label ?: "Недоступно: $component")
                            TextButton(onClick = { if (value.enabled && launcher != null) model.record(launcher.launch(workspace, listOf(component), false)) }, enabled = value.enabled && app != null && launcher != null) { Text("Открыть / вернуться") }
                        }
                        TextButton(onClick = { model.assign(workspace, component, false) }) { Text("Убрать") }
                    }
                }
                item {
                    if (assigned.isEmpty()) Text("Сессия пуста. Выберите до двух приложений ниже.")
                    Button(onClick = { if (value.enabled && launcher != null) model.record(launcher.launch(workspace, assigned, true)) }, enabled = value.enabled && assigned.size == 2 && assigned.all { id -> apps.any { it.componentId == id } } && launcher != null) { Text("Попробовать Split Screen") }
                    Text("Запрос может открыть приложения последовательно или не разделить экран. Реальная пара не подтверждается: Android не даёт доступ к чужим задачам.")
                    Text("Запасной путь: откройте первое приложение → системные Недавние → значок приложения → Разделить экран → выберите второе. Названия и поддержка зависят от Android. Вернуться к этой инструкции можно через HOME.")
                }
                item { TextButton(onClick = { picker = !picker }) { Text(if (picker) "Скрыть список приложений" else "Добавить приложение в сессию") } }
                if (picker) {
                item { OutlinedTextField(search, { search = it }, label = { Text("Поиск приложений для сессии") }, modifier = Modifier.fillMaxWidth()) }
                items(apps.filter { it.componentId !in assigned && it.label.contains(search, ignoreCase = true) }, key = { it.componentId }) { app ->
                    Row {
                        Image(app.icon.asImageBitmap(), null, Modifier.size(36.dp))
                        Text(app.label, Modifier.weight(1f).padding(horizontal = 8.dp))
                        TextButton(onClick = { model.assign(workspace, app.componentId, true) }, enabled = assigned.size < 2) { Text("Назначить") }
                    }
                }
            }
            }
            item { Text("Диагностика", style = MaterialTheme.typography.titleMedium); Text(launcher?.diagnostics() ?: "Нет активного окна для запуска") }
            item { Text("Последние попытки (до 50)", style = MaterialTheme.typography.titleMedium); Text("Локально: время, номер workspace, действие и ответ API. Содержимое приложений и список чужих задач не собираются."); TextButton(onClick = model::clearLog) { Text("Очистить журнал") } }
            items(events.reversed()) { event -> Text("${DateFormat.getDateTimeInstance().format(Date(event.time))} · WS ${event.workspace + 1} · ${event.action}\n${event.result}") }
            item { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
}
