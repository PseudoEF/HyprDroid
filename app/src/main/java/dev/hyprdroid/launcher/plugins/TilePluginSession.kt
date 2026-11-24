package dev.hyprdroid.launcher.plugins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

internal sealed interface TileScreen {
    data object Loading : TileScreen
    data object Empty : TileScreen
    data class Ready(val root: TileNode) : TileScreen
    data class Error(val reason: String) : TileScreen
}

/** Each ModuleState owns one session. Only the owning tile can send its visible button IDs. */
internal class TilePluginSession(private val nanoTime: () -> Long = System::nanoTime) {
    var screen by mutableStateOf<TileScreen>(TileScreen.Loading)
        private set
    var busy by mutableStateOf(false)
        private set
    private var stopped = false
    private var localState = ""
    private var lastUpdate: Long? = null

    suspend fun update(
        event: String? = null,
        publish: suspend (() -> Unit) -> Unit = { it() },
        execute: suspend (TileRequest) -> PluginResult,
    ) {
        if (stopped || busy) return
        val now = nanoTime()
        if (lastUpdate?.let { now - it < 350_000_000L } == true) return
        if (event != null) {
            val root = (screen as? TileScreen.Ready)?.root ?: return
            if (!tileEventId.matches(event) || !root.containsButton(event)) return
        }
        lastUpdate = now
        busy = true
        try {
            if (event == null) publish { if (!stopped) screen = TileScreen.Loading }
            val result = execute(TileRequest(event, localState))
            publish publishResult@{
                if (stopped) return@publishResult
                when (result) {
                    is PluginResult.Failure -> screen = TileScreen.Error(result.message)
                    is PluginResult.Success -> {
                        val frame = result.tile
                        if (frame == null) {
                            screen = TileScreen.Error("Lua Runtime не вернул интерфейс тайла")
                        } else {
                            localState = frame.state
                            screen = frame.root?.let { TileScreen.Ready(it) } ?: TileScreen.Empty
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publish { if (!stopped) screen = TileScreen.Error("Не удалось обновить Lua-тайл") }
        } finally {
            busy = false
        }
    }

    fun stop() {
        stopped = true
        localState = ""
        screen = TileScreen.Empty
    }
}
