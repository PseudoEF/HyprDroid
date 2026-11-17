package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.data.PanelsCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PanelSizingTest {
    private val widget = PanelWidget(9, "example.widgets/.Clock", "Clock", 50, 180)
    @Test fun gesturesMigrateToSwipeOnlyAndIndependentChoicesRoundTrip() {
        val source = PanelsSettings(left = PanelSettings(activation = PanelActivation.TAP_SWIPE, swipeThresholdDp = 72))
        assertEquals(source, PanelsCodec.decode(PanelsCodec.encode(source)))
        val old = JSONObject(PanelsCodec.encode(source)).put("version", 3)
        val restored = PanelsCodec.decode(old.toString())
        assertEquals(PanelActivation.SWIPE_ONLY, restored.left.activation)
        assertEquals(48, restored.left.swipeThresholdDp)
        assertEquals(source.left.modules, restored.left.modules)
        assertEquals(24, PanelSettings(swipeThresholdDp = 0).validated().swipeThresholdDp)
        assertEquals(96, PanelSettings(swipeThresholdDp = 200).validated().swipeThresholdDp)
    }
    @Test fun oldPanelDocumentKeepsWidgetsAndGetsIndependentDefaultWidth() {
        val source = PanelsSettings(left = PanelSettings(modules = listOf(widget.moduleId, "future"), widgets = listOf(widget), widthPercent = 95))
        val old = JSONObject(PanelsCodec.encode(source)).put("version", 2)
        old.getJSONObject("left").remove("widthPercent"); old.getJSONObject("right").remove("widthPercent")
        val restored = PanelsCodec.decode(old.toString())
        assertEquals(80, restored.left.widthPercent)
        assertEquals(listOf(widget), restored.left.widgets)
        assertEquals(source.left.modules, restored.left.modules)
        val next = restored.copy(left = restored.left.copy(widthPercent = 60))
        assertEquals(next, PanelsCodec.decode(PanelsCodec.encode(next)))
        assertEquals(50, next.left.widgets.single().widthPercent)
    }
    @Test fun fittingRespectsFixedAxesProviderBoundsAndNarrowPanelsWithoutChangingPreferences() {
        val resizable = PanelWidgetLimits(120f, 100f, 220f, 250f)
        assertEquals(PanelWidgetSize(150f, 180f, false), fitPanelWidget(widget, 300f, resizable))
        assertEquals(PanelWidgetSize(80f, 180f, true), fitPanelWidget(widget, 80f, resizable))
        assertEquals(PanelWidgetSize(220f, 250f, false), fitPanelWidget(widget.copy(widthPercent = 100, heightDp = 400), 300f, resizable))
        assertEquals(PanelWidgetSize(120f, 100f, false), fitPanelWidget(widget, 300f, resizable.copy(horizontal = false, vertical = false)))
        assertEquals(50, widget.widthPercent)
        assertEquals(50, PanelSettings(widthPercent = -1).validated().widthPercent)
        assertEquals(100, PanelSettings(widthPercent = 500).validated().widthPercent)
    }
}
