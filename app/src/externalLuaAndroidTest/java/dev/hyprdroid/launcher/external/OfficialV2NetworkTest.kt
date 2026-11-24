package dev.hyprdroid.launcher.external

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.data.TileStateSlot
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.packages.AndroidPackages
import dev.hyprdroid.launcher.packages.PackageException
import dev.hyprdroid.launcher.plugins.TileExecutionV2
import dev.hyprdroid.launcher.plugins.TileStateValue
import dev.hyprdroid.launcher.plugins.TileSystemSnapshotV2
import dev.hyprdroid.launcher.plugins.TileClockV2
import dev.hyprdroid.launcher.plugins.TileUiCodec
import dev.hyprdroid.launcher.workspaceengine.WindowId
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import dev.hyprdroid.launcher.workspaceengine.WorkspaceWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficialV2NetworkTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = AndroidPackages.manager(app)
    private val store get() = WorkspaceEngineStore(app)
    private val clockWindow = WindowId("verified-migration-clock")
    private val fixture get() = TileStateSlot("clock-tile", 2, clockWindow)
    private val format = mapOf("format24" to TileStateValue.Flag(false))

    @Test
    fun seedLegacy(): Unit = runBlocking {
        assertTrue(app.assets.list("hypr-packages-v2").orEmpty().isNotEmpty())
        manager.install("clock-tile")
        val engine = WorkspaceEngine()
        assertTrue(engine.addFirstWindow(WorkspaceWindow(clockWindow, "clock-tile", 0, 0)))
        store.save("99", engine)
        store.saveTileStateV2(fixture, emptyMap(), format) { true }
    }

    @Test
    fun migrateLegacy(): Unit = runBlocking {
        assertTrue(BuildConfig.V2_OFFICIAL_LUA)
        assertTrue(app.assets.list("hypr-packages-v2").orEmpty().isEmpty())
        assertEquals(2, manager.refreshRepository())
        val official = TileStateSlot("clock-tile", 2, clockWindow, "github-v1")
        assertEquals(emptyMap<String, TileStateValue>(), store.loadTileStateV2(official))
        assertThrows(PackageException::class.java) { manager.install("clock-tile") }
        manager.installFromTest("clock-tile")
        store.migrateFixtureState("clock-tile")
        manager.finishFixtureMigration("clock-tile")
        assertEquals(format, store.loadTileStateV2(official))
        manager.remove("clock-tile")
        assertEquals(format, store.loadTileStateV2(official))
        manager.install("clock-tile")
        val (manifest, bytes) = manager.verifiedV2Tile("clock-tile")
        val snapshot = TileSystemSnapshotV2(TileClockV2("2026-11-24", "14:00:00", "2:00:00 PM"), null, null)
        val frame = requireNotNull(ExperimentalExternalLua.runV2(
            app, bytes, TileExecutionV2(manifest.permissions, store.loadTileStateV2(official), snapshot),
        ))
        assertTrue("2:00:00 PM" in TileUiCodec.encode(frame.tree, 2))
    }

    @Test
    fun seedOfficial(): Unit = runBlocking {
        assertTrue(BuildConfig.V2_OFFICIAL_LUA)
        manager.refreshRepository()
        manager.install("clock-tile")
        val engine = WorkspaceEngine()
        assertTrue(engine.addFirstWindow(WorkspaceWindow(clockWindow, "clock-tile", 0, 0)))
        store.save("99", engine)
        val slot = TileStateSlot("clock-tile", 2, clockWindow, "github-v1")
        store.saveTileStateV2(slot, emptyMap(), format) { true }
    }

    @Test
    fun verifyOfficialUpgrade(): Unit = runBlocking {
        assertTrue(BuildConfig.V2_OFFICIAL_LUA)
        val slot = TileStateSlot("clock-tile", 2, clockWindow, "github-v1")
        val state = store.loadTileStateV2(slot)
        assertEquals(format, state)
        val (manifest, bytes) = manager.verifiedV2Tile("clock-tile")
        val snapshot = TileSystemSnapshotV2(TileClockV2("2026-11-24", "14:00:00", "2:00:00 PM"), null, null)
        val frame = requireNotNull(ExperimentalExternalLua.runV2(app, bytes,
            TileExecutionV2(manifest.permissions, state, snapshot)))
        assertTrue("2:00:00 PM" in TileUiCodec.encode(frame.tree, 2))
    }

    @Test
    fun networkInstall(): Unit = runBlocking {
        assertTrue(BuildConfig.V2_OFFICIAL_LUA)
        assertTrue(app.assets.list("hypr-packages-v2").orEmpty().isEmpty())
        assertTrue(manager.installed().isEmpty())
        assertTrue(manager.available("clock-tile").isEmpty())
        assertEquals(2, manager.refreshRepository())
        assertEquals("clock-tile", manager.available("clock").single().id)
        assertEquals("battery-tile", manager.available("battery").single().id)
        assertEquals(2, manager.info("clock-tile").available?.apiVersion)
        val snapshot = TileSystemSnapshotV2(TileClockV2("2026-11-24", "14:00:00", "2:00:00 PM"), 63, true)
        for ((id, expected) in listOf("clock-tile" to "14:00:00", "battery-tile" to "63%")) {
            assertEquals(id, manager.install(id).single().id)
            val (manifest, bytes) = manager.verifiedV2Tile(id)
            val frame = requireNotNull(ExperimentalExternalLua.runV2(app, bytes,
                TileExecutionV2(manifest.permissions, emptyMap(), snapshot)))
            assertTrue(expected in TileUiCodec.encode(frame.tree, 2))
            manager.remove(id)
            assertThrows(PackageException::class.java) { manager.verifiedV2Tile(id) }
            assertEquals(id, manager.install(id).single().id)
        }
        manager.install("hello-tile")
        assertNotNull(ExperimentalExternalLua.run(app, manager.verifiedHelloTile(),
            dev.hyprdroid.launcher.plugins.TileRequest()))
    }
}
