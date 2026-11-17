package dev.hyprdroid.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.*;

/** Separate-UID fixture. Java keeps it independent of the target APK's Kotlin runtime. */
public class OverlayTestHostActivity extends Activity {
    private int taps;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        taps = state == null ? 0 : state.getInt("taps", 0);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 32, 32, 32); layout.setBackgroundColor(0xff313244);
        TextView title = new TextView(this);
        title.setText("Overlay test host\nSeparate APK · public Android window"); title.setGravity(Gravity.CENTER); title.setTextColor(0xffcdd6f4); title.setTextSize(22);
        layout.addView(title);
        Button button = new Button(this); button.setAllCaps(false); button.setText("Outside taps: " + taps);
        button.setOnClickListener(v -> button.setText("Outside taps: " + (++taps))); layout.addView(button);
        EditText input = new EditText(this); input.setContentDescription("Keyboard fixture"); input.setHint("Keyboard fixture"); input.setSingleLine(true); layout.addView(input);
        Button fullscreen = new Button(this); fullscreen.setAllCaps(false); fullscreen.setText("Fullscreen fixture");
        fullscreen.setOnClickListener(v -> { if (android.os.Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) getWindow().getInsetsController().hide(WindowInsets.Type.systemBars()); }); layout.addView(fullscreen);
        setContentView(layout);
    }
    @Override public void onSaveInstanceState(Bundle out) { out.putInt("taps", taps); super.onSaveInstanceState(out); }
}
