package dev.hyprdroid.launcher

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit fixture for the isolated CI emulator; never resets data in production code. */
@RunWith(AndroidJUnit4::class)
class UpgradePersistenceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun verifyInPlaceUpgrade(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("phase")
        assumeTrue("Run explicitly with seed/verify on an isolated emulator", phase == "seed" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = readBuildIdentity(context)
        assertEquals(arguments.getString("expectedCode")!!.toLong(), identity.versionCode)
        assertEquals(arguments.getString("expectedName"), identity.versionName)
        assertEquals(arguments.getString("expectedCommit"), identity.commit)
        assertEquals(arguments.getString("expectedCertificate"), identity.certificateSha256)
        assertTrue(identity.distribution)
        val repository = WorkspaceRepository(context)
        val shell = ShellRepository(context)
        val baseline = File(context.filesDir, "upgrade-fixture.json")
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appearance = WorkspaceAppearance(false, 60, GridDensity.RELAXED, 20)
        val wallpaper = "content://hyprdroid.upgrade-test/image"
        if (phase == "seed") {
            // The runner is fresh. Refuse to seed over any existing test/user data.
            assertFalse(baseline.exists())
            assertTrue(repository.settings.first().workspaces.all { it.items.isEmpty() })
            assertTrue(shell.settings.first().sidebar.isEmpty())
            val apps = LauncherCatalog(context).load().take(3).map { it.componentId }
            assertEquals(3, apps.size)
            apps.forEach { repository.setPinned(0, it, true) }
            repository.placeBefore(0, "app:${apps[2]}", "app:${apps[0]}")
            repository.setPinned(2, apps[1], true)
            repository.setPinned(2, apps[0], true)
            repository.setAppearance(0, appearance)
            repository.selectWorkspace(2)
            shell.setFavorite(apps[0], true)
            shell.setFavorite(apps[2], true)
            shell.setSidebarItem(WorkspaceItem.AppShortcut(apps[1]), true)
            shell.setSidebarItem(WorkspaceItem.AppShortcut(apps[0]), true)
            shell.reorderSidebar("app:${apps[0]}", -1)
            shell.setSidebarItem(WorkspaceItem.AppShortcut(apps[1], "upgrade-shortcut-fixture"), true)
            shell.setPresentation(ShellPresentation(DesktopMode.CLEAN, DockVisibility.AUTO))
            shell.setWallpaper(wallpaper)
            baseline.writeText(JSONObject().put("apps", JSONArray(apps)).put("uid", context.applicationInfo.uid)
                .put("firstInstallTime", packageInfo.firstInstallTime).put("code", identity.versionCode)
                .put("certificate", identity.certificateSha256).toString())
        } else {
            val original = JSONObject(baseline.readText())
            val apps = original.getJSONArray("apps").let { array -> List(array.length()) { array.getString(it) } }
            assertEquals(original.getInt("uid"), context.applicationInfo.uid)
            assertEquals(original.getLong("firstInstallTime"), packageInfo.firstInstallTime)
            assertEquals(original.getString("certificate"), identity.certificateSha256)
            assertTrue(identity.versionCode > original.getLong("code"))
            val saved = repository.settings.first()
            assertEquals(2, saved.selectedWorkspace)
            assertEquals(listOf(apps[2], apps[0], apps[1]).map { "app:$it" }, saved.workspaces[0].items.map { it.id })
            assertTrue(saved.workspaces[1].items.isEmpty())
            assertEquals(listOf(apps[1], apps[0]).map { "app:$it" }, saved.workspaces[2].items.map { it.id })
            assertEquals(appearance, saved.workspaces[0].appearance)
            assertEquals(setOf(apps[0], apps[2]), shell.settings.first().favorites)
            val shortcut = WorkspaceItem.AppShortcut(apps[1], "upgrade-shortcut-fixture")
            assertEquals(listOf(WorkspaceItem.AppShortcut(apps[0]), WorkspaceItem.AppShortcut(apps[1]), shortcut), shell.settings.first().sidebar)
            assertEquals(wallpaper, shell.settings.first().wallpaperUri)
            assertEquals(DesktopMode.CLEAN, shell.settings.first().presentation.desktopMode)
            assertEquals(DockVisibility.AUTO, shell.settings.first().presentation.dockVisibility)
            assertEquals(SidebarModule.entries.toSet(), shell.settings.first().presentation.sidebarModules)
            dismissBackgroundPixelLauncherAnr()
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Меню оболочки").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Меню оболочки").performClick()
                compose.onNodeWithText("О приложении", substring = false).performClick()
                compose.onNodeWithTag("about-version-name").assertTextEquals(identity.versionName)
                compose.onNodeWithTag("about-version-code").assertTextEquals(identity.versionCode.toString())
                compose.onNodeWithTag("about-commit").assertTextEquals(identity.commit)
                compose.waitForIdle()
                captureShellScreen("v052-about")
                compose.onNodeWithTag("about-certificate").performScrollTo().assertTextEquals(identity.certificateSha256)
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
                compose.onNodeWithTag("about-screen").assertDoesNotExist()
            } finally { scenario.close() }
        }
    }
}
