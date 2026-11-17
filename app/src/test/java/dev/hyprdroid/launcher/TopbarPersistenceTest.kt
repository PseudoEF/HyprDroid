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

class TopbarPersistenceTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun topbarRulesAndPanelWidgetsRemainIndependentAcrossRecreation() = runBlocking {
        val file = directory.newFolder().resolve("shell.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bar = TopbarSettings(TopbarMode.AUTO_HIDE, 70, 64, 75, appRules = mapOf("example.video" to AppBarRule.SHOW, "example.game" to AppBarRule.HIDE))
        val widget = PanelWidget(19, "example/.Provider", "Keep", 80, 180)
        val panel = PanelSettings(modules = listOf("CLOCK", widget.moduleId, "QS_WIFI"), widgets = listOf(widget))
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = first, produceFile = { file }))
            repository.setWallpaper("file:///keep.gif"); repository.setPanel(PanelSide.LEFT, panel)
            repository.setQuickSettings(QuickSettingsConfig(listOf("QS_VOLUME", "QS_WIFI")))
            repository.setTopbar { bar }
            assertEquals(panel, repository.settings.first().panels.left)
            repository.reorderPanelModule(PanelSide.LEFT, "QS_WIFI", -1)
            assertEquals(bar, repository.topbar.first())
        } finally { first.coroutineContext[Job]!!.cancelAndJoin() }
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = next, produceFile = { file }))
            assertEquals(bar, repository.topbar.first())
            assertEquals(listOf(widget), repository.settings.first().panels.left.widgets)
            assertEquals("file:///keep.gif", repository.settings.first().wallpaperUri)
            assertEquals(listOf("QS_VOLUME", "QS_WIFI"), repository.quickSettings.first().topbar)
        } finally { next.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun unknownTopbarDocumentIsNotOverwrittenByAnEdit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { directory.newFolder().resolve("shell.preferences_pb") })
            val key = stringPreferencesKey("topbar_overlay_configuration")
            val raw = "{\"version\":99,\"future\":true}"
            store.edit { it[key] = raw }
            try { ShellRepository(store).setTopbar { TopbarSettings() }; fail() } catch (_: IOException) { }
            assertEquals(raw, store.data.first()[key])
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
