package dev.hyprdroid.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hyprdroid.launcher.ui.WorkspaceScreen
import dev.hyprdroid.launcher.ui.WorkspaceViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: WorkspaceViewModel = viewModel()
            WorkspaceScreen(model)
        }
    }
}
