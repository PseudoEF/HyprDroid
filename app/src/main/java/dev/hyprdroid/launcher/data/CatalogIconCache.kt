package dev.hyprdroid.launcher.data

/**
 * Identity of one decoded icon. Density and pixel size belong to the key so a density or display
 * size change never reuses a bitmap that belongs to another configuration. The catalogue only
 * queries `Process.myUserHandle()`, so a profile switch cannot reuse icons either: it either
 * restarts the process or asks the launcher for another user's activities, which never match.
 */
internal data class IconKey(val componentId: String, val densityDpi: Int, val sizePx: Int)

/**
 * A shortcut icon is stale when either the shortcut itself or its package changed. Without a
 * readable package timestamp the icon must not be cached, so a missed callback cannot keep it.
 */
internal fun shortcutIconStamp(shortcutChanged: Long, packageUpdated: Long?): Long? =
    packageUpdated?.let { maxOf(shortcutChanged, it) }

/**
 * In-memory icon store limited by decoded bytes. Values are never recycled: Compose may still draw
 * the previous catalogue while the next one is being built.
 */
internal class CatalogIconCache<T : Any>(private val maxBytes: Long) {
    internal data class Entry<T>(val value: T, val updated: Long, val bytes: Int)

    private val entries = LinkedHashMap<IconKey, Entry<T>>()
    private var bytes = 0

    /** A value is reusable only when the package update timestamp still matches. */
    @Synchronized fun get(key: IconKey, updated: Long): T? = entries[key]?.takeIf { it.updated == updated }?.value

    @Synchronized fun put(key: IconKey, updated: Long, valueBytes: Int, value: T) {
        entries.remove(key)?.let { bytes -= it.bytes }
        entries[key] = Entry(value, updated, valueBytes)
        bytes += valueBytes
    }

    /**
     * Keeps only the keys that the current catalogue still uses, then drops the oldest entries until
     * the byte budget is met. Dropped icons are decoded again on the next load instead of growing
     * the cache without a bound.
     */
    @Synchronized fun retain(keys: Set<IconKey>): Int {
        entries.keys.filterNot { it in keys }.forEach { stale -> entries.remove(stale)?.let { bytes -= it.bytes } }
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            bytes -= iterator.next().value.bytes
            iterator.remove()
        }
        return bytes
    }

    @get:Synchronized val sizeBytes: Int get() = bytes
    @get:Synchronized val size: Int get() = entries.size
}
