package dev.hyprdroid.launcher.data

import dev.hyprdroid.launcher.model.*
import org.json.*
import java.io.IOException

/** Separate versioned document inside shell v3; legacy fields remain available for old readers. */
internal object PanelsCodec {
    fun decode(raw: String): PanelsSettings = try {
        val root = JSONObject(raw)
        if (root.getInt("version") != 1) throw IOException("Unsupported panels schema")
        PanelsSettings(read(root.getJSONObject("left")), read(root.getJSONObject("right")))
    } catch (error: JSONException) { throw IOException("Invalid panels configuration", error) }
      catch (error: IllegalArgumentException) { throw IOException("Invalid panels configuration", error) }
    private fun read(value: JSONObject): PanelSettings = PanelSettings(
        scope = PanelScope.valueOf(value.getString("scope")), markerWidthDp = value.getInt("markerWidthDp"),
        touchWidthDp = value.getInt("touchWidthDp"), heightDp = value.getInt("heightDp"),
        positionPercent = value.getInt("positionPercent"), insetDp = value.getInt("insetDp"),
        visibility = MarkerVisibility.valueOf(value.getString("visibility")),
        modules = value.getJSONArray("modules").let { array -> List(array.length()) { array.getString(it) } },
    ).validated()
    fun encode(value: PanelsSettings): String = JSONObject().put("version", 1)
        .put("left", write(value.left)).put("right", write(value.right)).toString()
    private fun write(value: PanelSettings): JSONObject = value.validated().let {
        JSONObject().put("scope", it.scope.name).put("markerWidthDp", it.markerWidthDp).put("touchWidthDp", it.touchWidthDp)
            .put("heightDp", it.heightDp).put("positionPercent", it.positionPercent).put("insetDp", it.insetDp)
            .put("visibility", it.visibility.name).put("modules", JSONArray(it.modules))
    }
}
