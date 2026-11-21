package dev.hyprdroid.launcher

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.data.ShellRepository
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.model.ShellPresentation
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose bounds for the tiling surface: every window must share the visible workspace area. */
@RunWith(AndroidJUnit4::class)
class TilingSurfaceBoundsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun await(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    // Added tiles use unique UUIDs; select them by the stable tag prefix instead of an index.
    private fun tileNodes() = compose.onAllNodes(
        SemanticsMatcher("tiling window") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tiling-window-") == true
        },
    )
    private fun awaitTileCount(count: Int) = compose.waitUntil(15_000) { tileNodes().fetchSemanticsNodes().size == count }
    private fun tileBounds(index: Int): Rect = tileNodes()[index].fetchSemanticsNode().boundsInRoot

    private fun seed() = runBlocking {
        dismissBackgroundPixelLauncherAnr()
        val workspace = WorkspaceRepository(context)
        workspace.settings.first().workspaces.forEach { w -> w.items.forEach { workspace.removeItem(w.id, it.id) } }
        workspace.selectWorkspace(0)
        ShellRepository(context).setPresentation(ShellPresentation())
        // Start every run with an empty tiling tree and a closed surface.
        val engines = WorkspaceEngineStore(context)
        engines.save("0", WorkspaceEngine())
        engines.setOpen("0", false)
    }

    private fun openTiling() {
        compose.onNodeWithContentDescription("Меню оболочки").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("Оконный тайл").assertIsEnabled() }.isSuccess }
        compose.onNodeWithText("Оконный тайл").performClick()
        await("tiling-window-tile-1")
        compose.waitForIdle()
    }

    private fun assertVisibleInside(window: Rect, area: Rect, label: String) {
        val tolerance = 1f
        assertTrue("$label must have a positive width", window.width > 0f)
        assertTrue("$label must have a positive height", window.height > 0f)
        assertTrue("$label left ${window.left} is outside ${area.left}", window.left >= area.left - tolerance)
        assertTrue("$label top ${window.top} is outside ${area.top}", window.top >= area.top - tolerance)
        assertTrue("$label right ${window.right} is outside ${area.right}", window.right <= area.right + tolerance)
        assertTrue("$label bottom ${window.bottom} is outside ${area.bottom}", window.bottom <= area.bottom + tolerance)
    }

    @Test fun tiledWindowsShareTheVisibleWorkspaceWithoutScrolling() {
        seed()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await("clean-workspace-0")
            openTiling()
            val canvas = bounds("workspace-canvas-0")
            val surface = bounds("tiling-surface")
            assertVisibleInside(surface, canvas, "tiling surface")
            assertVisibleInside(bounds("tiling-window-tile-1"), surface, "tile-1")

            // Two windows must be visible at the same time inside the workspace.
            compose.onNodeWithTag("tiling-add-window").performClick()
            awaitTileCount(2)
            compose.waitForIdle()
            val first = tileBounds(0)
            val second = tileBounds(1)
            assertVisibleInside(first, surface, "tile-1")
            assertVisibleInside(second, surface, "tile-2")
            assertFalse("Two tiled windows must not overlap", first.overlaps(second))
            assertTrue("Both windows must keep a visible width", first.width > 0f && second.width > 0f)

            // Three or more windows divide the same surface; none of them moves outside the viewport.
            repeat(4) { index ->
                compose.onNodeWithTag("tiling-add-window").performClick()
                awaitTileCount(index + 3)
                compose.waitForIdle()
            }
            val windows = (0 until 6).map { index -> tileBounds(index) }
            windows.forEachIndexed { index, window -> assertVisibleInside(window, surface, "tile-${index + 1}") }
            for (i in windows.indices) {
                for (j in i + 1 until windows.size) {
                    assertFalse("tile-${i + 1} and tile-${j + 1} overlap", windows[i].overlaps(windows[j]))
                }
            }

            // The workspace stays on the same pager page: no scrolling is needed to reach any window.
            compose.onNodeWithTag("workspace-canvas-0").assertExists()
            compose.onNodeWithTag("clean-workspace-0").assertExists()
        } finally { scenario.close() }
    }
}
