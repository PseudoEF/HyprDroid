package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Mocha {
    val Base = Color(0xFF1E1E2E)
    val Mantle = Color(0xFF181825)
    val Surface = Color(0xFF313244)
    val Outline = Color(0xFF585B70)
    val Text = Color(0xFFCDD6F4)
    val Subtext = Color(0xFFBAC2DE)
    val Lavender = Color(0xFFB4BEFE)
    val Mauve = Color(0xFFCBA6F7)
    val Green = Color(0xFFA6E3A1)
    val Red = Color(0xFFF38BA8)
}

@Composable
fun ShellTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Mocha.Mauve, secondary = Mocha.Lavender, tertiary = Mocha.Green,
        background = Mocha.Base, surface = Mocha.Mantle, surfaceVariant = Mocha.Surface,
        onBackground = Mocha.Text, onSurface = Mocha.Text, onSurfaceVariant = Mocha.Subtext,
        outline = Mocha.Outline, error = Mocha.Red,
    )) {
        CompositionLocalProvider(LocalContentColor provides Mocha.Text, content = content)
    }
}

@Composable
fun ShellSurface(modifier: Modifier = Modifier, highlighted: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = Mocha.Mantle.copy(alpha = .92f),
        contentColor = Mocha.Text,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (highlighted) Mocha.Mauve else Mocha.Outline.copy(alpha = .7f)),
        content = content,
    )
}
