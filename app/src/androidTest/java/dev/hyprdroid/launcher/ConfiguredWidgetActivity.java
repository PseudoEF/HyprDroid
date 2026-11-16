package dev.hyprdroid.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ConfiguredWidgetActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        setResult(RESULT_CANCELED);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setFitsSystemWindows(true);
        int padding = Math.round(32 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("Fixture widget configuration"); title.setTextSize(22);
        layout.addView(title);
        Button save = new Button(this);
        save.setAllCaps(false);
        save.setText("Save widget");
        save.setOnClickListener(view -> {
            new ConfiguredFixtureWidgetProvider().onUpdate(this, AppWidgetManager.getInstance(this), new int[] { id });
            setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
            finish();
        });
        layout.addView(save);
        Button cancel = new Button(this);
        cancel.setAllCaps(false);
        cancel.setText("Cancel widget"); cancel.setOnClickListener(view -> finish());
        layout.addView(cancel);
        setContentView(layout);
    }
}
