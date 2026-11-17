package dev.hyprdroid.launcher.data

import dev.hyprdroid.launcher.model.MAX_DOCK_APPS
import dev.hyprdroid.launcher.model.validComponent
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Additive order document; the legacy favorites set remains the membership authority. */
internal object DockOrderCodec {
    fun read(raw: String?): List<String> {
        if (raw == null) return emptyList()
        try {
            require(raw.length <= 8192)
            val root = JSONObject(raw)
            require(root.getInt("version") == 1)
            val array = root.getJSONArray("apps")
            val apps = List(array.length()) { array.getString(it) }
            validate(apps)
            return apps
        } catch (error: Exception) { throw IOException("Invalid or unsupported dock order", error) }
    }
    fun write(apps: List<String>): String {
        validate(apps)
        return JSONObject().put("version", 1).put("apps", JSONArray(apps)).toString()
    }
    private fun validate(apps: List<String>) {
        require(apps.size <= MAX_DOCK_APPS && apps.distinct().size == apps.size && apps.all(::validComponent))
    }
}
