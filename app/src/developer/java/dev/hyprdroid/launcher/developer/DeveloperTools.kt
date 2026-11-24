package dev.hyprdroid.launcher.developer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.hyprdroid.launcher.modules.*
import dev.hyprdroid.launcher.packages.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

@Composable
internal fun DeveloperTools(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext
    val files = remember { DeveloperFiles() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), files::picked)
    val commands = remember(app, files, picker) {
        val store = DeveloperPackageStore(app.noBackupFilesDir.resolve("developer-packages")) { id ->
            id == HELLO_TILE_ID || id == DROIDFETCH_SAMPLE_ID || id in v2FixtureIds ||
                AndroidPackages.manager(app).available(id).any { it.id == id } ||
                AndroidPackages.manager(app).installed().any { it.id == id }
        }
        DeveloperCommands(
            store,
            read = { limit ->
                val uri = files.pick { picker.launch(arrayOf("*/*")) }
                if (uri == null) null else withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { readPackageBytes(it, limit) }
                        ?: throw IOException("Не удалось открыть выбранный файл")
                }
            },
            confirm = files::confirm,
        )
    }
    CompositionLocalProvider(LocalTerminalExtension provides commands::execute) { content() }
    files.prompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { prompt.answer.complete(false) },
            title = { Text(prompt.title) },
            text = { Text(prompt.description) },
            confirmButton = { TextButton(onClick = { prompt.answer.complete(true) }) { Text("Подтвердить") } },
            dismissButton = { TextButton(onClick = { prompt.answer.complete(false) }) { Text("Отмена") } },
        )
    }
}

private class DeveloperFiles {
    private var pending: CompletableDeferred<Uri?>? = null
    var prompt by mutableStateOf<Prompt?>(null)
        private set

    data class Prompt(val title: String, val description: String, val answer: CompletableDeferred<Boolean>)

    suspend fun pick(open: () -> Unit): Uri? {
        if (pending != null) throw IOException("Предыдущий выбор файла ещё открыт")
        val request = CompletableDeferred<Uri?>()
        pending = request
        try {
            open()
        } catch (error: RuntimeException) {
            pending = null
            throw IOException("Не удалось открыть системный выбор файлов", error)
        }
        return request.await()
    }

    fun picked(uri: Uri?) {
        val request = pending
        pending = null
        request?.complete(uri)
    }

    suspend fun confirm(title: String, description: String): Boolean {
        val request = Prompt(title, description, CompletableDeferred())
        prompt = request
        return try { request.answer.await() } finally { prompt = null }
    }
}

internal class DeveloperCommands(
    private val store: DeveloperPackageStore,
    private val read: suspend (Int) -> ByteArray?,
    private val confirm: suspend (String, String) -> Boolean,
) {
    private val selection = Mutex()

    fun execute(args: List<String>): CommandResult? {
        if (args.size == 3 && args.take(2) == listOf("tile", "add") &&
            args[2] != HELLO_TILE_ID && args[2] != DROIDFETCH_SAMPLE_ID && args[2] !in v2FixtureIds
        ) return operation {
            val local = withContext(Dispatchers.IO) { store.info(args[2]) }
            CommandOperationResult(listOf(if (local == null) "Пакет не допущен к запуску в тайле."
                else "Исполнение локальных пакетов отключено в Developer Edition."), failed = true)
        }
        if (args.firstOrNull() != "dev") return null
        val command = args.drop(1)
        return when {
            command == listOf("trust", "add") -> operation {
                exclusiveSelection {
                    val pem = read(DeveloperPackageStore.KEY_LIMIT) ?: return@exclusiveSelection cancelled()
                    val key = decodeDeveloperPublicKey(pem)
                    if (!confirm("Доверять публичному ключу?",
                            "Ed25519\nSHA-256 fingerprint: ${key.fingerprint}\n" +
                                "Этот ключ сможет подтверждать локальные пакеты. Исполнение отключено."))
                        return@exclusiveSelection cancelled()
                    withContext(Dispatchers.IO) { ensureActive(); store.trust(pem) }
                    output("Добавлен ключ Ed25519 ${key.fingerprint}")
                }
            }
            command == listOf("trust", "list") -> operation {
                val keys = withContext(Dispatchers.IO) { store.keys() }
                CommandOperationResult(keys.map { "Ed25519 · ${it.fingerprint}" }.ifEmpty { listOf("Нет доверенных ключей.") })
            }
            command.size == 3 && command.take(2) == listOf("trust", "remove") -> operation {
                withContext(Dispatchers.IO) { store.revoke(command[2]) }
                output("Доверие отозвано. Связанные локальные пакеты заблокированы.")
            }
            command == listOf("import") -> operation {
                exclusiveSelection {
                    if (!confirm("Импорт локального пакета", "Сначала выберите .hyp, затем .signature.json от SDK."))
                        return@exclusiveSelection cancelled()
                    val archive = read(DeveloperPackageStore.ARCHIVE_LIMIT) ?: return@exclusiveSelection cancelled()
                    val metadata = read(DeveloperPackageStore.METADATA_LIMIT) ?: return@exclusiveSelection cancelled()
                    val preview = withContext(Dispatchers.IO) { store.preview(archive, metadata) }
                    if (!confirm("Импортировать пакет?", description(preview.record, true).joinToString("\n")))
                        return@exclusiveSelection cancelled()
                    withContext(Dispatchers.IO) { ensureActive(); store.install(preview) }
                    output("Импортирован ${preview.record.entry.manifest.id}. Исполнение отключено.")
                }
            }
            command == listOf("list") -> operation {
                withContext(Dispatchers.IO) {
                    CommandOperationResult(store.packages().map {
                        "${it.entry.manifest.id} ${it.entry.manifest.version} · ${status(store.trusted(it))}"
                    }.ifEmpty { listOf("Нет локальных пакетов.") })
                }
            }
            command.size == 2 && command[0] == "info" -> operation {
                withContext(Dispatchers.IO) {
                    val record = store.info(command[1]) ?: throw PackageException("Локальный пакет не найден")
                    CommandOperationResult(description(record, store.trusted(record)))
                }
            }
            command.size == 2 && command[0] == "remove" -> operation {
                withContext(Dispatchers.IO) { store.remove(command[1]) }
                output("Локальный пакет удалён.")
            }
            else -> CommandResult.Output(
                "hypr dev trust add | list | remove <fingerprint>\nhypr dev import | list | info <id> | remove <id>",
            )
        }
    }

    private suspend fun exclusiveSelection(action: suspend () -> CommandOperationResult): CommandOperationResult {
        if (!selection.tryLock()) return CommandOperationResult(listOf("Другой выбор файла уже выполняется."), true)
        return try { action() } finally { selection.unlock() }
    }

    private fun operation(body: suspend () -> CommandOperationResult) = CommandResult.Stream(listOf(CommandEvent.Operation {
        try {
            body()
        } catch (error: IOException) {
            CommandOperationResult(listOf(error.message ?: "Ошибка чтения или записи локальных данных"), true)
        } catch (_: SecurityException) {
            CommandOperationResult(listOf("Нет доступа к выбранному файлу."), true)
        }
    }))

    private fun cancelled() = output("Отменено.")
    private fun output(message: String) = CommandOperationResult(listOf(message))

    private fun status(trusted: Boolean) = if (trusted) "Imported / Verified / Execution disabled"
        else "Imported / Trust revoked / Execution disabled"

    private fun description(record: DeveloperPackageStore.Record, trusted: Boolean) = with(record.entry) {
        listOf(
            "${manifest.id} ${manifest.version} · API ${manifest.apiVersion}",
            "Источник: Local developer",
            "SHA-256: $sha256",
            "Издатель Ed25519: $keyId",
            "Capabilities: ${manifest.permissions.sorted().joinToString().ifEmpty { "—" }}",
            status(trusted),
        )
    }
}
