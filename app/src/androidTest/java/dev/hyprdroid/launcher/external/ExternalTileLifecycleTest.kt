package dev.hyprdroid.launcher.external

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hyprdroid.launcher.BuildConfig
import dev.hyprdroid.launcher.MainActivity
import dev.hyprdroid.launcher.data.WorkspaceEngineStore
import dev.hyprdroid.launcher.data.WorkspaceRepository
import dev.hyprdroid.launcher.packages.AndroidPackages
import dev.hyprdroid.launcher.packages.CatalogPackage
import dev.hyprdroid.launcher.packages.HELLO_TILE_ID
import dev.hyprdroid.launcher.packages.HypPackageVerifier
import dev.hyprdroid.launcher.packages.HyprPackageManager
import dev.hyprdroid.launcher.packages.PackageArtifactSource
import dev.hyprdroid.launcher.packages.PackageCatalog
import dev.hyprdroid.launcher.packages.PackageDownload
import dev.hyprdroid.launcher.packages.SignedPackageRepository
import dev.hyprdroid.launcher.workspaceengine.WindowId
import dev.hyprdroid.launcher.workspaceengine.WorkspaceEngine
import dev.hyprdroid.launcher.workspaceengine.WorkspaceWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalTileLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun countsSurviveBackgroundWorkspaceChangeAndActivityRecreation() {
        assumeTrue(BuildConfig.EXTERNAL_LUA)
        val app = instrumentation.targetContext
        val workspace = WorkspaceRepository(app)
        val layout = WorkspaceEngineStore(app)
        runBlocking {
            installSignedFixture()
            for (id in 0..1) {
                layout.save(id.toString(), WorkspaceEngine().apply {
                    addFirstWindow(WorkspaceWindow(WindowId("hello-test-$id"), HELLO_TILE_ID, 0, 0))
                })
                layout.setOpen(id.toString(), true)
            }
            workspace.selectWorkspace(0)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitCount(0)
            click("Increment")
            awaitCount(1)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitCount(1)
            scenario.recreate()
            awaitCount(1)

            selectWorkspace(1)
            awaitCount(0)
            click("Increment")
            awaitCount(1)
            selectWorkspace(0)
            awaitCount(1)
            click("Reset")
            awaitCount(0)
            selectWorkspace(1)
            awaitCount(1)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitCount(1)
            selectWorkspace(0)
            awaitCount(0)
        }
    }

    private fun selectWorkspace(id: Int) {
        compose.onNodeWithContentDescription("Рабочее пространство ${id + 1}").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("tiling-window-hello-test-$id").fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithTag("tiling-window-hello-test-${1 - id}").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun click(label: String) {
        SystemClock.sleep(400)
        compose.onNodeWithText(label).performClick()
    }

    private fun awaitCount(count: Int) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Count: $count").fetchSemanticsNodes().size == 1 }
    }

    private fun installSignedFixture() {
        val root = instrumentation.targetContext.noBackupFilesDir.resolve("hypr-packages")
        val trust = AndroidPackages.repositoryTrust
        val transport = PackageDownload { url, limit ->
            val asset = if (url == SignedPackageRepository.CATALOG_URL) "catalog.json" else "hello-tile-1.0.0.hyp"
            instrumentation.context.assets.open(asset).use { it.readBytes() }.also { require(it.size <= limit) }
        }
        val repo = SignedPackageRepository(trust, root.resolve("github-catalog-v1.json"), transport, emptySet())
        val manager = HyprPackageManager(root, object : PackageCatalog {
            override val description = "signed fixture"
            override fun packages() = emptyList<CatalogPackage>()
        }, PackageArtifactSource { error("Unexpected bundled artifact") }, HypPackageVerifier(mapOf(trust.keyId to trust.publicKey)),
            repository = repo)
        manager.refreshRepository()
        manager.install(HELLO_TILE_ID)
    }
}
