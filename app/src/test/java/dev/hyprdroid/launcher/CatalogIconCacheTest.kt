package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.data.CatalogIconCache
import dev.hyprdroid.launcher.data.IconKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogIconCacheTest {
    private val key = IconKey("example.app/.Main", 420, 144)

    @Test fun reusesOnlyWhileUpdateTimeAndConfigurationMatch() {
        val cache = CatalogIconCache<String>(1_000)
        cache.put(key, 7, 100, "icon")
        assertEquals("icon", cache.get(key, 7))
        assertNull("updated package must decode again", cache.get(key, 8))
        assertNull("another density must decode again", cache.get(key.copy(densityDpi = 560), 7))
        assertNull("another pixel size must decode again", cache.get(key.copy(sizePx = 192), 7))
    }

    @Test fun retainDropsEntriesThatLeftTheCatalogue() {
        val cache = CatalogIconCache<String>(1_000)
        val removed = IconKey("gone.app/.Main", 420, 144)
        cache.put(key, 7, 100, "a")
        cache.put(removed, 7, 100, "b")
        assertEquals(100, cache.retain(setOf(key)))
        assertEquals(1, cache.size)
        assertNull(cache.get(removed, 7))
    }

    @Test fun byteBudgetEvictsOldestEntries() {
        val cache = CatalogIconCache<String>(250)
        val second = IconKey("second.app/.Main", 420, 144)
        val third = IconKey("third.app/.Main", 420, 144)
        cache.put(key, 7, 100, "a")
        cache.put(second, 7, 100, "b")
        cache.put(third, 7, 100, "c")
        assertEquals(200, cache.retain(setOf(key, second, third)))
        assertEquals(2, cache.size)
        assertNull("the oldest entry gives way to the budget", cache.get(key, 7))
        assertEquals("c", cache.get(third, 7))
    }

    @Test fun replacingAnEntryUpdatesTheByteCount() {
        val cache = CatalogIconCache<String>(1_000)
        cache.put(key, 7, 100, "old")
        cache.put(key, 7, 40, "new")
        assertEquals(40, cache.sizeBytes)
        assertEquals("new", cache.get(key, 7))
    }
}
