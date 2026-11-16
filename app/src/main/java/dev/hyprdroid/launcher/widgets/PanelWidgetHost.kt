package dev.hyprdroid.launcher.widgets

import android.appwidget.*
import android.content.*
import android.os.*
import android.view.View
import android.widget.TextView
import android.widget.RemoteViews
import dev.hyprdroid.launcher.data.ShellRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One stable Android host, shared by existing Home and overlay panels, never by a new widget store. */
class PanelWidgetHost private constructor(private val context: Context) {
    companion object {
        const val HOST_ID = 0x485950
        @Volatile private var instance: PanelWidgetHost? = null
        fun get(context: Context): PanelWidgetHost = instance ?: synchronized(this) {
            instance ?: PanelWidgetHost(context.applicationContext).also { instance = it }
        }
    }
    val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
    internal val operations = Mutex()
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()
    private val failure = MutableStateFlow<String?>(null)
    val error = failure.asStateFlow()
    @Volatile private var references = 0
    internal val visibleConsumers get() = references
    private var watching = false
    private val main = Handler(Looper.getMainLooper())
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { changed() }
    }
    private fun changed() { changes.value++ }
    private val platform = object : AppWidgetHost(context, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo): AppWidgetHostView = SafeWidgetView(context)
        override fun onProvidersChanged() { super.onProvidersChanged(); changed() }
        override fun onProviderChanged(id: Int, info: AppWidgetProviderInfo) { super.onProviderChanged(id, info); changed() }
        override fun onAppWidgetRemoved(id: Int) {
            if (Build.VERSION.SDK_INT >= 30) super.onAppWidgetRemoved(id)
            changed()
        }
        fun forgetDetachedViews() { clearViews() }
    }
    val host: AppWidgetHost get() = platform
    fun info(id: Int): AppWidgetProviderInfo? = try { manager.getAppWidgetInfo(id) } catch (_: RuntimeException) { null }
    fun acquire() {
        if (references++ == 0) {
            retry()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(packages, filter)
            watching = true
        }
    }
    fun release() {
        references = (references - 1).coerceAtLeast(0)
        if (references == 0) {
            try { host.stopListening() } catch (_: RuntimeException) { }
            if (watching) { context.unregisterReceiver(packages); watching = false }
            // A new slot may mount during this frame. Never clear its just-created host view.
            main.post { if (references == 0) platform.forgetDetachedViews() }
        }
    }
    fun retry() {
        try { host.startListening(); failure.value = null }
        catch (_: RuntimeException) { failure.value = "Android не разрешил получать обновления виджетов" }
        changed()
    }
    suspend fun cleanup(repository: ShellRepository) = operations.withLock {
        // Read before touching IDs: unknown/corrupt schemas must never cause resource deletion.
        val saved = repository.settings.first()
        val used = (saved.panels.left.widgets + saved.panels.right.widgets).map { it.appWidgetId }.toSet() + listOfNotNull(saved.pendingWidget?.widget?.appWidgetId)
        try { host.appWidgetIds.filter { it !in used }.forEach { host.deleteAppWidgetId(it) } }
        catch (_: RuntimeException) { failure.value = "Не удалось освободить удалённые виджеты; повторим при открытии Home" }
    }
    fun delete(id: Int) { host.deleteAppWidgetId(id); changed() }

    private class SafeWidgetView(context: Context) : AppWidgetHostView(context) {
        override fun getErrorView(): View = TextView(context).apply {
            text = "Не удалось показать Android-виджет. Проверьте поставщика или добавьте виджет заново."
            setTextColor(0xffcdd6f4.toInt()); setPadding(16, 16, 16, 16)
        }
        override fun updateAppWidget(remoteViews: RemoteViews?) {
            try { super.updateAppWidget(remoteViews) }
            catch (_: RuntimeException) { removeAllViews(); addView(getErrorView()) }
        }
    }
}
