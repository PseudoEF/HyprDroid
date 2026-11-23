package dev.hyprdroid.launcher.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.packages.AndroidPackages
import dev.hyprdroid.launcher.packages.DROIDFETCH_SAMPLE_ID
import dev.hyprdroid.launcher.packages.PackageException
import dev.hyprdroid.launcher.plugins.AndroidLuaPlugins
import dev.hyprdroid.launcher.plugins.BuiltInLuaPlugin
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TileElement
import dev.hyprdroid.launcher.plugins.TileNode
import dev.hyprdroid.launcher.plugins.TilePluginSession
import dev.hyprdroid.launcher.plugins.TileScreen
import dev.hyprdroid.launcher.ui.Mocha
import dev.hyprdroid.launcher.workspaceengine.WindowId
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object DroidFetchTileModule : TileModule {
    override val id = DROIDFETCH_SAMPLE_ID
    override val title = "DroidFetch · Lua"
    override val summary = "Устройство и батарея · требуется установленный пакет"
    override val glyph = ModuleGlyph.SYSTEM

    private class State(id: WindowId) : ModuleState(id) {
        val session = TilePluginSession()
        override fun dispose() = session.stop()
    }

    override fun createState(windowId: WindowId): ModuleState = State(windowId)

    @Composable
    override fun Content(state: ModuleState, modifier: Modifier) {
        val session = (state as State).session
        val context = LocalContext.current.applicationContext
        val scope = rememberCoroutineScope()
        suspend fun update(event: String? = null) = session.update(event) { request ->
            try {
                withContext(Dispatchers.IO) { AndroidPackages.manager(context).requireDroidFetchTileSample() }
                AndroidLuaPlugins.run(context, BuiltInLuaPlugin.DROIDFETCH_TILE, request)
            } catch (error: PackageException) {
                PluginResult.Failure(error.message ?: "Доступ к DroidFetch запрещён")
            } catch (_: IOException) {
                PluginResult.Failure("Не удалось проверить установленный DroidFetch")
            }
        }
        LaunchedEffect(state) { update() }
        when (val screen = session.screen) {
            is TileScreen.Ready -> RenderTile(
                screen.root, modifier.clipToBounds().padding(6.dp), !session.busy,
            ) { event -> scope.launch { update(event) } }
            else -> Column(
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp),
            ) {
                when (screen) {
                    TileScreen.Loading -> Text("Загрузка DroidFetch…", color = Mocha.Subtext)
                    TileScreen.Empty -> Text("Модуль пока не вывел содержимое.", color = Mocha.Subtext)
                    is TileScreen.Error -> Text(screen.reason, color = Mocha.Red)
                    is TileScreen.Ready -> Unit
                }
                if (screen != TileScreen.Loading) {
                    TextButton(enabled = !session.busy, onClick = { scope.launch { update() } }) {
                        Text("Повторить")
                    }
                }
            }
        }
    }
}

@Composable
internal fun RenderTile(node: TileNode, modifier: Modifier, enabled: Boolean, onEvent: (String) -> Unit) {
    when (node) {
        is TileNode.Text -> Text(
            node.text, modifier, color = Mocha.Text,
            style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis,
        )
        is TileNode.Button -> Button(onClick = { onEvent(node.id) }, enabled = enabled, modifier = modifier) {
            Text(node.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        is TileNode.Progress -> LinearProgressIndicator(progress = { node.fraction }, modifier = modifier.fillMaxWidth())
        is TileNode.Spacer -> Spacer(modifier.height(node.size.dp))
        is TileNode.Group -> {
            val children: @Composable () -> Unit = {
                node.children.forEach { RenderTile(it, Modifier.fillMaxWidth(), enabled, onEvent) }
            }
            when (node.element) {
                TileElement.ROW -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.children.forEach { RenderTile(it, Modifier.weight(1f), enabled, onEvent) }
                }
                TileElement.CARD -> Card(modifier) {
                    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { children() }
                }
                TileElement.SCROLLABLE_COLUMN -> Column(
                    modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp),
                ) { children() }
                else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) { children() }
            }
        }
    }
}
