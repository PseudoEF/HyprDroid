package dev.hyprdroid.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.data.readBuildIdentity

@Composable
fun AboutScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val info = remember(context) { readBuildIdentity(context) }
    Surface(color = Mocha.Base, contentColor = Mocha.Text, modifier = Modifier.fillMaxSize().blockHomeBehind().testTag("about-screen")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().sidebarClosingGesture(onClose = onClose), verticalAlignment = Alignment.CenterVertically) {
                Text("О приложении", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Закрыть сведения о приложении") }
            }
            Text("HyprDroid", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 20.dp))
            SelectionContainer {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BuildValue("Версия · versionName", info.versionName, "about-version-name")
                    BuildValue("Код сборки · versionCode", info.versionCode.toString(), "about-version-code")
                    BuildValue("Идентификатор приложения", info.applicationId, "about-application-id")
                    BuildValue("Коммит", info.commit, "about-commit")
                    BuildValue("Сборка", if (info.distribution) "CI · ${info.runId}" else "Локальная / проверочная · ${info.runId}", "about-channel")
                    BuildValue("Сертификат подписи · SHA-256", info.certificateSha256, "about-certificate")
                }
            }
        }
    }
}

@Composable
private fun BuildValue(title: String, value: String, tag: String) {
    ShellSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Mocha.Subtext)
            Text(value, modifier = Modifier.padding(top = 6.dp).testTag(tag), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
