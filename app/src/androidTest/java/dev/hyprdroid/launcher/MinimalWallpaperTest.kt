package dev.hyprdroid.launcher

import android.content.ContentValues
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.hyprdroid.launcher.data.*
import dev.hyprdroid.launcher.model.*
import dev.hyprdroid.launcher.ui.WorkspaceViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MinimalWallpaperTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val repository get() = ShellRepository(context)
    private fun state() = runBlocking { repository.settings.first() }
    private fun await(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    private fun gone(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
    private fun menu(item: String) {
        compose.onNodeWithContentDescription("Меню оболочки").performClick()
        compose.onNodeWithText(item, substring = false).performScrollTo().performClick()
    }
    private fun clickDocument(selector: BySelector, timeout: Long = 8_000): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeout
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            device.waitForIdle(1_000)
            try {
                val node = device.wait(Until.findObject(selector), 1_000) ?: continue
                node.click()
                return true
            } catch (_: StaleObjectException) { /* DocumentsUI reloads roots as providers finish loading. */ }
        }
        return false
    }
    private fun image(scenario: ActivityScenario<MainActivity>): ImageView? {
        var result: ImageView? = null
        scenario.onActivity { activity ->
            fun inspect(view: android.view.View) {
                if (view is ImageView && view.tag == "animated-wallpaper-view") result = view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
            }
            inspect(activity.window.decorView)
        }
        return result
    }
    @Test fun minimalControlsAndLocalGifPauseRestoreAndRejectInvalidFiles() {
        dismissBackgroundPixelLauncherAnr()
        val previous = state()
        runBlocking { repository.setPresentation(ShellPresentation(DesktopMode.CLEAN)); repository.setWallpaper(null) }
        val name = "hyprdroid-animated-fixture.gif"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HyprDroidTests")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val source = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(source)!!.use { it.write(wallpaperTestGif()) }
        context.contentResolver.update(source, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var imported: Uri? = null
        try {
            await("clean-workspace-0")
            compose.onNodeWithContentDescription("Открыть app drawer").assertDoesNotExist()
            compose.onNodeWithContentDescription("Все приложения").assertDoesNotExist()
            menu("Все приложения"); await("app-drawer"); device.pressBack(); gone("app-drawer")
            compose.onNodeWithContentDescription("Меню оболочки").performClick()
            compose.onNodeWithTag("shell-menu-gesture").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width * .15f, height / 2f), androidx.compose.ui.geometry.Offset(width * .9f, height / 2f), 400) }
            gone("shell-menu")
            menu("Рабочий стол и панели"); await("shell-appearance-panel")
            compose.onNodeWithTag("drawer-topbar-SHOWN").performScrollTo().performClick()
            compose.onNodeWithTag("sidebar-topbar").performScrollTo().performClick()
            compose.onNodeWithTag("appearance-dismiss-gesture").performScrollTo().performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width * .1f, height / 2f), androidx.compose.ui.geometry.Offset(width * .9f, height / 2f), 400)
            }
            gone("shell-appearance-panel")
            assertEquals(ShellButtonVisibility.AUTO, state().presentation.drawerTopbar)
            assertTrue(state().presentation.sidebarTopbar)

            menu("Выбрать изображение…")
            var picked = clickDocument(By.text(name), 4_000)
            if (!picked) {
                assertTrue(clickDocument(By.desc("Show roots")))
                assertTrue(clickDocument(By.text("Images")))
                assertTrue(clickDocument(By.text("HyprDroidTests")))
                // Grid descriptions can target Quick View rather than return a document URI.
                // Select the actual filename row in DocumentsUI's normal list mode.
                assertTrue(clickDocument(By.desc(java.util.regex.Pattern.compile("(?i).*list.*")), 5_000))
                picked = clickDocument(By.text(name))
            }
            if (!picked) {
                captureShellScreen("v072-picker-failure")
                val hierarchy = java.io.ByteArrayOutputStream()
                device.dumpWindowHierarchy(hierarchy)
                android.util.Log.e("WallpaperPickerTest", hierarchy.toString("UTF-8"))
            }
            assertTrue("System file picker must list the local GIF", picked)
            await("animated-wallpaper")
            compose.waitUntil(10_000) { (image(scenario)?.drawable as? AnimatedImageDrawable)?.isRunning == true }
            imported = Uri.parse(requireNotNull(state().wallpaperUri))
            assertTrue(AnimatedWallpaperFiles.isOwned(context, imported!!))
            assertTrue(File(imported!!.path!!).isFile)
            // Original picker document may disappear; the selected private copy remains playable.
            context.contentResolver.delete(source, null, null)
            menu("Все приложения"); await("app-drawer")
            assertFalse((image(scenario)?.drawable as AnimatedImageDrawable).isRunning)
            device.pressBack(); gone("app-drawer")
            compose.waitUntil(8_000) { (image(scenario)?.drawable as? AnimatedImageDrawable)?.isRunning == true }
            val retained = image(scenario)!!.drawable as AnimatedImageDrawable
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(retained.isRunning)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await("animated-wallpaper")
            scenario.recreate(); await("animated-wallpaper")
            compose.waitUntil(10_000) { (image(scenario)?.drawable as? AnimatedImageDrawable)?.isRunning == true }
            assertEquals(imported.toString(), state().wallpaperUri)
            assertTrue(image(scenario)!!.drawable.intrinsicWidth <= AnimatedWallpaperFiles.MAX_DECODE_DIMENSION)
            val focusView = image(scenario)!!
            scenario.onActivity { focusView.onWindowFocusChanged(false) }
            assertFalse((focusView.drawable as AnimatedImageDrawable).isRunning)
            scenario.onActivity { focusView.onWindowFocusChanged(true) }
            compose.waitUntil(8_000) { (image(scenario)?.drawable as? AnimatedImageDrawable)?.isRunning == true }

            menu("Рабочий стол и панели")
            compose.onNodeWithTag("wallpaper-scale-FIT").performScrollTo().performClick()
            compose.onNodeWithTag("wallpaper-motion").performScrollTo().performClick()
            compose.onNodeWithTag("drawer-topbar-SHOWN").performScrollTo().performClick()
            compose.onNodeWithTag("sidebar-topbar").performScrollTo().performClick()
            compose.onNodeWithTag("apply-shell-appearance").performScrollTo().performClick(); gone("shell-appearance-panel")
            assertEquals(WallpaperScale.FIT, state().presentation.wallpaperScale)
            assertFalse(state().presentation.sidebarTopbar)
            compose.onNodeWithTag("sidebar-open").assertDoesNotExist()
            compose.onNodeWithContentDescription("Открыть app drawer").assertExists()
            assertFalse((image(scenario)!!.drawable as AnimatedImageDrawable).isRunning)
            assertEquals(ImageView.ScaleType.FIT_CENTER, image(scenario)!!.scaleType)
            captureShellScreen("v072-gif-wallpaper")
            val invalid = File(context.cacheDir, "bad-wallpaper.gif").apply { writeText("GIF89a") }
            scenario.onActivity { ViewModelProvider(it)[WorkspaceViewModel::class.java].chooseWallpaper(Uri.fromFile(invalid)) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Не удалось открыть фон", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(imported.toString(), state().wallpaperUri)
            invalid.delete()
            scenario.onActivity { ViewModelProvider(it)[WorkspaceViewModel::class.java].useSystemWallpaper() }
            compose.waitUntil(10_000) { state().wallpaperUri == null }
            gone("animated-wallpaper")
            compose.waitUntil(8_000) { !File(imported!!.path!!).exists() }
        } finally {
            scenario.close()
            runBlocking { repository.setPresentation(previous.presentation); repository.setWallpaper(previous.wallpaperUri) }
            imported?.let { AnimatedWallpaperFiles.remove(context, it) }
            context.contentResolver.delete(source, null, null)
        }
    }
}
