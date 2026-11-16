package dev.hyprdroid.launcher.widgets

import android.app.Activity
import android.appwidget.*
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.staticCompositionLocalOf
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

val LocalWidgetBinding = staticCompositionLocalOf<WidgetBindingController?> { null }

/** Activity owns only system result delivery. Durable operation and placement use the existing shell store. */
class WidgetBindingController(private val activity: ComponentActivity) {
    companion object {
        private const val CONFIGURE_REQUEST_BASE = 0x4000
        private fun configureCode(id: Int) = CONFIGURE_REQUEST_BASE + id % 0x4000
    }
    private val repository = ShellRepository(activity)
    private val widgets = PanelWidgetHost.get(activity)
    private val failure = MutableStateFlow<String?>(null)
    val error = failure.asStateFlow()
    private val working = MutableStateFlow(false)
    val busy = working.asStateFlow()
    private val bind = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        runOperation {
            val pending = repository.settings.first().pendingWidget ?: return@runOperation
            if (pending.stage != WidgetBindingStage.BIND) return@runOperation
            val returnedId = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending.widget.appWidgetId) ?: pending.widget.appWidgetId
            if (result.resultCode == Activity.RESULT_OK) {
                if (returnedId != pending.widget.appWidgetId) return@runOperation
                advanceAfterBind()
            } else cancelLocked()
        }
    }
    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                activity.lifecycleScope.launch {
                    try { widgets.cleanup(repository) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { failure.value = "Не удалось сверить сохранённые виджеты; данные оставлены без изменений" }
                }
            }
        })
    }
    private fun runOperation(block: suspend () -> Unit) {
        activity.lifecycleScope.launch {
            working.value = true
            try { widgets.operations.withLock { block() }; failure.value = null }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failure.value = "Не удалось завершить добавление. Продолжите или отмените операцию в настройках панелей." }
            finally { working.value = false }
        }
    }
    fun add(side: PanelSide, info: AppWidgetProviderInfo) = runOperation {
        check(info.profile == Process.myUserHandle())
        check(repository.settings.first().pendingWidget == null)
        withContext(NonCancellable) {
            val id = widgets.host.allocateAppWidgetId()
            try {
                val density = activity.resources.displayMetrics.density
                val widget = PanelWidget(id, info.provider.flattenToString(), info.loadLabel(activity.packageManager).take(200),
                    heightDp = (info.minHeight / density).toInt().coerceIn(64, 600))
                repository.savePendingWidget(PendingPanelWidget(side, widget, WidgetBindingStage.ALLOCATED))
            }
            catch (error: Exception) { widgets.delete(id); throw error }
        }
        resumeLocked()
    }
    fun resume() = runOperation { resumeLocked() }
    fun cancel() = runOperation { cancelLocked() }
    private suspend fun cancelLocked() {
        val pending = repository.settings.first().pendingWidget ?: return
        withContext(NonCancellable) {
            repository.cancelPendingWidget(pending.widget.appWidgetId)
            widgets.delete(pending.widget.appWidgetId)
        }
    }
    private fun options() = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) }
    private suspend fun resumeLocked() {
        val pending = repository.settings.first().pendingWidget ?: return
        val id = pending.widget.appWidgetId
        if (pending.stage == WidgetBindingStage.COMMIT) { commit(pending); return }
        if (pending.stage == WidgetBindingStage.CONFIGURE) { configure(pending); return }
        val provider = ComponentName.unflattenFromString(pending.widget.provider) ?: error("Invalid provider")
        val bound = widgets.info(id)?.provider == provider || try { widgets.manager.bindAppWidgetIdIfAllowed(id, Process.myUserHandle(), provider, options()) } catch (_: SecurityException) { false }
        if (bound) advanceAfterBind() else {
            repository.savePendingWidget(pending.copy(stage = WidgetBindingStage.BIND))
            bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, Process.myUserHandle()).putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options()))
        }
    }
    private suspend fun advanceAfterBind() {
        val pending = repository.settings.first().pendingWidget ?: return
        val info = widgets.info(pending.widget.appWidgetId)
        check(info != null && info.provider.flattenToString() == pending.widget.provider && info.profile == Process.myUserHandle())
        val next = pending.copy(stage = WidgetBindingStage.CONFIGURE)
        repository.savePendingWidget(next)
        configure(next)
    }
    private suspend fun configure(pending: PendingPanelWidget) {
        val info = widgets.info(pending.widget.appWidgetId) ?: error("Widget is not bound")
        check(info.provider.flattenToString() == pending.widget.provider)
        if (info.configure == null) commit(pending) else widgets.host.startAppWidgetConfigureActivityForResult(activity, pending.widget.appWidgetId, 0, configureCode(pending.widget.appWidgetId), null)
    }
    private suspend fun commit(pending: PendingPanelWidget) {
        check(widgets.info(pending.widget.appWidgetId)?.provider?.flattenToString() == pending.widget.provider)
        withContext(NonCancellable) {
            repository.savePendingWidget(pending.copy(stage = WidgetBindingStage.COMMIT))
            repository.commitPendingWidget(pending.widget.appWidgetId)
        }
    }
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode !in 0x4000..0x7fff) return false
        runOperation {
            val pending = repository.settings.first().pendingWidget ?: return@runOperation
            if (pending.stage != WidgetBindingStage.CONFIGURE || requestCode != configureCode(pending.widget.appWidgetId)) return@runOperation
            if (resultCode == Activity.RESULT_OK && data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending.widget.appWidgetId)?.let { it != pending.widget.appWidgetId } == true) return@runOperation
            if (resultCode == Activity.RESULT_OK) commit(pending) else cancelLocked()
        }
        return true
    }
}
