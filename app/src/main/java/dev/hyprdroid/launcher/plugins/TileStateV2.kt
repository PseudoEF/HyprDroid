package dev.hyprdroid.launcher.plugins

import org.json.JSONObject

internal sealed interface TileStateValue {
    data class Text(val value: String) : TileStateValue
    data class Number(val value: Double) : TileStateValue
    data class Flag(val value: Boolean) : TileStateValue
}

/** A single bounded record. The owning Core slot carries the package, schema and instance. */
internal object TileStateV2 {
    private val keyPattern = Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,31}")

    fun encode(values: Map<String, TileStateValue>): String {
        require(values.size <= 16)
        val json = JSONObject()
        for ((key, value) in values.toSortedMap()) {
            require(keyPattern.matches(key))
            json.put(key, when (value) {
                is TileStateValue.Text -> value.value.also { require(it.isPluginText(128)) }
                is TileStateValue.Number -> value.value.also { require(it.isFinite()) }
                is TileStateValue.Flag -> value.value
            })
        }
        return json.toString().also { require(it.encodeToByteArray().size <= TILE_STATE_LIMIT) }
    }

    fun decode(raw: String): Map<String, TileStateValue> {
        if (raw.isEmpty()) return emptyMap()
        require(raw.encodeToByteArray().size <= TILE_STATE_LIMIT)
        val json = JSONObject(raw)
        require(json.length() <= 16)
        return json.keys().asSequence().associateWith { key ->
            require(keyPattern.matches(key))
            when (val value = json.get(key)) {
                is String -> TileStateValue.Text(value.also { require(it.isPluginText(128)) })
                is Number -> TileStateValue.Number(value.toDouble().also { require(it.isFinite()) })
                is Boolean -> TileStateValue.Flag(value)
                else -> throw IllegalArgumentException("Unsupported tile state value")
            }
        }.also { require(encode(it) == raw) }
    }
}
