package dev.hyprdroid.launcher.packages

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PublishedHelloEvidenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun legacyInstallRequiresExactSignedRedownloadAndReauthenticatesEverySnapshot() {
        val root = folder.newFolder()
        val catalog = fixture("catalog.json")
        val archive = fixture("hello-tile-1.0.0.hyp")
        val trust = AndroidPackages.repositoryTrust
        val registry = FilePackageRegistry(root)
        val repo = SignedPackageRepository(trust, root.resolve("catalog.json"), PackageDownload { url, limit ->
            val bytes = when (url) {
                SignedPackageRepository.CATALOG_URL -> catalog
                "https://github.com/PseudoEF/HyprDroid-Packages/releases/download/hello-tile-v1.0.0/hello-tile-1.0.0.hyp" -> archive
                else -> throw IOException("Unexpected URL: $url")
            }
            require(bytes.size <= limit)
            bytes
        }, emptySet())
        repo.refresh()
        val entry = repo.packages().single { it.manifest.id == HELLO_TILE_ID }
        val verifier = HypPackageVerifier(mapOf(trust.keyId to trust.publicKey))
        val legacy = root.resolve("objects/pkg-123456").apply { mkdirs() }
        verifier.prepare(entry, archive.inputStream(), legacy)
        registry.publish(mapOf(HELLO_TILE_ID to InstalledPackage(entry.manifest, legacy.name)))
        val manager = HyprPackageManager(root, object : PackageCatalog {
            override val description = "empty"
            override fun packages() = emptyList<CatalogPackage>()
        }, PackageArtifactSource { throw IOException("No bundled package") }, verifier, registry, repo)

        assertThrows(PackageException::class.java) { manager.verifiedHelloTile() }
        assertEquals(listOf(entry.manifest), manager.install(HELLO_TILE_ID))
        val installed = registry.read().getValue(HELLO_TILE_ID)
        assertTrue(installed.hasSignedEvidence)
        val directory = root.resolve("objects").resolve(installed.directory)
        val expected = verifier.verifiedEntrypoint(entry, archive.inputStream())
        val snapshot = manager.verifiedHelloTile()
        assertArrayEquals(expected, snapshot)
        val proof = directory.resolve("catalog-proof.json")
        proof.writeText("{}")
        assertThrows(PackageException::class.java) { manager.verifiedHelloTile() }
        proof.writeBytes(catalog)
        val storedArchive = directory.resolve("archive.hyp")
        storedArchive.appendBytes(byteArrayOf(0))
        assertThrows(PackageException::class.java) { manager.verifiedHelloTile() }
        storedArchive.writeBytes(archive)

        directory.resolve("content/main.lua").appendText("\n-- changed")
        assertThrows(PackageException::class.java) { manager.verifiedHelloTile() }
        assertArrayEquals(expected, snapshot)
        val generation = manager.helloTileGeneration.value
        manager.remove(HELLO_TILE_ID)
        assertTrue(manager.helloTileGeneration.value > generation)
        assertThrows(PackageException::class.java) { manager.verifiedHelloTile() }
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/external/$name")).use { it.readBytes() }
}
