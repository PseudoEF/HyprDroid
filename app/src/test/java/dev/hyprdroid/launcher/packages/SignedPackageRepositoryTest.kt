package dev.hyprdroid.launcher.packages

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SignedPackageRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val key = signer.public.encoded.takeLast(32).toByteArray().hex()
    private val keyId = "fixture-v1"
    private val trust = PackageRepositoryTrust(keyId, key, 1)
    private val requests = mutableMapOf<String, ByteArray>()
    private val download = PackageDownload { url, limit ->
        val bytes = requests[url] ?: throw IOException("offline")
        if (bytes.size > limit) throw PackageException("oversized")
        bytes
    }

    @Test
    fun signedRemotePackageUsesExistingInstallerAndCacheSurvivesOffline() {
        val root = folder.newFolder()
        val repo = repository(root)
        val first = artifact("publisher.hello-tile", "1.0.0")
        offer(1, first)
        val manager = manager(root, repo)

        assertEquals(1, manager.refreshRepository())
        assertEquals(listOf(first.manifest), manager.available("hello"))
        assertEquals(listOf(first.manifest), manager.install("hello-tile"))
        requests.clear()

        val reopened = manager(root, repository(root))
        assertEquals(listOf(first.manifest), reopened.available())
        assertEquals(first.manifest, reopened.info("hello-tile").installed)
        assertThrows(IOException::class.java) { reopened.refreshRepository() }
        assertEquals(first.manifest, reopened.remove("hello-tile"))
    }

    @Test
    fun failedArtifactUpdatePreservesCurrentFilesAndVersion() {
        val root = folder.newFolder()
        val manager = manager(root, repository(root))
        val first = artifact("hello-tile", "1.0.0")
        offer(1, first)
        manager.refreshRepository()
        manager.install("hello-tile")

        val second = artifact("hello-tile", "2.0.0")
        offer(2, second)
        requests[second.url] = first.bytes
        manager.refreshRepository()
        assertThrows(PackageException::class.java) { manager.update() }
        assertEquals(listOf(first.manifest), manager.installed())

        val corrupt = artifact("hello-tile", "3.0.0", bytes = byteArrayOf(1, 2, 3))
        offer(3, corrupt)
        manager.refreshRepository()
        assertThrows(IOException::class.java) { manager.update() }
        assertEquals(listOf(first.manifest), manager.installed())
        assertTrue(root.resolve("objects").listFiles().orEmpty().size == 1)
    }

    @Test
    fun rejectsTamperedUrlEvenWithValidSignatureAndRejectsUnsignedEdits() {
        val root = folder.newFolder()
        val repo = repository(root)
        val item = artifact("hello-tile", "1.0.0")
        val payload = payload(1, item.record().put("url", "https://example.com/module.hyp"))
        requests[SignedPackageRepository.CATALOG_URL] = envelope(payload)
        assertThrows(PackageException::class.java) { repo.refresh() }
        offer(1, item)
        val signed = JSONObject(requests.getValue(SignedPackageRepository.CATALOG_URL).decodeToString())
        signed.put("payload", Base64.getEncoder().encodeToString(payload))
        requests[SignedPackageRepository.CATALOG_URL] = signed.toString().encodeToByteArray()
        assertThrows(PackageException::class.java) { repo.refresh() }
        assertTrue(repo.packages().isEmpty())
    }

    @Test
    fun rollbackAndSameVersionReplacementFailIncludingAfterReopening() {
        val root = folder.newFolder()
        val item = artifact("hello-tile", "2.0.0")
        offer(4, item)
        repository(root).refresh()
        val reopened = repository(root)
        offer(4, artifact("hello-tile", "3.0.0"))
        assertThrows(PackageException::class.java) { reopened.refresh() }
        offer(3, item)
        assertThrows(PackageException::class.java) { reopened.refresh() }
        offer(5, artifact("hello-tile", "1.0.0"))
        assertThrows(PackageException::class.java) { reopened.refresh() }
        offer(5, artifact("hello-tile", "2.0.0", bytes = byteArrayOf(1)))
        assertThrows(PackageException::class.java) { reopened.refresh() }
        requests[SignedPackageRepository.CATALOG_URL] = envelope(payload(5, item.record().put("status", "revoked")))
        assertEquals(5, reopened.refresh())
        assertTrue(reopened.packages().isEmpty())
        offer(6, item)
        assertThrows(PackageException::class.java) { reopened.refresh() }
    }

    @Test
    fun absentPinAndConflictingIdsFailClosed() {
        val item = artifact(DROIDFETCH_SAMPLE_ID, "1.0.0")
        offer(1, item)
        assertThrows(PackageException::class.java) { repository(folder.newFolder()).refresh() }
        val disabled = SignedPackageRepository(null, folder.newFile(), download, emptySet())
        assertThrows(PackageException::class.java) { disabled.refresh() }
        assertTrue(disabled.packages().isEmpty())
    }

    private fun repository(root: File) =
        SignedPackageRepository(trust, root.resolve("catalog.json"), download, setOf(DROIDFETCH_SAMPLE_ID))

    private fun manager(root: File, repo: SignedPackageRepository) = HyprPackageManager(
        root,
        object : PackageCatalog {
            override val description = "fixture"
            override fun packages() = emptyList<CatalogPackage>()
        },
        PackageArtifactSource { error("Remote artifacts must go through the repository") },
        HypPackageVerifier(mapOf(keyId to key)),
        repository = repo,
    )

    private fun offer(revision: Int, item: Artifact) {
        requests[SignedPackageRepository.CATALOG_URL] = envelope(payload(revision, item.record()))
        requests[item.url] = item.bytes
    }

    private fun payload(revision: Int, entry: JSONObject) = JSONObject()
        .put("schemaVersion", 1).put("repository", SignedPackageRepository.REPOSITORY)
        .put("revision", revision).put("packages", JSONArray(listOf(entry))).toString().encodeToByteArray()

    private fun envelope(payload: ByteArray) = JSONObject()
        .put("keyId", keyId).put("payload", Base64.getEncoder().encodeToString(payload))
        .put("signature", sign(SignedPackageRepository.DOMAIN.encodeToByteArray() + payload))
        .toString().encodeToByteArray()

    private fun artifact(id: String, version: String, bytes: ByteArray? = null): Artifact {
        val manifest = PackageManifest(id, PackageVersion.parse(version), 1, "lua", "main.lua", setOf("ui.tile"), emptyMap())
        val archive = bytes ?: ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                for ((name, text) in listOf("manifest.json" to manifest.encode().toString(), "main.lua" to "-- fixture")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.encodeToByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        return Artifact(manifest, "$id-v$version/$id-$version.hyp", archive)
    }

    private inner class Artifact(val manifest: PackageManifest, path: String, val bytes: ByteArray) {
        val url = "${SignedPackageRepository.RELEASE_ROOT}/$path"
        fun record() = JSONObject().put("manifest", manifest.encode()).put("url", url).put("status", "active")
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).hex()).put("signature", sign(bytes))
    }

    private fun sign(bytes: ByteArray) = Signature.getInstance("Ed25519").run {
        initSign(signer.private)
        update(bytes)
        sign().hex()
    }

    private fun ByteArray.hex() = HexFormat.of().formatHex(this)
}
