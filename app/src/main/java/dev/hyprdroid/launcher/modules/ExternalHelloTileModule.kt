package dev.hyprdroid.launcher.modules

import android.util.Log
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.data.TileStateSlot
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.external.ExperimentalExternalLua
import dev.hyprdroid.launcher.packages.AndroidPackages
import dev.hyprdroid.launcher.packages.HELLO_TILE_ID
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TilePluginSession
import dev.hyprdroid.launcher.plugins.TileScreen
import dev.hyprdroid.launcher.ui.Mocha
import dev.hyprdroid.launcher.workspaceengine.WindowId
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object ExternalHelloTileModule : TileModule {
    override val id = HELLO_TILE_ID
    override val title = "Hello HyprDroid"
    override val summary = "Экспериментальный внешний Lua"
    override val glyph = ModuleGlyph.COMMAND

    override fun createState(windowId: WindowId): ModuleState = object : ModuleState(windowId) {}

    @Composable
    override fun Content(state: ModuleState, modifier: Modifier) {
        val context = LocalContext.current.applicationContext
        val manager = remember(context) { AndroidPackages.manager(context) }
        val generation by manager.helloTileGeneration.collectAsState()
        key(state, generation) {
            ContentForGeneration(state.windowId, modifier, generation)
        }
    }

    @Composable
    private fun ContentForGeneration(windowId: WindowId, modifier: Modifier, generation: Long) {
        val context = LocalContext.current.applicationContext
        val manager = remember(context) { AndroidPackages.manager(context) }
        val session = remember { TilePluginSession() }
        val store = remember(context) { WorkspaceEngineStore(context) }
        val slot = remember(windowId) { TileStateSlot(HELLO_TILE_ID, schema = 1, windowId = windowId) }
        DisposableEffect(session) {
            Log.d("HyprLuaTile", "UI session opened; generation=$generation")
            onDispose {
                Log.d("HyprLuaTile", "UI session closed; composition left or authorization changed")
                session.stop()
            }
        }
        val scope = rememberCoroutineScope()
        suspend fun update(event: String? = null) = session.update(event, publish = { apply ->
            manager.externalAdmission.commit(generation) {
                apply()
                if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaGate", "hello published generation=$generation screen=${session.screen.javaClass.simpleName}")
            }
        }) execute@{ request ->
            if (!BuildConfig.EXTERNAL_LUA) return@execute PluginResult.Failure("Экспериментальный Lua отключён")
            try {
                val (result, saved) = manager.externalAdmission.run(generation) {
                    val (bytes, state) = withContext(Dispatchers.IO) {
                        manager.verifiedHelloTile() to store.tileState(slot)
                    }
                    if (request.event == null) {
                        Log.d("HyprLuaTile", "Rendering from Core state; initial mount or explicit retry")
                    }
                    ExperimentalExternalLua.run(context, bytes, request.copy(state = state),
                        dispatch = { manager.externalAdmission.dispatch(generation, it) },
                    ) {
                        manager.helloTileGeneration.value == generation
                    } to state
                } ?: return@execute PluginResult.Failure("Доверенная запись Hello Tile изменилась")
                val updated = (result as? PluginResult.Success)?.tile?.state
                if (updated != null && request.event != null) {
                    val commitStarted = if (BuildConfig.V2_FIXTURE_LUA) SystemClock.elapsedRealtime() else 0L
                    manager.externalAdmission.commit(generation) {
                        store.saveTileState(slot, saved, updated)
                        if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaGate", "hello commitMs=${SystemClock.elapsedRealtime() - commitStarted} generation=$generation")
                    }
                }
                result
            } catch (error: IOException) {
                PluginResult.Failure(error.message ?: "Пакет не подтверждён")
            }
        }
        LaunchedEffect(session) { update() }
        when (val screen = session.screen) {
            is TileScreen.Ready -> RenderTile(
                screen.root, modifier.clipToBounds().padding(6.dp), !session.busy,
            ) { event ->
                if (BuildConfig.V2_FIXTURE_LUA) Log.i("HyprLuaGate", "hello click generation=$generation busy=${session.busy}")
                scope.launch { update(event) }
            }
            else -> Column(modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
                Text(when (screen) {
                    TileScreen.Loading -> "Загрузка Hello Tile…"
                    TileScreen.Empty -> "Нет содержимого"
                    is TileScreen.Error -> screen.reason
                    is TileScreen.Ready -> ""
                }, color = Mocha.Text)
                if (screen is TileScreen.Error) {
                    TextButton(onClick = { scope.launch { update() } }) { Text("Повторить") }
                }
            }
        }
    }
}
