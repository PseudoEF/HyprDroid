package dev.hyprdroid.launcher.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.hyprdroid.launcher.ui.Mocha
import dev.hyprdroid.launcher.workspaceengine.WindowId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

internal class ClockState(windowId: WindowId) : ModuleState(windowId)

internal object ClockModule : TileModule {
    override val id = "hyprdroid.clock"
    override val title = "Часы"
    override val summary = "Время и дата"
    override val glyph = ModuleGlyph.CLOCK

    override fun createState(windowId: WindowId): ModuleState = ClockState(windowId)

    @Composable
    override fun Content(state: ModuleState, modifier: Modifier) {
        var now by remember { mutableStateOf(LocalDateTime.now()) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(state, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    now = LocalDateTime.now()
                    delay(1_000)
                }
            }
        }
        val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }
        val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()) }
        BoxWithConstraints(modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            val narrow = maxWidth < 150.dp || maxHeight < 78.dp
            val roomy = maxWidth >= 260.dp && maxHeight >= 150.dp
            val showDate = !narrow || maxHeight >= 104.dp
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = now.format(timeFormat),
                    style = when {
                        narrow -> MaterialTheme.typography.titleLarge
                        roomy -> MaterialTheme.typography.displayLarge
                        else -> MaterialTheme.typography.displaySmall
                    },
                    color = Mocha.Text,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("module-clock-${state.windowId.value}"),
                )
                if (showDate) {
                    Text(
                        text = now.format(dateFormat),
                        style = if (roomy) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall,
                        color = Mocha.Subtext,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
