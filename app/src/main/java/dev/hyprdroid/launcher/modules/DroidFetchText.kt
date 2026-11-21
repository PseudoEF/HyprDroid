package dev.hyprdroid.launcher.modules

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.hyprdroid.launcher.ui.Mocha

@Composable
internal fun DroidFetchText(
    output: String,
    modifier: Modifier = Modifier,
) {
    val styled = buildAnnotatedString {
        output.lines().forEachIndexed { index, line ->
            if (index > 0) append("\n")

            val separator = line.indexOf(": ")
            val logoWidth = if (
                output.contains(" |  H  D  | ") &&
                line.length >= 14 &&
                index < 7
            ) 14 else 0

            if (logoWidth > 0) {
                withStyle(SpanStyle(color = Mocha.Lavender)) {
                    append(line.take(logoWidth))
                }
            }

            val rest = line.drop(logoWidth)

            if (separator >= logoWidth) {
                val localSeparator = rest.indexOf(": ")
                if (localSeparator >= 0) {
                    withStyle(SpanStyle(color = Mocha.Mauve)) {
                        append(rest.take(localSeparator + 1))
                    }
                    append(rest.drop(localSeparator + 1))
                } else {
                    append(rest)
                }
            } else {
                append(rest)
            }
        }
    }

    Text(
        text = styled,
        modifier = modifier,
        color = Mocha.Text,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        softWrap = false,
    )
}
