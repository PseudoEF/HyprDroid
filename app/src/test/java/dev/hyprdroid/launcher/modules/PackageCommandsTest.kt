package dev.hyprdroid.launcher.modules

import dev.hyprdroid.launcher.packages.AndroidPackages
import dev.hyprdroid.launcher.packages.BundledPackageCatalog
import dev.hyprdroid.launcher.packages.HypPackageVerifier
import dev.hyprdroid.launcher.packages.HyprPackageManager
import dev.hyprdroid.launcher.packages.PackageArtifactSource
import dev.hyprdroid.launcher.workspaceengine.WindowId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageCommandsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun terminalRunsVerifiedInstallationAndReportsErrors() = runBlocking {
        val assets = sequenceOf(File("src/main/assets/hypr-packages"), File("app/src/main/assets/hypr-packages"))
            .first { it.isDirectory }
        val root = temporaryFolder.newFolder()
        val manager = HyprPackageManager(
            root,
            BundledPackageCatalog { assets.resolve("catalog.json").inputStream() },
            PackageArtifactSource { assets.resolve(it.filename).inputStream() },
            HypPackageVerifier(mapOf(AndroidPackages.SAMPLE_KEY_ID to AndroidPackages.SAMPLE_PUBLIC_KEY)),
        )
        val processor = CommandProcessor(packageCommands = PackageCommands { manager })
        val panel = CommandPanelState(WindowId("package-test"))
        val id = "hyprdroid.droidfetch-sample"

        assertTrue((processor.execute("HYPR \t help") as CommandResult.Output).text.contains("hypr update"))
        assertTrue(processor.execute("hypr list --unknown") is CommandResult.Output)

        panel.updateInput("hypr install $id")
        val events = checkNotNull(panel.submit(processor))
        assertFalse(root.resolve("registry.json").exists())
        panel.play(events)
        assertEquals("Установлено: $id 1.0.0", panel.lines.last().text)

        panel.updateInput("hypr list --installed")
        panel.play(checkNotNull(panel.submit(processor)))
        assertEquals("$id 1.0.0", panel.lines.last().text)

        panel.updateInput("hypr remove unavailable")
        panel.play(checkNotNull(panel.submit(processor)))
        assertTrue(panel.lines.last().text.startsWith("Ошибка hypr:"))
        assertEquals(id, manager.installed().single().id)
    }
}
