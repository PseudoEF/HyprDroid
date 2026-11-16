package dev.hyprdroid.launcher

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.os.Bundle
import android.widget.*
import dev.hyprdroid.launcher.test.R

/** Real RemoteViews provider in a separate test APK/UID. Never included in the launcher APK. */
open class FixtureWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { render(context, manager, it) } }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { render(context, manager, id) }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "dev.hyprdroid.test.WIDGET_CLICK" || intent.action == "dev.hyprdroid.test.WIDGET_PULSE") {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            val prefs = context.getSharedPreferences("widget-fixture", 0)
            prefs.edit().putInt("counter-$id", prefs.getInt("counter-$id", 0) + 1).commit()
            render(context, AppWidgetManager.getInstance(context), id)
        } else super.onReceive(context, intent)
    }
    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val counter = context.getSharedPreferences("widget-fixture", 0).getInt("counter-$id", 0)
        val options = manager.getAppWidgetOptions(id)
        val views = RemoteViews(context.packageName, R.layout.fixture_widget)
        views.setTextViewText(R.id.fixture_text, "Fixture #$id · $counter")
        views.setTextViewText(R.id.fixture_size, "size:${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}")
        views.setOnClickPendingIntent(R.id.fixture_click, PendingIntent.getBroadcast(context, id,
            Intent(context, javaClass).setAction("dev.hyprdroid.test.WIDGET_CLICK").putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        manager.updateAppWidget(id, views)
    }
}
class ConfiguredFixtureWidgetProvider : FixtureWidgetProvider()
class ConfiguredWidgetActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        setResult(RESULT_CANCELED)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ConfiguredWidgetActivity).apply { text = "Fixture widget configuration"; textSize = 22f })
            addView(Button(this@ConfiguredWidgetActivity).apply {
                text = "Save widget"
                setOnClickListener {
                    ConfiguredFixtureWidgetProvider().onUpdate(this@ConfiguredWidgetActivity, AppWidgetManager.getInstance(this@ConfiguredWidgetActivity), intArrayOf(id))
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                }
            })
            addView(Button(this@ConfiguredWidgetActivity).apply { text = "Cancel widget"; setOnClickListener { finish() } })
        })
    }
}
