package dev.hyprdroid.launcher.modules

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class ModuleSummary(val id: String, val title: String)

internal sealed interface CommandResult {
    data class Output(val text: String) : CommandResult
    object Clear : CommandResult
}

/**
 * Safe, built-in HyprDroid command language. It never starts processes, evaluates shell syntax or
 * touches the file system; the only commands it understands are help, clear, modules and date.
 */
internal class CommandProcessor(
    private val modules: () -> List<ModuleSummary> = { emptyList() },
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

    fun execute(raw: String): CommandResult {
        val command = raw.trim().lowercase(Locale.ROOT)
        if (command.isEmpty()) return CommandResult.Output("")
        return when (command) {
            "help" -> CommandResult.Output(
                listOf(
                    "Доступные команды:",
                    "help — эта справка",
                    "clear — очистить журнал",
                    "modules — список модулей",
                    "date — текущие дата и время",
                ).joinToString("\n"),
            )
            "clear" -> CommandResult.Clear
            "modules" -> {
                val items = modules()
                CommandResult.Output(
                    if (items.isEmpty()) "Нет зарегистрированных модулей."
                    else items.joinToString("\n") { "${it.id} — ${it.title}" },
                )
            }
            "date" -> CommandResult.Output(now().format(dateFormat))
            else -> CommandResult.Output(
                "Неизвестная команда: ${raw.trim()}\nЭто не shell. Введите help для списка безопасных команд.",
            )
        }
    }
}
