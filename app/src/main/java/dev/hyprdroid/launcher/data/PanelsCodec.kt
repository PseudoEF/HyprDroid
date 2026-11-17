package dev.hyprdroid.launcher.data

import dev.hyprdroid.launcher.model.*
import org.json.*
import java.io.IOException

/** Separate versioned document inside shell v3; legacy fields remain available for old readers. */
internal object PanelsCodec {
    fun decode(raw: String): PanelsSettings = try {
        val root = JSONObject(raw)
        val version = root.getInt("version")
        if (version !in 1..4) throw IOException("Unsupported panels schema")
        PanelsSettings(read(root.getJSONObject("left"), version), read(root.getJSONObject("right"), version)).also {
            val ids = (it.left.widgets + it.right.widgets).map { widget -> widget.appWidgetId }
            require(ids.distinct().size == ids.size)
        }
    } catch (error: JSONException) { throw IOException("Invalid panels configuration", error) }
      catch (error: IllegalArgumentException) { throw IOException("Invalid panels configuration", error) }
    private fun read(value: JSONObject, version: Int): PanelSettings = PanelSettings(
        scope = PanelScope.valueOf(value.getString("scope")), markerWidthDp = value.getInt("markerWidthDp"),
        touchWidthDp = value.getInt("touchWidthDp"), heightDp = value.getInt("heightDp"),
        positionPercent = value.getInt("positionPercent"), insetDp = value.getInt("insetDp"),
        visibility = MarkerVisibility.valueOf(value.getString("visibility")),
        modules = value.getJSONArray("modules").let { array -> List(array.length()) { array.getString(it) } },
        widgets = if (version == 1) emptyList() else value.getJSONArray("widgets").let { array -> List(array.length()) { readWidget(array.getJSONObject(it)) } },
        widthPercent = if (version < 3) 80 else value.getInt("widthPercent"),
        activation = if (version < 4) PanelActivation.SWIPE_ONLY else PanelActivation.valueOf(value.getString("activation")),
        swipeThresholdDp = if (version < 4) 48 else value.getInt("swipeThresholdDp"),
    ).validated()
    fun encode(value: PanelsSettings): String {
        val ids = (value.left.widgets + value.right.widgets).map { it.appWidgetId }
        require(ids.distinct().size == ids.size)
        return JSONObject().put("version", 4).put("left", write(value.left)).put("right", write(value.right)).toString()
    }
    private fun write(value: PanelSettings): JSONObject = value.validated().let {
        JSONObject().put("scope", it.scope.name).put("markerWidthDp", it.markerWidthDp).put("touchWidthDp", it.touchWidthDp)
            .put("heightDp", it.heightDp).put("positionPercent", it.positionPercent).put("insetDp", it.insetDp)
            .put("visibility", it.visibility.name).put("modules", JSONArray(it.modules))
            .put("widgets", JSONArray().apply { it.widgets.forEach { widget -> put(writeWidget(widget)) } }).put("widthPercent", it.widthPercent)
            .put("activation", it.activation.name).put("swipeThresholdDp", it.swipeThresholdDp)
    }
    private fun readWidget(value: JSONObject) = PanelWidget(value.getInt("id"), value.getString("provider"), value.getString("title"),
        value.getInt("widthPercent"), value.getInt("heightDp")).validated()
    private fun writeWidget(widget: PanelWidget) = widget.validated().let {
        JSONObject().put("id", it.appWidgetId).put("provider", it.provider).put("title", it.title)
            .put("widthPercent", it.widthPercent).put("heightDp", it.heightDp)
    }
    fun encodePending(value: PendingPanelWidget): String = JSONObject().put("version", 1).put("side", value.side.name)
        .put("stage", value.stage.name).put("widget", writeWidget(value.widget)).toString()
    fun decodePending(raw: String): PendingPanelWidget = try {
        val value = JSONObject(raw)
        if (value.getInt("version") != 1) throw IOException("Unsupported widget operation")
        PendingPanelWidget(PanelSide.valueOf(value.getString("side")), readWidget(value.getJSONObject("widget")), WidgetBindingStage.valueOf(value.getString("stage")))
    } catch (error: JSONException) { throw IOException("Invalid widget operation", error) }
      catch (error: IllegalArgumentException) { throw IOException("Invalid widget operation", error) }
}
