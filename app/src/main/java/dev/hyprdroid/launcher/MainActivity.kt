package dev.hyprdroid.launcher

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val bg = Color(0xFF101116)
private val panel = Color(0xFF20232C)
private val accent = Color(0xFFA6E3A1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcher = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        setContent { HyprHome(launcher) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HyprHome(launcher: LauncherApps) {
    val apps = remember {
        runCatching { launcher.getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != "dev.hyprdroid.launcher" }
            .sortedBy { it.label.toString().lowercase() } }
            .getOrDefault(emptyList())
    }
    val pager = rememberPagerState(pageCount = { 3 })
    var gridLayout by remember { mutableStateOf(true) }
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, surface = panel, background = bg)) {
        Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("HyprDroid", color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("WS ${pager.currentPage + 1}/3", color = Color.LightGray)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tiling: ${if (gridLayout) "Grid" else "List"}", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { gridLayout = !gridLayout }) { Text("Switch layout") }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                Column {
                    Text("Workspace ${page + 1}", color = Color.LightGray)
                    Spacer(Modifier.height(10.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (gridLayout) 2 else 1),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(apps, key = { it.componentName.flattenToString() }) { app ->
                            Column(
                                modifier = Modifier.fillMaxWidth().height(90.dp)
                                    .background(panel, RoundedCornerShape(14.dp))
                                    .clickable {
                                        runCatching { launcher.startMainActivity(app.componentName, Process.myUserHandle(), null, null) }
                                    }.padding(12.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(app.label.toString(), color = Color.White, maxLines = 2)
                                Text(app.componentName.packageName, color = Color.Gray, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Text("Swipe to change workspace • Tap to launch", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
    }
}
