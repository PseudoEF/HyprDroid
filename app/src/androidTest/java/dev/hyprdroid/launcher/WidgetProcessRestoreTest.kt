package dev.hyprdroid.launcher

import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.widgets.PanelWidgetHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated CI phases, deliberately excluded from the normal UI suite. No product reset path. */
@RunWith(AndroidJUnit4::class)
class WidgetProcessRestoreTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun widgetIdPlacementAndRemoteViewsSurviveARealProcessRestart() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("widgetPhase") ?: error("Explicit widget phase required")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ShellRepository(context)
        val session = PanelWidgetHost.get(context)
        val file = File(context.filesDir, "widget-process-fixture.json")
        if (phase == "seed") {
            assertFalse(file.exists())
            val previous = repository.settings.first().panels
            assertTrue(previous.left.widgets.isEmpty() && previous.right.widgets.isEmpty())
            val id = session.host.allocateAppWidgetId()
            val provider = ComponentName(context.packageName + ".test", FixtureWidgetProvider::class.java.name)
            assertTrue(session.manager.bindAppWidgetIdIfAllowed(id, provider))
            val widget = PanelWidget(id, provider.flattenToString(), "Process restore fixture", 85, 180)
            repository.savePendingWidget(PendingPanelWidget(PanelSide.LEFT, widget, WidgetBindingStage.COMMIT))
            repository.commitPendingWidget(id)
            file.writeText(JSONObject().put("id", id).put("provider", provider.flattenToString()).put("panels", PanelsCodec.encode(previous)).toString())
        } else {
            assertEquals("verify", phase)
            val fixture = JSONObject(file.readText())
            val id = fixture.getInt("id")
            val saved = repository.settings.first().panels.left
            assertEquals(listOf(id), saved.widgets.map { it.appWidgetId })
            assertTrue(saved.modules.contains("ANDROID_WIDGET:$id"))
            assertEquals(85, saved.widgets.single().widthPercent)
            assertEquals(180, saved.widgets.single().heightDp)
            assertTrue(session.host.appWidgetIds.contains(id))
            assertEquals(fixture.getString("provider"), session.info(id)!!.provider.flattenToString())
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Меню оболочки").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Меню оболочки").performClick()
                compose.onNodeWithText("Левая панель", substring = false).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("left-panel").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("sidebar-items").performScrollToIndex(saved.modules.lastIndex)
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue(device.wait(Until.hasObject(By.text("Fixture #$id · 0")), 8_000))
                captureShellScreen("widget-after-process-restart")
            } finally { scenario.close() }
            val original = PanelsCodec.decode(fixture.getString("panels"))
            repository.setPanel(PanelSide.LEFT, original.left)
            repository.setPanel(PanelSide.RIGHT, original.right)
            session.cleanup(repository)
            assertFalse(session.host.appWidgetIds.contains(id))
            file.delete()
        }
    }
}
