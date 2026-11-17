package dev.hyprdroid.launcher.data

import dev.hyprdroid.launcher.model.*
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal object SessionsCodec {
    fun read(raw: String?): WorkspaceSessions {
        if (raw == null) return WorkspaceSessions()
        try {
            require(raw.length <= 8192)
            val json = JSONObject(raw)
            require(json.getInt("version") == 1)
            val rows = json.getJSONArray("apps")
            require(rows.length() == WORKSPACE_COUNT)
            var value = WorkspaceSessions(enabled = json.getBoolean("enabled"))
            repeat(rows.length()) { workspace ->
                val row = rows.getJSONArray(workspace)
                require(row.length() <= 2)
                repeat(row.length()) { index ->
                    val component = row.getString(index)
                    require(component !in value.apps[workspace])
                    value = value.assign(workspace, component, true)
                }
            }
            return value
        } catch (error: Exception) { throw IOException("Не удалось прочитать сессии; данные не изменены", error) }
    }
    fun write(value: WorkspaceSessions): String = JSONObject().put("version", 1)
        .put("enabled", value.enabled).put("apps", JSONArray().apply {
            value.apps.forEach { put(JSONArray(it)) }
        }).toString()
    fun events(raw: String?): List<SessionEvent> {
        if (raw == null) return emptyList()
        try {
            require(raw.length <= 32768)
            val list = JSONArray(raw)
            require(list.length() <= 50)
            return List(list.length()) { index -> list.getJSONObject(index).let {
                SessionEvent(it.getLong("time"), it.getInt("workspace"), it.getString("action"), it.getString("result"))
            } }
        } catch (error: Exception) { throw IOException("Не удалось прочитать журнал сессий", error) }
    }
    fun writeEvents(events: List<SessionEvent>): String = JSONArray().apply {
        events.takeLast(50).forEach { put(JSONObject().put("time", it.time).put("workspace", it.workspace)
            .put("action", it.action).put("result", it.result)) }
    }.toString()
}
