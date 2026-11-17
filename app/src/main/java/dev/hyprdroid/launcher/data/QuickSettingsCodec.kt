package dev.hyprdroid.launcher.data

import dev.hyprdroid.launcher.model.QuickSettingsConfig
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException

internal object QuickSettingsCodec {
    fun read(raw: String?): QuickSettingsConfig {
        if (raw == null) return QuickSettingsConfig()
        try {
            require(raw.length <= 16384)
            val root = JSONObject(raw)
            require(root.getInt("version") == 1)
            val array = root.getJSONArray("topbar")
            return QuickSettingsConfig(List(array.length()) { array.getString(it) }).validated()
        } catch (error: Exception) { throw IOException("Не удалось прочитать Quick Settings; данные сохранены", error) }
    }
    fun write(value: QuickSettingsConfig): String = JSONObject().put("version", 1).put("topbar", JSONArray(value.validated().topbar)).toString()
}
