package dev.hyprdroid.launcher.data

import dev.hyprdroid.launcher.model.*
import org.json.JSONObject
import java.io.IOException

internal object TopbarCodec {
    fun read(raw: String?): TopbarSettings {
        if (raw == null) return TopbarSettings()
        try {
            require(raw.length <= 100_000)
            val json = JSONObject(raw); require(json.getInt("version") == 1)
            val rules = json.getJSONObject("apps")
            return TopbarSettings(TopbarMode.valueOf(json.getString("mode")), json.getInt("width"), json.getInt("height"),
                json.getInt("opacity"), json.getBoolean("context"), json.getBoolean("hidden"),
                rules.keys().asSequence().associateWith { AppBarRule.valueOf(rules.getString(it)) }).validated()
        } catch (error: Exception) { throw IOException("TopBar: неизвестные или повреждённые настройки; данные не изменены", error) }
    }
    fun write(value: TopbarSettings): String = value.validated().let {
        JSONObject().put("version", 1).put("mode", it.mode.name).put("width", it.widthPercent).put("height", it.heightDp)
            .put("opacity", it.opacityPercent).put("context", it.contextAware).put("hidden", it.manuallyHidden)
            .put("apps", JSONObject().apply { it.appRules.forEach { (key, rule) -> put(key, rule.name) } }).toString()
    }
}
