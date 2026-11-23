package dev.hyprdroid.launcher.modules

import androidx.compose.runtime.staticCompositionLocalOf
import dev.hyprdroid.launcher.packages.DROIDFETCH_SAMPLE_ID
import dev.hyprdroid.launcher.packages.PackageException
import dev.hyprdroid.launcher.workspaceengine.WindowId
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TileCommandPort(
    val add: () -> WindowId?,
    val list: () -> List<WindowId>,
    val remove: (WindowId) -> Boolean,
)

internal val LocalTileCommandPort = staticCompositionLocalOf<TileCommandPort?> { null }

internal class TileCommands(
    private val port: TileCommandPort,
    private val authorize: () -> Unit,
) {
    fun execute(args: List<String>): CommandResult {
        if (args == listOf("list")) {
            return CommandResult.Output(port.list().joinToString("\n") {
                "${it.value} · $DROIDFETCH_SAMPLE_ID"
            }.ifEmpty { "В этом пространстве нет Lua-тайлов." })
        }
        if (args.size != 2) return invalid()
        val id = args[1]
        return when (args[0]) {
            "add" -> {
                if (id != DROIDFETCH_SAMPLE_ID) {
                    return CommandResult.Output("Пакет не допущен к запуску в тайле.", failed = true)
                }
                CommandResult.Stream(listOf(CommandEvent.Operation {
                    try {
                        withContext(Dispatchers.IO) { authorize() }
                        val window = port.add()
                        if (window == null) failure("В этом пространстве нет места для нового тайла.")
                        else CommandOperationResult(listOf("Создан тайл ${window.value} · $id"))
                    } catch (error: PackageException) {
                        failure(error.message ?: "Доступ к тайлу запрещён.")
                    } catch (_: IOException) {
                        failure("Не удалось проверить установленный пакет.")
                    }
                }))
            }
            "remove" -> {
                if (id.length in 1..80 && port.remove(WindowId(id))) {
                    CommandResult.Output("Тайл $id удалён.")
                } else CommandResult.Output("Lua-тайл $id отсутствует в этом пространстве.", failed = true)
            }
            else -> invalid()
        }
    }

    private fun failure(reason: String) = CommandOperationResult(listOf(reason), failed = true)
    private fun invalid() = CommandResult.Output("Использование: hypr tile add | list | remove", failed = true)
}
