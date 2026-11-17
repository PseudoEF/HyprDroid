package dev.hyprdroid.launcher.ui

import android.os.Build
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
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Editing one placement uses the same ordered module editor as Android widgets. */
@Composable
internal fun QuickSettingsEditor(onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { ShellRepository(context) }
    val controller = rememberQuickController()
    val scope = rememberCoroutineScope()
    var placement by rememberSaveable { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf<PanelSettings?>(null) }
    var previous by remember { mutableStateOf<PanelSettings?>(null) }
    var topbar by remember { mutableStateOf<QuickSettingsConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var pendingPlacement by remember { mutableStateOf<Int?>(null) }
    var closing by remember { mutableStateOf(false) }
    fun requestClose() { if (!saving) { if (dirty) closing = true else onClose() } }
    fun requestPlacement(next: Int) { if (!saving && next != placement) { if (dirty) pendingPlacement = next else placement = next } }
    BackHandler { requestClose() }
    LaunchedEffect(placement) {
        draft = null; previous = null; error = null
        try {
            val qs = repository.quickSettings.first()
            val shell = repository.settings.first()
            topbar = qs
            val initial = when(placement) { 0 -> PanelSettings(modules = qs.topbar); 1 -> shell.panels.left; else -> shell.panels.right }
            draft = initial; previous = initial; dirty = false
        } catch (_: Exception) { error = "Не удалось прочитать настройки. Исходные данные не изменены." }
    }
    if (closing || pendingPlacement != null) AlertDialog(onDismissRequest = { closing = false; pendingPlacement = null },
        title = { Text("Отменить несохранённые изменения?") },
        confirmButton = { TextButton(onClick = {
            val next = pendingPlacement; closing = false; pendingPlacement = null; dirty = false
            if (next != null) placement = next else onClose()
        }) { Text("Отменить изменения") } }, dismissButton = { TextButton(onClick = { closing = false; pendingPlacement = null }) { Text("Продолжить редактирование") } })
    Surface(Modifier.fillMaxSize().blockHomeBehind().testTag("quick-settings-editor"), color = Mocha.Base, contentColor = Mocha.Text) {
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            Row(Modifier.fillMaxWidth().sidebarClosingGesture(onClose = { requestClose() })) {
                Text("Редактор Quick Settings", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { requestClose() }, enabled = !saving) { Text("Закрыть") }
            }
            Row { listOf("Верх", "Слева", "Справа").forEachIndexed { index, name ->
                TextButton(onClick = { requestPlacement(index) }, enabled = !saving) { Text(if (placement == index) "[$name]" else name) }
            } }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Выберите размещение. Порядок и включение меняются только после сохранения. Боковые панели сохраняют собственные Home/Overlay-настройки.")
                val value = draft
                if (value != null) {
                    Text("Долгое нажатие плитки открывает настройки. Для перестановки выберите модуль и используйте «Выше/Ниже».")
                    // Other modules remain in the draft but are edited by the existing full panel editor.
                    val selected = value.modules.filter { QuickTile.fromModule(it) != null }
                    PanelModuleEditor(value.copy(modules = selected, widgets = emptyList()), { edited ->
                        val replacement = edited.modules.toMutableList()
                        val merged = value.modules.mapNotNull { id -> if (QuickTile.fromModule(id) == null) id else if (replacement.isNotEmpty()) replacement.removeAt(0) else null } + replacement
                        draft = value.copy(modules = merged); dirty = true
                    }, !saving)
                    QuickTile.entries.filter { it.moduleId !in value.modules }.forEach { tile ->
                        TextButton(onClick = { draft = value.copy(modules = value.modules + tile.moduleId); dirty = true }, enabled = !saving) { Text("Добавить ${tile.title}") }
                    }
                    Text("Предпросмотр", style = MaterialTheme.typography.titleSmall)
                    selected.forEach { QuickSettingsTile(requireNotNull(QuickTile.fromModule(it)), interactive = false) }
                }
                Text("Дополнительный доступ — только по вашему выбору", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { controller.writeAccess() }) { Text("Разрешить яркость и автоповорот") }
                if (Build.VERSION.SDK_INT >= 31) TextButton(onClick = { controller.permission(true) }) { Text("Разрешить чтение состояния Bluetooth") }
                Text("Wi-Fi и «Не беспокоить» используют системный интерфейс. CAMERA запрашивается только при выборе фонарика; разрешения не нужны для обычного лаунчера.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Button(onClick = { val value = draft ?: return@Button; saving = true; scope.launch {
                try {
                    if (placement == 0) repository.setQuickSettings(requireNotNull(topbar).copy(topbar = value.modules))
                    else repository.setPanel(if (placement == 1) PanelSide.LEFT else PanelSide.RIGHT, value, previous)
                    previous = value; dirty = false
                } catch (_: Exception) { error = "Сохранение не выполнено. Черновик оставлен для повторной попытки." }
                finally { saving = false }
            } }, enabled = draft != null && dirty && !saving, modifier = Modifier.fillMaxWidth().testTag("quick-settings-save")) { Text(if (saving) "Сохранение…" else "Сохранить размещение") }
        }
    }
}
