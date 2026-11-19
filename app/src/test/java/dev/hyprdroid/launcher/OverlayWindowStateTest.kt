package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.model.PanelScope
import dev.hyprdroid.launcher.model.PanelSettings
import dev.hyprdroid.launcher.model.PanelSide
import dev.hyprdroid.launcher.model.PanelsSettings
import dev.hyprdroid.launcher.overlay.OverlayWindowState
import dev.hyprdroid.launcher.overlay.handlesCover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowStateTest {
    private fun panels(left: PanelScope = PanelScope.HOME, right: PanelScope = PanelScope.APPS) =
        PanelsSettings(left = PanelSettings(scope = left), right = PanelSettings(scope = right))

    @Test fun handlesMustMatchRequestedSidesNotJustTheirCount() {
        val both = panels(left = PanelScope.APPS)
        assertTrue(handlesCover(both, setOf(PanelSide.LEFT, PanelSide.RIGHT)) { true })
        assertFalse("a single handle cannot cover both sides", handlesCover(both, setOf(PanelSide.LEFT)) { true })
        assertFalse("a handle for the wrong side is not reused", handlesCover(panels(), setOf(PanelSide.LEFT)) { true })
        assertFalse("stale extra handles force a rebuild",
            handlesCover(panels(left = PanelScope.HOME), setOf(PanelSide.LEFT, PanelSide.RIGHT)) { true })
        assertTrue(handlesCover(panels(left = PanelScope.HOME, right = PanelScope.HOME), emptySet()) { true })
    }

    @Test fun detachedHandleForcesRebuild() {
        assertFalse(handlesCover(panels(), setOf(PanelSide.RIGHT)) { it == PanelSide.LEFT })
        assertTrue(handlesCover(panels(), setOf(PanelSide.RIGHT)) { it == PanelSide.RIGHT })
    }

    @Test fun geometryChangeInvalidatesTheShownWindow() {
        val shown = OverlayWindowState(PanelSide.LEFT, PanelsSettings(), 1080, 2400, 2.75f)
        assertEquals(shown, shown.copy())
        assertNotEquals("width", shown, shown.copy(width = 1440))
        assertNotEquals("height", shown, shown.copy(height = 1080))
        assertNotEquals("density", shown, shown.copy(density = 2.0f))
        assertNotEquals("panels", shown, shown.copy(panels = panels(left = PanelScope.APPS)))
        assertNotEquals("side", shown, shown.copy(side = null))
    }
}
