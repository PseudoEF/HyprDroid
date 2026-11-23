package dev.hyprdroid.launcher.modules

import dev.hyprdroid.launcher.packages.HyprPackageManager
import dev.hyprdroid.launcher.packages.PackageException
import dev.hyprdroid.launcher.packages.PackageManifest
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PackageCommands(private val manager: () -> HyprPackageManager) {
    fun execute(args: List<String>): CommandResult = when (args.firstOrNull()) {
        null, "help" -> if (args.size <= 1) CommandResult.Output(USAGE) else invalid()
        "repo" -> when (args) {
            listOf("repo", "list") -> operation { repositoryStatus() }
            listOf("repo", "update") -> operation("Проверка каталога GitHub…") {
                listOf("Проверена ревизия каталога ${refreshRepository()}.")
            }
            else -> invalid()
        }
        "search" -> if (args.size >= 2) {
            operation { listOf(catalogDescription) + available(args.drop(1).joinToString(" ")).summary() }
        } else invalid()
        "list" -> when (args) {
            listOf("list") -> operation { listOf(catalogDescription) + available().summary() }
            listOf("list", "--installed") -> operation { installed().summary("Нет установленных пакетов.") }
            else -> invalid()
        }
        "info" -> withPackageId(args) { id ->
            val details = info(id)
            val manifest = details.available ?: checkNotNull(details.installed)
            listOf(
                manifest.label(),
                "Каталог: ${details.available?.version ?: "нет записи"}",
                "Установлено: ${details.installed?.version ?: "нет"}",
                "Plugin API ${manifest.apiVersion}, runtime ${manifest.runtime}, вход ${manifest.entry}",
                "Запрошенные разрешения: ${manifest.permissions.sorted().joinToString().ifEmpty { "нет" }}",
                "Зависимости: ${manifest.dependencies.toSortedMap().entries.joinToString { (id, version) ->
                    "$id >= $version"
                }.ifEmpty { "нет" }}",
            )
        }
        "install" -> withPackageId(args, "Проверка и установка пакета…") { id ->
            val changes = install(id)
            if (changes.isEmpty()) listOf("Уже установлена актуальная или более новая версия $id.")
            else changes.map { "Установлено: ${it.label()}" }
        }
        "remove" -> withPackageId(args, "Удаление пакета…") { id ->
            listOf("Удалено: ${remove(id).label()}")
        }
        "update" -> if (args.size == 1) {
            operation("Проверка обновлений…") {
                val changes = update()
                if (changes.isEmpty()) listOf("В этом каталоге нет обновлений для установленных пакетов.")
                else changes.map { "Применено: ${it.label()}" }
            }
        } else invalid()
        else -> invalid()
    }

    private fun withPackageId(
        args: List<String>,
        status: String? = null,
        action: HyprPackageManager.(String) -> List<String>,
    ): CommandResult = if (args.size == 2) operation(status) { action(args[1]) } else invalid()

    private fun operation(status: String? = null, action: HyprPackageManager.() -> List<String>): CommandResult {
        val events = buildList {
            if (status != null) add(CommandEvent.Line(status))
            add(CommandEvent.Operation {
                withContext(Dispatchers.IO) {
                    try {
                        CommandOperationResult(manager().action())
                    } catch (error: PackageException) {
                        CommandOperationResult(listOf("Ошибка hypr: ${error.message}"), failed = true)
                    } catch (_: IOException) {
                        CommandOperationResult(listOf("Ошибка hypr: не удалось загрузить, прочитать или сохранить данные пакета."), failed = true)
                    } catch (_: SecurityException) {
                        CommandOperationResult(listOf("Ошибка hypr: Android отказал в доступе к хранилищу."), failed = true)
                    }
                }
            })
        }
        return CommandResult.Stream(events)
    }

    private fun invalid() = CommandResult.Output("Неверные аргументы. Использование: hypr help", failed = true)

    private fun List<PackageManifest>.summary(empty: String = "Пакеты не найдены."): List<String> =
        if (isEmpty()) listOf(empty) else map { it.label() }

    private fun PackageManifest.label() = "$id $version"

    private companion object {
        val USAGE = listOf(
            "Hypr Package Manager",
            "hypr help",
            "hypr repo list",
            "hypr repo update",
            "hypr search <query>",
            "hypr list",
            "hypr list --installed",
            "hypr info <module>",
            "hypr install <module>",
            "hypr remove <module>",
            "hypr update",
            "hypr tile add <module>",
            "hypr tile list",
            "hypr tile remove <tile-id>",
        ).joinToString("\n")
    }
}
