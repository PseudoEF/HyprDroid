package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class QuickSettingsTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun defaultModulesUseTheSameIdsInEveryPlacementAndUnknownIdsSurvive() {
        val value = QuickSettingsConfig(listOf("QS_VOLUME", "future:tile", "QS_WIFI"))
        assertEquals(value, QuickSettingsCodec.read(QuickSettingsCodec.write(value)))
        assertEquals(8, QuickSettingsCodec.read(null).topbar.size)
        QuickTile.entries.forEach { tile -> assertTrue(PanelModule.entries.any { it.name == tile.moduleId }) }
        listOf("{}", "{\"version\":9,\"topbar\":[]}", "{\"version\":1,\"topbar\":[\"QS_WIFI\",\"QS_WIFI\"]}").forEach {
            try { QuickSettingsCodec.read(it); fail("Must preserve unreadable data") } catch (_: IOException) { }
        }
    }
    @Test fun placementsPersistWithoutChangingWidgetsWallpaperOrOtherPanelModules() = runBlocking {
        val file = directory.newFolder().resolve("shell.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val top = QuickSettingsConfig(listOf("QS_VOLUME", "QS_WIFI"))
        val widget = PanelWidget(19, "example.app/.Widget", "Keep", 80, 180)
        val left = PanelSettings(modules = listOf("CLOCK", "QS_ROTATION", widget.moduleId, "future:module", "QS_BRIGHTNESS"), widgets = listOf(widget))
        try {
            val repo = ShellRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            repo.setWallpaper("content://keep/gif")
            repo.setFavorite("example.app/.Main", true)
            repo.setPanel(PanelSide.LEFT, left)
            val right = repo.settings.first().panels.right
            repo.setQuickSettings(top)
            assertEquals(right, repo.settings.first().panels.right)
            repo.setQuickSettings(QuickSettingsConfig(emptyList()))
            assertEquals(left, repo.settings.first().panels.left)
            repo.setQuickSettings(top)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = ShellRepository(PreferenceDataStoreFactory.create(scope = next, produceFile = { file }))
            assertEquals(top, repo.quickSettings.first())
            assertEquals(left, repo.settings.first().panels.left)
            assertEquals("content://keep/gif", repo.settings.first().wallpaperUri)
            assertEquals(setOf("example.app/.Main"), repo.settings.first().favorites)
        } finally { next.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
