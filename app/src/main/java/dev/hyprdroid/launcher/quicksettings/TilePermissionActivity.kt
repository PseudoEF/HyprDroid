package dev.hyprdroid.launcher.quicksettings

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hyprdroid.launcher.ui.ShellTheme

/** Explicit user entry works from both Home and overlay without an ActivityResult owner in the service. */
class TilePermissionActivity : ComponentActivity() {
    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetooth = intent.getBooleanExtra("bluetooth", false)
        val notifications = intent.getBooleanExtra("notifications", false)
        if (notifications && Build.VERSION.SDK_INT < 33) { finish(); return }
        if (bluetooth && Build.VERSION.SDK_INT < 31) { finish(); return }
        setContent { ShellTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(if (notifications) "Восстановление TopBar" else if (bluetooth) "Состояние Bluetooth" else "Доступ к фонарику", style = MaterialTheme.typography.titleLarge)
                    Text(if (notifications) "Уведомление позволяет восстановить скрытый TopBar и отключить overlay. Без доступа можно вернуться через Home." else if (bluetooth) "Разрешение «Устройства поблизости» нужно только для чтения состояния Bluetooth. Можно отказаться и пользоваться системными настройками. Поиск устройств не выполняется."
                        else "Android требует разрешение камеры для управления вспышкой. HyprDroid не открывает камеру и не снимает фото или видео. После разрешения нажмите плитку ещё раз.")
                    Button(onClick = { request.launch(if (notifications) Manifest.permission.POST_NOTIFICATIONS else if (bluetooth) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.CAMERA) }) { Text("Продолжить") }
                    TextButton(onClick = { finish() }) { Text("Отмена") }
                }
            }
        } }
    }
}
