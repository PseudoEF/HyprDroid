package dev.hyprdroid.launcher

import androidx.datastore.preferences.core.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class MinimalUiSettingsTest {
    @Test fun defaultButtonsFollowModeAndExplicitChoicesOverrideIt() {
        val value = ShellPresentation()
        assertFalse(value.drawerTopbar.visible(DesktopMode.CLEAN))
        assertFalse(value.drawerDock.visible(DesktopMode.CLEAN))
        assertTrue(value.drawerTopbar.visible(DesktopMode.CLASSIC))
        assertTrue(ShellButtonVisibility.SHOWN.visible(DesktopMode.CLEAN))
        assertFalse(ShellButtonVisibility.HIDDEN.visible(DesktopMode.CLASSIC))
    }
    @Test fun additiveSettingsRoundTripAndLegacyValuesRemainAvailable() {
        val preferences = mutablePreferencesOf()
        val value = ShellPresentation(DesktopMode.CLASSIC, DockVisibility.AUTO, setOf(SidebarModule.SYSTEM_STATUS),
            ShellButtonVisibility.HIDDEN, ShellButtonVisibility.SHOWN, false, WallpaperScale.FIT, false)
        ShellPresentationCodec.write(preferences, value)
        assertEquals(value, ShellPresentationCodec.read(preferences))
        preferences.remove(stringPreferencesKey("minimal_ui_configuration"))
        assertEquals(ShellPresentation(DesktopMode.CLASSIC, DockVisibility.AUTO, setOf(SidebarModule.SYSTEM_STATUS)), ShellPresentationCodec.read(preferences))
        preferences[stringPreferencesKey("minimal_ui_configuration")] = """{"version":99}"""
        try { ShellPresentationCodec.read(preferences); fail("Must retain unknown schema") } catch (_: IOException) { }
    }
    @Test fun compressedCopyHasStrictByteLimitWithoutAllocatingFrames() {
        val output = ByteArrayOutputStream()
        copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 3)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        try { copyBounded(ByteArrayInputStream(ByteArray(4)), ByteArrayOutputStream(), 3); fail("Limit must apply before writing") } catch (_: IOException) { }
    }
}
