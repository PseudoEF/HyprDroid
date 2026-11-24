package dev.hyprdroid.launcher.developer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.MainActivity
import dev.hyprdroid.launcher.R

class DeveloperActivity : MainActivity() {
    @Composable
    override fun ActivityContent(content: @Composable () -> Unit) {
        Column {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF27213C),
                contentColor = Color(0xFFF5CB72),
            ) {
                Text(stringResource(R.string.developer_edition_banner), Modifier.padding(8.dp))
            }
            DeveloperTools(content)
        }
    }
}
