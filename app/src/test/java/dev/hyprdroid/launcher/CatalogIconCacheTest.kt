package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.data.CatalogIconCache
import dev.hyprdroid.launcher.data.IconKey
import dev.hyprdroid.launcher.data.IconStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogIconCacheTest {
    private val key = IconKey("example.app/.Main", 420, 144)

    @Test fun reusesOnlyWhileConfigurationAndStampMatch() {
        val cache = CatalogIconCache<String>(1_000)
        cache.put(key, IconStamp(packageUpdated = 7), 100, "icon")
        assertEquals("icon", cache.get(key, IconStamp(packageUpdated = 7)))
        assertNull("updated package must decode again", cache.get(key, IconStamp(packageUpdated = 8)))
        assertNull("another density must decode again", cache.get(key.copy(densityDpi = 560), IconStamp(packageUpdated = 7)))
        assertNull("another pixel size must decode again", cache.get(key.copy(sizePx = 192), IconStamp(packageUpdated = 7)))
    }

    @Test fun retainDropsEntriesThatLeftTheCatalogue() {
        val cache = CatalogIconCache<String>(1_000)
        val removed = IconKey("gone.app/.Main", 420, 144)
        cache.put(key, IconStamp(packageUpdated = 7), 100, "a")
        cache.put(removed, IconStamp(packageUpdated = 7), 100, "b")
        assertEquals(100, cache.retain(setOf(key)))
        assertEquals(1, cache.size)
        assertNull(cache.get(removed, IconStamp(packageUpdated = 7)))
    }

    @Test fun byteBudgetEvictsOldestEntries() {
        val cache = CatalogIconCache<String>(250)
        val second = IconKey("second.app/.Main", 420, 144)
        val third = IconKey("third.app/.Main", 420, 144)
        cache.put(key, IconStamp(packageUpdated = 7), 100, "a")
        cache.put(second, IconStamp(packageUpdated = 7), 100, "b")
        cache.put(third, IconStamp(packageUpdated = 7), 100, "c")
        assertEquals(200, cache.retain(setOf(key, second, third)))
        assertEquals(2, cache.size)
        assertNull("the oldest entry gives way to the budget", cache.get(key, IconStamp(packageUpdated = 7)))
        assertEquals("c", cache.get(third, IconStamp(packageUpdated = 7)))
    }

    @Test fun replacingAnEntryUpdatesTheByteCount() {
        val cache = CatalogIconCache<String>(1_000)
        cache.put(key, IconStamp(packageUpdated = 7), 100, "old")
        cache.put(key, IconStamp(packageUpdated = 7), 40, "new")
        assertEquals(40, cache.sizeBytes)
        assertEquals("new", cache.get(key, IconStamp(packageUpdated = 7)))
    }

    @Test fun shortcutIconsTrackIndependentVersionParts() {
        val shortcut = IconKey("shortcut:com.example/.first", 420, 144)
        val shortcutCache = CatalogIconCache<String>(1_000)
        shortcutCache.put(shortcut, IconStamp(shortcutChanged = 5, packageUpdated = 9), 100, "s")
        assertEquals("s", shortcutCache.get(shortcut, IconStamp(shortcutChanged = 5, packageUpdated = 9)))
        assertNull("package update must invalidate the shortcut icon", shortcutCache.get(shortcut, IconStamp(shortcutChanged = 5, packageUpdated = 10)))
        assertNull("shortcut change must invalidate the icon", shortcutCache.get(shortcut, IconStamp(shortcutChanged = 6, packageUpdated = 9)))
        assertNull("unknown package update time must not be cached", shortcutCache.get(shortcut, IconStamp(shortcutChanged = 5, packageUpdated = null)))
    }
}
