package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.model.*
import org.junit.Assert.*
import org.junit.Test

class BrightnessScaleTest {
    @Test fun perceptualScaleIsBoundedMonotonicAndRoundTripsEverySetting() {
        assertEquals(0f, BrightnessScale.toSlider(1), 0.0001f)
        assertEquals(1f, BrightnessScale.toSlider(255), 0.0001f)
        assertEquals(68, BrightnessScale.toSetting(.75f))
        var previous = -1f
        for (raw in 1..255) {
            val slider = BrightnessScale.toSlider(raw)
            assertTrue(slider >= previous)
            assertEquals(raw, BrightnessScale.toSetting(slider))
            previous = slider
        }
        assertEquals(1, BrightnessScale.toSetting(-5f))
        assertEquals(255, BrightnessScale.toSetting(5f))
    }
    @Test fun linearSevenPercentIsNotClaimedAsAndroidPercentageAndAutoIsUnknown() {
        assertEquals(.448f, BrightnessScale.toSlider(18), .002f)
        assertTrue(BrightnessScale.detail(18, false).contains("оценка"))
        assertFalse(BrightnessScale.detail(18, true).contains("%"))
        assertFalse(BrightnessScale.detail(null, false).contains("%"))
        assertFalse(BrightnessScale.detail(128, null).contains("%"))
    }
    @Test fun compactTileEditsKeepOtherModulesAndWidgetBindings() {
        val widget = PanelWidget(19, "example/.Provider", "Keep", 80, 180)
        val old = PanelSettings(modules = listOf("CLOCK", "QS_WIFI", widget.moduleId, "future:module", "QS_VOLUME"), widgets = listOf(widget))
        val next = old.withQuickModules(listOf("QS_VOLUME", "QS_TORCH"))
        assertEquals(listOf("CLOCK", "QS_VOLUME", widget.moduleId, "future:module", "QS_TORCH"), next.modules)
        assertEquals(old.widgets, next.widgets)
        assertEquals(listOf("CLOCK", widget.moduleId, "future:module"), old.withQuickModules(emptyList()).modules)
    }
}
