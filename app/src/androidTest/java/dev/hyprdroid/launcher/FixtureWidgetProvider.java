package dev.hyprdroid.launcher;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.RemoteViews;
import dev.hyprdroid.launcher.test.R;

/** Separate-process fixture: uses Android/Java, not the target APK's Kotlin runtime. */
public class FixtureWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) render(context, manager, id);
    }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        render(context, manager, id);
    }
    @Override public void onReceive(Context context, Intent intent) {
        if ("dev.hyprdroid.test.WIDGET_CLICK".equals(intent.getAction()) || "dev.hyprdroid.test.WIDGET_PULSE".equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            SharedPreferences prefs = context.getSharedPreferences("widget-fixture", 0);
            prefs.edit().putInt("counter-" + id, prefs.getInt("counter-" + id, 0) + 1).commit();
            render(context, AppWidgetManager.getInstance(context), id);
        } else super.onReceive(context, intent);
    }
    private void render(Context context, AppWidgetManager manager, int id) {
        if (manager.getAppWidgetInfo(id) == null) return;
        int counter = context.getSharedPreferences("widget-fixture", 0).getInt("counter-" + id, 0);
        Bundle options = manager.getAppWidgetOptions(id);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.fixture_widget);
        views.setTextViewText(R.id.fixture_text, "Fixture #" + id + " · " + counter);
        views.setTextViewText(R.id.fixture_size, "size:" + options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) + "x" + options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT));
        views.setOnClickPendingIntent(R.id.fixture_click, PendingIntent.getBroadcast(context, id,
                new Intent(context, getClass()).setAction("dev.hyprdroid.test.WIDGET_CLICK").putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        manager.updateAppWidget(id, views);
    }
}
