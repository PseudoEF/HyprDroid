package dev.hyprdroid.launcher

import android.os.Bundle
import android.util.Log
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

open class MainActivity : ComponentActivity() {
    private lateinit var widgetBinding: WidgetBindingController
    private val route = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.EXTERNAL_LUA) Log.d("HyprLuaTile", "Activity created; restored=${savedInstanceState != null}")
        route.value = intent?.getStringExtra("hyprdroid.route")
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
            val requested by route.collectAsState()
            ActivityContent {
                CompositionLocalProvider(LocalWidgetBinding provides widgetBinding) {
                    WorkspaceScreen(model, requested) { route.value = null }
                }
            }
        }
    }
    @Composable
    protected open fun ActivityContent(content: @Composable () -> Unit) = content()

    override fun onStart() {
        super.onStart()
        if (BuildConfig.EXTERNAL_LUA) Log.d("HyprLuaTile", "Activity started")
    }

    override fun onStop() {
        if (BuildConfig.EXTERNAL_LUA) Log.d("HyprLuaTile", "Activity stopped")
        super.onStop()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (BuildConfig.V2_FIXTURE_LUA && event.actionMasked in
            listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL)
        ) {
            dev.hyprdroid.launcher.external.TileInputTrace.stage(
                dev.hyprdroid.launcher.external.TileInputTrace.current,
                "activity-${event.actionMasked} x=${event.x.toInt()} y=${event.y.toInt()}",
            )
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); route.value = intent.getStringExtra("hyprdroid.route") }
    @Deprecated("Android widget host configuration uses the platform result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgetBinding.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }
}
