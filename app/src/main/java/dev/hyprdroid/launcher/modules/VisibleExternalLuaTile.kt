package dev.hyprdroid.launcher.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.external.ExperimentalExternalLua

@Composable
internal fun VisibleExternalLuaTile() {
    if (!BuildConfig.V2_FIXTURE_LUA || !LocalExternalTileVisible.current) return
    val app = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(app, lifecycle) {
        var release: (() -> Unit)? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (release == null) release = ExperimentalExternalLua.visibleTile(app)
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    release?.invoke()
                    release = null
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            release?.invoke()
        }
    }
}
