package dev.hyprdroid.launcher.modules

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.hyprdroid.launcher.ui.Mocha
import dev.hyprdroid.launcher.workspaceengine.WindowId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class DroidFetchState(windowId: WindowId) : ModuleState(windowId) {
    var info by mutableStateOf<DroidFetchInfo?>(null)
}

internal object DroidFetchModule : TileModule {
    override val id = "hyprdroid.droidfetch"
    override val title = "DroidFetch"
    override val summary = "ASCII-информация об устройстве"
    override val glyph = ModuleGlyph.COMMAND

    override fun createState(windowId: WindowId): ModuleState =
        DroidFetchState(windowId)

    @Composable
    override fun Content(state: ModuleState, modifier: Modifier) {
        val fetch = state as DroidFetchState
        val context = LocalContext.current.applicationContext
        val lifecycle = LocalLifecycleOwner.current.lifecycle

        LaunchedEffect(fetch, lifecycle, context) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    fetch.info = withContext(Dispatchers.Default) {
                        DroidFetchSystem.read(context)
                    }
                    delay(10_000)
                }
            }
        }

        BoxWithConstraints(modifier) {
            val compact = maxWidth < 320.dp
            val output = fetch.info?.let {
                DroidFetchFormatter.format(it, compact)
            } ?: "Loading DroidFetch..."

            Text(
                text = output,
                color = Mocha.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            )
        }
    }
}
