package dev.hyprdroid.launcher.modules

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class ModuleSummary(val id: String, val title: String)

internal sealed interface CommandResult {
    data class Output(val text: String, val failed: Boolean = false) : CommandResult
    object Clear : CommandResult
    data class Stream(val events: List<CommandEvent>) : CommandResult
}

internal sealed interface CommandEvent {
    data class Line(val text: String) : CommandEvent
    data class Progress(val text: String, val cursorOffset: Int = text.length) : CommandEvent
    data class Pause(val millis: Long) : CommandEvent
    data class Operation(val run: suspend () -> CommandOperationResult) : CommandEvent
}

internal data class CommandOperationResult(val lines: List<String>, val failed: Boolean = false)

/** Routes built-in commands. Streaming operations run separately from command parsing. */
internal class CommandProcessor(
    private val modules: () -> List<ModuleSummary> = { emptyList() },
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val droidfetch: () -> String = { "DroidFetch unavailable" },
    private val packageCommands: PackageCommands? = null,
    private val luaPluginCommands: LuaPluginCommands? = null,
    private val history: TerminalHistory? = null,
    private val tileCommands: TileCommands? = null,
) {
    private val whitespace = Regex("\\s+")
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

    fun execute(raw: String): CommandResult {
        val command = raw.trim().lowercase(Locale.ROOT)
        if (command.isEmpty()) return CommandResult.Output("")
        val first = command.takeWhile { !it.isWhitespace() }
        if (first == "hypr" && packageCommands != null) {
            val args = command.split(whitespace).drop(1)
            if (args.firstOrNull() == "tile") {
                return tileCommands?.execute(args.drop(1))
                    ?: CommandResult.Output("Команды тайлов доступны внутри рабочего пространства.", failed = true)
            }
            return packageCommands.execute(args)
        }
        if (first == "lua" && luaPluginCommands != null) {
            return luaPluginCommands.execute(command.split(whitespace).drop(1))
        }
        if (first == "history" && history != null) {
            val args = command.split(whitespace)
            if (args != listOf("history") && args != listOf("history", "clear")) {
                return CommandResult.Output("Использование: history [clear]", failed = true)
            }
            return CommandResult.Stream(listOf(CommandEvent.Operation {
                try {
                    if (args.size == 2) {
                        history.clear()
                        CommandOperationResult(listOf("История очищена."))
                    } else {
                        val entries = history.entries()
                        CommandOperationResult(entries.ifEmpty { listOf("История пуста.") })
                    }
                } catch (_: java.io.IOException) {
                    CommandOperationResult(listOf("Ошибка доступа к истории команд."), failed = true)
                }
            }))
        }
        return when (command) {
            "help" -> CommandResult.Output(
                listOf(
                    "Доступные команды:",
                    "help — эта справка",
                    "clear — очистить журнал",
                    "history [clear] — показать или очистить историю команд",
                    "modules — список модулей",
                    "date — текущие дата и время",
                    "droidfetch — ASCII-информация об устройстве",
                    "demo install — имитация потоковой установки (без shell)",
                    "hypr help — менеджер пакетов",
                    "lua help — встроенные Lua-модули",
                ).joinToString("\n"),
            )
            "droidfetch" -> CommandResult.Output(droidfetch())
            "clear" -> CommandResult.Clear
            "modules" -> {
                val items = modules()
                CommandResult.Output(
                    if (items.isEmpty()) "Нет зарегистрированных модулей."
                    else items.joinToString("\n") { "${it.id} — ${it.title}" },
                )
            }
            "date" -> CommandResult.Output(now().format(dateFormat))
            "demo install" -> CommandResult.Stream(demoInstallEvents())
            "demo" -> CommandResult.Output(
                "Использование: demo install — имитация потоковой установки (shell не запускается).",
            )
            else -> CommandResult.Output(
                "Неизвестная команда: ${raw.trim()}\nЭто не shell. Введите help для списка безопасных команд.",
                failed = true,
            )
        }
    }

    /**
     * Deterministic demo install script. Every pause stays between 60 and 220 ms and the whole
     * script runs for roughly 2.7 s; progress is always emitted on a single line via [CommandEvent.Progress].
     */
    private fun demoInstallEvents(): List<CommandEvent> = listOf(
        CommandEvent.Line("demo: имитация установки модуля weather"),
        CommandEvent.Pause(160),
        CommandEvent.Line("Подготовка окружения…"),
        CommandEvent.Pause(140),
        CommandEvent.Line("Разрешение зависимостей…"),
        CommandEvent.Pause(120),
        CommandEvent.Line("  core 1.4.0 — уже установлен"),
        CommandEvent.Pause(90),
        CommandEvent.Line("  ui-kit 2.1.0 — будет добавлен"),
        CommandEvent.Pause(90),
        CommandEvent.Line("Скачивание weather 1.2.0 (2.4 МБ)"),
        CommandEvent.Pause(100),
        demoProgress("Загрузка", 0),
        CommandEvent.Pause(160),
        demoProgress("Загрузка", 20),
        CommandEvent.Pause(160),
        demoProgress("Загрузка", 40),
        CommandEvent.Pause(160),
        demoProgress("Загрузка", 60),
        CommandEvent.Pause(160),
        demoProgress("Загрузка", 80),
        CommandEvent.Pause(160),
        demoProgress("Загрузка", 100),
        CommandEvent.Pause(140),
        CommandEvent.Line("Контрольная сумма SHA-256 совпадает"),
        CommandEvent.Pause(180),
        CommandEvent.Line("Распаковка и проверка файлов…"),
        CommandEvent.Pause(90),
        demoProgress("Файлы", 30),
        CommandEvent.Pause(120),
        demoProgress("Файлы", 60),
        CommandEvent.Pause(120),
        demoProgress("Файлы", 90),
        CommandEvent.Pause(120),
        demoProgress("Файлы", 100),
        CommandEvent.Pause(100),
        CommandEvent.Line("Регистрация модуля weather…"),
        CommandEvent.Pause(140),
        CommandEvent.Line("Установлено: weather 1.2.0 (2.4 МБ)"),
        CommandEvent.Pause(80),
        CommandEvent.Line("Готово. Модуль доступен в списке модулей."),
        CommandEvent.Pause(120),
    )

    private fun demoProgress(label: String, percent: Int): CommandEvent.Progress {
        val filled = percent / 10
        val bar = "#".repeat(filled) + "-".repeat(10 - filled)
        val text = "$label [$bar] ${percent.toString().padStart(3)}%"
        return CommandEvent.Progress(text, cursorOffset = label.length + 2 + filled)
    }
}
