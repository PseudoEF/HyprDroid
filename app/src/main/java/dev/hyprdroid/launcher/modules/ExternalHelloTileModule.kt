package dev.hyprdroid.launcher.modules

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
            ContentForGeneration(modifier, generation)
        }
    }

    @Composable
    private fun ContentForGeneration(modifier: Modifier, generation: Long) {
        val context = LocalContext.current.applicationContext
        val manager = remember(context) { AndroidPackages.manager(context) }
        val session = remember { TilePluginSession() }
        DisposableEffect(session) { onDispose { session.stop() } }
        val scope = rememberCoroutineScope()
        suspend fun update(event: String? = null) = session.update(event) execute@{ request ->
            if (!BuildConfig.EXTERNAL_LUA) return@execute PluginResult.Failure("Экспериментальный Lua отключён")
            try {
                val bytes = withContext(Dispatchers.IO) { manager.verifiedHelloTile() }
                if (manager.helloTileGeneration.value != generation) {
                    return@execute PluginResult.Failure("Доверенная запись Hello Tile изменилась")
                }
                val result = ExperimentalExternalLua.run(context, bytes, request)
                if (manager.helloTileGeneration.value == generation) result
                else PluginResult.Failure("Доверенная запись Hello Tile изменилась")
            } catch (error: IOException) {
                PluginResult.Failure(error.message ?: "Пакет не подтверждён")
            }
        }
        LaunchedEffect(session) { update() }
        when (val screen = session.screen) {
            is TileScreen.Ready -> RenderTile(
                screen.root, modifier.clipToBounds().padding(6.dp), !session.busy,
            ) { event -> scope.launch { update(event) } }
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
