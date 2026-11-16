package dev.hyprdroid.launcher

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Independent non-sensitive window in the instrumentation APK, not the product APK. */
class OverlayTestHostActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply {
            text = "Overlay test host\nSeparate APK · public Android window"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(0xffcdd6f4.toInt())
            setBackgroundColor(0xff313244.toInt())
        })
    }
}
