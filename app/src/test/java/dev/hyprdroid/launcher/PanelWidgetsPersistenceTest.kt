package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PanelWidgetsPersistenceTest {
    @get:Rule val directory = TemporaryFolder()
    private val widget = PanelWidget(7, "example.widgets/.Clock", "Clock", 75, 220)
    @Test fun pendingOperationAndMultipleInstancesSurviveRestartAndCommitUsesLatestPanel() = runBlocking {
        val file = directory.newFolder().resolve("shell.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            repository.setFavorite("example.app/.Main", true)
            repository.setWallpaper("content://keep")
            repository.savePendingWidget(PendingPanelWidget(PanelSide.LEFT, widget, WidgetBindingStage.CONFIGURE))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ShellRepository(PreferenceDataStoreFactory.create(scope = next, produceFile = { file }))
            assertEquals(widget, repository.settings.first().pendingWidget!!.widget)
            repository.setPanel(PanelSide.LEFT, PanelsSettings().left.copy(modules = listOf("DATE", "CLOCK", "BATTERY", "future:module")))
            repository.savePendingWidget(repository.settings.first().pendingWidget!!.copy(stage = WidgetBindingStage.COMMIT))
            repository.commitPendingWidget(widget.appWidgetId)
            repository.commitPendingWidget(widget.appWidgetId) // Duplicate result is idempotent.
            assertNull(repository.settings.first().pendingWidget)
            val first = repository.settings.first().panels.left
            val second = widget.copy(appWidgetId = 8)
            repository.savePendingWidget(PendingPanelWidget(PanelSide.LEFT, second, WidgetBindingStage.COMMIT))
            repository.commitPendingWidget(8)
            // Saving an older geometry draft must retain the just-added second instance.
            repository.setPanel(PanelSide.LEFT, first.copy(widgets = listOf(widget.copy(heightDp = 300))), first)
            val saved = repository.settings.first()
            assertEquals(listOf(7, 8), saved.panels.left.widgets.map { it.appWidgetId })
            assertEquals(300, saved.panels.left.widgets.first().heightDp)
            assertEquals(listOf("DATE", "CLOCK", "BATTERY", "future:module", widget.moduleId, second.moduleId), saved.panels.left.modules)
            assertEquals(setOf("example.app/.Main"), saved.favorites)
            assertEquals("content://keep", saved.wallpaperUri)
            repository.reorderPanelModule(PanelSide.LEFT, second.moduleId, -1)
            assertEquals(listOf(second.moduleId, widget.moduleId), repository.settings.first().panels.left.modules.takeLast(2))
            repository.setPanel(PanelSide.LEFT, saved.panels.left.copy(modules = saved.panels.left.modules - widget.moduleId, widgets = listOf(second)))
            assertEquals(listOf(second), repository.settings.first().panels.left.widgets)
        } finally { next.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun versionOneRetainsAllModulesAndGeometryBeforeFirstVersionTwoWrite() {
        val original = PanelsSettings(left = PanelsSettings().left.copy(scope = PanelScope.EVERYWHERE, insetDp = 28, modules = listOf("BATTERY", "future:local", "CLOCK")))
        val raw = JSONObject(PanelsCodec.encode(original)).put("version", 1)
        raw.getJSONObject("left").remove("widgets"); raw.getJSONObject("right").remove("widgets")
        val migrated = PanelsCodec.decode(raw.toString())
        assertEquals(original, migrated)
        assertEquals(3, JSONObject(PanelsCodec.encode(migrated)).getInt("version"))
    }
    @Test fun malformedOrDuplicatedWidgetRecordsAreRejectedAndUnknownModulesRemainData() {
        val panel = PanelsSettings().left.copy(modules = listOf(widget.moduleId, "ANDROID_WIDGET:future"), widgets = listOf(widget))
        val data = PanelsSettings(left = panel)
        assertEquals(data, PanelsCodec.decode(PanelsCodec.encode(data)))
        try { PanelsCodec.encode(data.copy(right = panel)); fail("IDs must be unique across panels") } catch (_: IllegalArgumentException) { }
        try { panel.copy(modules = emptyList()).validated(); fail("A widget must have an ordered slot") } catch (_: IllegalArgumentException) { }
        assertEquals(widget.copy(widthPercent = 25, heightDp = 600), widget.copy(widthPercent = -9, heightDp = 9000).validated())
    }
}
