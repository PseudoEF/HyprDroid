package dev.hyprdroid.launcher

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceSessionsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun experimentDisabledByDefaultAndAssignmentsPersistWithoutChangingPins() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = WorkspaceRepository(context)
        val before = runBlocking { repository.sessions.first() }
        val pins = runBlocking { repository.settings.first() }
        val apps = LauncherCatalog(context).load().take(2)
        assertEquals(2, apps.size)
        try {
            runBlocking { repository.setSessions { WorkspaceSessions() } }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Меню оболочки").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Меню оболочки").performClick()
                compose.onNodeWithText("Workspace Sessions").performScrollTo().performClick()
                compose.onNodeWithTag("sessions-enabled").assertIsOff().performClick()
                compose.waitUntil(10_000) { runBlocking { repository.sessions.first().enabled } }
                compose.onNodeWithTag("sessions-enabled").performClick()
            }
            runBlocking {
                repository.setSessions { it.assign(0, apps[0].componentId, true).assign(0, apps[1].componentId, true).assign(1, apps[1].componentId, true) }
            }
            val restored = runBlocking { WorkspaceRepository(context).sessions.first() }
            assertFalse(restored.enabled)
            assertEquals(listOf(apps[0].componentId, apps[1].componentId), restored.apps[0])
            assertEquals(listOf(apps[1].componentId), restored.apps[1])
            assertEquals(pins.workspaces, runBlocking { repository.settings.first() }.workspaces)
        } finally { runBlocking { repository.setSessions { before } } }
    }
}
