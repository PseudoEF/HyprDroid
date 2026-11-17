package dev.hyprdroid.launcher.data

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import dev.hyprdroid.launcher.model.SessionEvent

/** No task enumeration, background launches, delayed starts or privileged window operations. */
class SessionLauncher(private val activity: Activity) {
    fun diagnostics(): String {
        val manager = activity.getSystemService(ActivityManager::class.java)
        return "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\n" +
            "Текущее окно HyprDroid: ${if (activity.isInMultiWindowMode) "multi-window" else "полный экран"}\n" +
            "Мало RAM: ${manager.isLowRamDevice}\n" +
            "LAUNCH_ADJACENT: публичный API доступен; поддержка пары устройством и приложениями не подтверждена.\n" +
            "Чужие задачи и их содержимое недоступны. Возврат и сохранение состояния определяют Android и само приложение."
    }

    fun launch(workspace: Int, components: List<String>, pair: Boolean): SessionEvent {
        val result = try {
            require(components.size == if (pair) 2 else 1)
            val intents = components.mapIndexed { index, id ->
                val component = requireNotNull(ComponentName.unflattenFromString(id))
                val launcher = activity.getSystemService(android.content.pm.LauncherApps::class.java)
                require(launcher.isActivityEnabled(component, android.os.Process.myUserHandle()))
                Intent.makeMainActivity(component).apply {
                    // Launcher semantics: prefer an existing task; never request a fresh multiple task or clear data.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    if (pair && index == 1) addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                }
            }
            if (pair) activity.startActivities(intents.toTypedArray()) else activity.startActivity(intents.single())
            if (pair) "Запрос пары отправлен; Split Screen и повторное использование задач не подтверждены. Если пара не появилась, используйте системные Недавние."
            else "Запрос запуска/возврата отправлен; повторное использование задачи не подтверждено."
        } catch (_: SecurityException) { "Android запретил запуск. Используйте системный Split Screen." }
          catch (_: android.content.ActivityNotFoundException) { "Activity недоступна: приложение могло быть удалено или отключено." }
          catch (_: IllegalArgumentException) { "Назначение недоступно или неполно; выберите установленные приложения." }
        return SessionEvent(System.currentTimeMillis(), workspace, if (pair) "pair_request" else "launch_or_return", result)
    }
}
