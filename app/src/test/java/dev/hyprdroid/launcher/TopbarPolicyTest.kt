package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TopbarPolicyTest {
    private fun TopbarSettings.decide(home: Boolean = false, safe: Boolean = true, ime: Boolean = false,
        app: String? = "example.app", category: ForegroundCategory = ForegroundCategory.NORMAL,
        idle: Boolean = false, reveal: Boolean = false) = visibility(home, safe, ime, app, category, idle, reveal)
    @Test fun optInAndSafetyCannotBeOverriddenByNotificationsOrAppRules() {
        assertEquals(BarVisibility.NONE, TopbarSettings().decide(reveal = true))
        val bar = TopbarSettings(TopbarMode.ALWAYS, appRules = mapOf("example.app" to AppBarRule.SHOW))
        assertEquals(BarVisibility.NONE, bar.decide(home = true)) // The Activity renders Home Waybar, not a duplicate overlay.
        assertEquals(BarVisibility.NONE, bar.decide(safe = false, reveal = true))
        assertEquals(BarVisibility.NONE, bar.decide(ime = true, reveal = true))
    }
    @Test fun categoryIsOnlyAHintAndExplicitRulesTakePrecedence() {
        val bar = TopbarSettings(TopbarMode.ALWAYS)
        assertEquals(BarVisibility.BAR, bar.decide(category = ForegroundCategory.UNKNOWN, app = null))
        assertEquals(BarVisibility.NONE, bar.decide(category = ForegroundCategory.GAME))
        assertEquals(BarVisibility.NONE, bar.decide(category = ForegroundCategory.VIDEO))
        assertEquals(BarVisibility.BAR, bar.copy(appRules = mapOf("example.app" to AppBarRule.SHOW)).decide(category = ForegroundCategory.GAME))
        assertEquals(BarVisibility.NONE, bar.copy(appRules = mapOf("example.app" to AppBarRule.HIDE)).decide())
        assertEquals(BarVisibility.BAR, bar.copy(manuallyHidden = true).decide(reveal = true))
        assertEquals(BarVisibility.BAR, bar.copy(appRules = mapOf("example.app" to AppBarRule.HIDE)).decide(reveal = true))
    }
    @Test fun idleHidesOnlyInAutoAndAlwaysShowDisablesTheTimer() {
        val bar = TopbarSettings(TopbarMode.AUTO_HIDE)
        assertEquals(BarVisibility.REVEAL, bar.decide(idle = true))
        assertEquals(BarVisibility.BAR, bar.decide(idle = true, reveal = true))
        assertEquals(BarVisibility.BAR, bar.copy(appRules = mapOf("example.app" to AppBarRule.SHOW)).decide(idle = true))
        assertEquals(BarVisibility.NONE, bar.copy(manuallyHidden = true).decide(idle = true))
    }
    @Test fun configurationRoundTripsAndUnknownDocumentsAreNotReset() {
        val value = TopbarSettings(TopbarMode.AUTO_HIDE, 72, 64, 70, false, true, mapOf("example.app" to AppBarRule.HIDE))
        assertEquals(value, TopbarCodec.read(TopbarCodec.write(value)))
        assertEquals(TopbarMode.HOME_ONLY, TopbarCodec.read(null).mode)
        listOf("broken", "{}", "{\"version\":9}").forEach {
            try { TopbarCodec.read(it); fail("Do not overwrite unknown data") } catch (_: IOException) { }
        }
    }
}
