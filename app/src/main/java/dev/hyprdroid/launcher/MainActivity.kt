package dev.hyprdroid.launcher

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import dev.hyprdroid.launcher.widgets.LocalWidgetBinding
import dev.hyprdroid.launcher.widgets.WidgetBindingController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hyprdroid.launcher.ui.WorkspaceScreen
import dev.hyprdroid.launcher.ui.WorkspaceViewModel

class MainActivity : ComponentActivity() {
    private lateinit var widgetBinding: WidgetBindingController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetBinding = WidgetBindingController(this)
        dev.hyprdroid.launcher.overlay.OverlaySession.attach(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setContent {
            val model: WorkspaceViewModel = viewModel()
            CompositionLocalProvider(LocalWidgetBinding provides widgetBinding) { WorkspaceScreen(model) }
        }
    }
    @Deprecated("Android widget host configuration uses the platform result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgetBinding.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }
}
