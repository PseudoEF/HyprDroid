package dev.hyprdroid.launcher.packages

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HyprPackageManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val trustedSigner by lazy { KeyPairGenerator.getInstance("Ed25519").generateKeyPair() }
    private val verifier by lazy {
        HypPackageVerifier(mapOf(TEST_KEY_ID to hex.formatHex(trustedSigner.public.encoded.takeLast(32).toByteArray())))
    }

    @Test
    fun versionComponentsCompareNumericallyAndRejectUnsupportedForms() {
        assertTrue(version("2.0.0") > version("1.99.999"))
        assertTrue(version("1.10.0") > version("1.9.99"))
        assertTrue(version("1.0.10") > version("1.0.9"))
        assertEquals("2147483647.0.0", version("2147483647.0.0").toString())

        for (invalid in listOf("1", "1.2", "01.2.3", "-1.0.0", "1.2.3-rc1", "1.2.3+build", "2147483648.0.0")) {
            assertThrows(invalid, PackageException::class.java) { version(invalid) }
        }
    }

    @Test
    fun bundledCatalogInstallsAuthenticArchiveAndRegistrySurvivesReopen() {
        val root = temporaryFolder.newFolder()
        val assetPath = "src/main/assets/hypr-packages"
        val assets = sequenceOf(File(assetPath), File("app", assetPath)).first { it.isDirectory }
        val catalog = BundledPackageCatalog { assets.resolve("catalog.json").inputStream() }
        val source = PackageArtifactSource { assets.resolve(it.filename).inputStream() }
        val pinnedVerifier = HypPackageVerifier(
            mapOf(AndroidPackages.SAMPLE_KEY_ID to AndroidPackages.SAMPLE_PUBLIC_KEY),
        )
        val first = HyprPackageManager(root, catalog, source, pinnedVerifier)
        val manifest = first.available("DROIDFETCH").single()

        assertEquals("hyprdroid.droidfetch-sample", manifest.id)
        assertTrue(first.installed().isEmpty())
        assertEquals(listOf(manifest), first.install(manifest.id))
        assertTrue(first.install(manifest.id).isEmpty())
        val record = FilePackageRegistry(root).read().getValue(manifest.id)
        assertTrue(root.resolve("objects/${record.directory}/content/${manifest.entry}").isFile)

        val reopened = HyprPackageManager(root, catalog, source, pinnedVerifier)
        assertEquals(PackageDetails(manifest, manifest), reopened.info(manifest.id))
        assertEquals(listOf(manifest), reopened.installed())
        assertEquals(manifest, reopened.remove(manifest.id))
        assertTrue(first.installed().isEmpty())
        assertTrue(root.resolve("objects").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun matchingDigestCannotAuthorizeAnUntrustedSignature() {
        val requested = manifest("sample")
        val wrongSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val artifact = artifact(requested, signer = wrongSigner)
        val root = temporaryFolder.newFolder()
        val manager = manager(root, TestCatalog(artifact))
        val failure = assertThrows(PackageException::class.java) { manager.install(requested.id) }

        assertTrue(failure.message.orEmpty().contains("Ed25519"))
        assertEmptyInstallation(root, manager)
    }

    @Test
    fun signedUnsafeArchivesAreRejectedWithoutLeavingObjects() {
        val requested = manifest("sample")
        val manifestFile = "manifest.json" to requested.encode().toString().encodeToByteArray()
        val script = requested.entry to "-- inert test".encodeToByteArray()
        val cases = mapOf(
            "path traversal" to listOf(manifestFile, script, "../../escape" to byteArrayOf(1)),
            "absolute path" to listOf(manifestFile, script, "/escape" to byteArrayOf(1)),
            "case-fold collision" to listOf(manifestFile, script, requested.entry.uppercase() to byteArrayOf(1)),
            "zip bomb" to listOf(manifestFile, script, "bomb.txt" to ByteArray(512 * 1024)),
            "invalid UTF-8" to listOf("manifest.json" to byteArrayOf(0xc3.toByte(), 0x28), script),
            "malformed JSON" to listOf("manifest.json" to "{broken".encodeToByteArray(), script),
            "mismatched manifest" to listOf(
                "manifest.json" to requested.copy(version = version("2.0.0")).encode().toString().encodeToByteArray(),
                script,
            ),
            "missing entry" to listOf(manifestFile),
            "entry is directory" to listOf(manifestFile, "${requested.entry}/" to byteArrayOf()),
        )
        for ((label, entries) in cases) {
            val root = temporaryFolder.newFolder()
            val manager = manager(root, TestCatalog(artifact(requested, entries)))

            assertThrows(label, PackageException::class.java) { manager.install(requested.id) }
            assertEmptyInstallation(root, manager)
            assertFalse(root.resolve("objects/escape").exists())
        }
    }

    @Test
    fun requiresValidDependencyGraphBeforeOpeningArtifacts() {
        val a = manifest("a", dependencies = mapOf("b" to version("2.0.0")))
        val b = manifest("b", dependencies = mapOf("a" to version("1.0.0")))
        val validVersionB = b.copy(version = version("2.0.0"))
        val root = temporaryFolder.newFolder()
        val catalog = TestCatalog(artifact(a))
        val manager = HyprPackageManager(
            root,
            catalog,
            PackageArtifactSource { error("Planning must finish before opening artifacts") },
            verifier,
        )

        assertThrows(PackageException::class.java) { manager.install(a.id) }
        catalog.replace(artifact(a), artifact(b))
        assertThrows(PackageException::class.java) { manager.install(a.id) }
        catalog.replace(artifact(a), artifact(validVersionB))
        val cycle = assertThrows(PackageException::class.java) { manager.install(a.id) }

        assertTrue(cycle.message.orEmpty().contains("Цикл"))
        assertEmptyInstallation(root, manager)
    }

    @Test
    fun installsDependenciesFirstAndProtectsThemWhileInUse() {
        val prerequisite = manifest("b", "1.1.0")
        val requested = manifest("a", dependencies = mapOf("b" to version("1.0.0")))
        val root = temporaryFolder.newFolder()
        val manager = manager(root, TestCatalog(artifact(requested), artifact(prerequisite)))

        assertEquals(listOf(prerequisite, requested), manager.install(requested.id))
        assertThrows(PackageException::class.java) { manager.remove(prerequisite.id) }
        assertEquals(listOf(requested, prerequisite), manager.installed())
        assertEquals(requested, manager.remove(requested.id))
        assertEquals(prerequisite, manager.remove(prerequisite.id))
        assertEmptyInstallation(root, manager)
    }

    @Test
    fun failedSecondUpdateLeavesBothPriorVersionsAndObjectsIntact() {
        val a = manifest("a")
        val b = manifest("b")
        val root = temporaryFolder.newFolder()
        val catalog = TestCatalog(artifact(a), artifact(b))
        val opened = mutableListOf<String>()
        val manager = HyprPackageManager(root, catalog, PackageArtifactSource {
            opened.add(it.manifest.id)
            catalog.open(it)
        }, verifier)
        manager.install(a.id)
        manager.install(b.id)
        val index = root.resolve("registry.json").readBytes()
        val originalObjects = objectNames(root)
        val nextA = a.copy(version = version("2.0.0"))
        val nextB = b.copy(version = version("2.0.0"))
        catalog.replace(artifact(nextA), artifact(nextB, entries = emptyList()))
        opened.clear()

        assertThrows(PackageException::class.java) { manager.update() }

        assertEquals(listOf(a.id, b.id), opened)
        assertArrayEquals(index, root.resolve("registry.json").readBytes())
        assertEquals(originalObjects, objectNames(root))
        assertEquals(listOf(a, b), manager(root, catalog).installed())

        catalog.replace(artifact(nextA), artifact(nextB))
        assertEquals(listOf(nextA, nextB), manager.update())
        assertEquals(listOf(nextA, nextB), manager(root, catalog).installed())
        assertTrue(objectNames(root).intersect(originalObjects).isEmpty())
    }

    @Test
    fun registryPublishFailureDeletesStagedObjectAndKeepsInstalledVersion() {
        val old = manifest("sample")
        val root = temporaryFolder.newFolder()
        val catalog = TestCatalog(artifact(old))
        val persisted = FilePackageRegistry(root)
        manager(root, catalog).install(old.id)
        val before = persisted.read()
        val originalObjects = objectNames(root)
        catalog.replace(artifact(old.copy(version = version("2.0.0"))))

        val brokenRegistry = object : PackageRegistry by persisted {
            override fun publish(packages: Map<String, InstalledPackage>) {
                assertEquals("2.0.0", packages.getValue(old.id).manifest.version.toString())
                throw PackageException("Injected registry I/O failure")
            }
        }

        val result = assertThrows(PackageException::class.java) { manager(root, catalog, brokenRegistry).update() }

        assertEquals("Injected registry I/O failure", result.message)
        assertEquals(before, persisted.read())
        assertEquals(originalObjects, objectNames(root))
    }

    private fun manager(
        root: File,
        catalog: TestCatalog,
        registry: PackageRegistry = FilePackageRegistry(root),
    ) = HyprPackageManager(root, catalog, PackageArtifactSource(catalog::open), verifier, registry)

    private fun manifest(
        id: String,
        version: String = "1.0.0",
        dependencies: Map<String, PackageVersion> = emptyMap(),
    ) = PackageManifest(id, version(version), 1, "lua", "script.lua", emptySet(), dependencies)

    private fun version(text: String) = PackageVersion.parse(text)

    private fun artifact(
        manifest: PackageManifest,
        entries: List<Pair<String, ByteArray>> = listOf(
            "manifest.json" to manifest.encode().toString().encodeToByteArray(),
            manifest.entry to "-- inert test".encodeToByteArray(),
        ),
        signer: KeyPair = trustedSigner,
    ): TestArtifact {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for ((path, bytes) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val archive = buffer.toByteArray()
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(signer.private)
            update(archive)
        }.sign()
        val digest = MessageDigest.getInstance("SHA-256").digest(archive)
        return TestArtifact(
            CatalogPackage(manifest, hex.formatHex(digest), hex.formatHex(signature), TEST_KEY_ID),
            archive,
        )
    }

    private fun assertEmptyInstallation(root: File, manager: HyprPackageManager) {
        assertTrue(manager.installed().isEmpty())
        assertTrue(objectNames(root).isEmpty())
    }

    private fun objectNames(root: File) = root.resolve("objects").list().orEmpty().toSet()

    private data class TestArtifact(val entry: CatalogPackage, val archive: ByteArray)

    private class TestCatalog(vararg artifacts: TestArtifact) : PackageCatalog {
        private var contents = artifacts.associateBy { it.entry.filename }
        override val description = "JVM fixture"
        override fun packages() = contents.values.map(TestArtifact::entry)

        fun open(entry: CatalogPackage) = contents.getValue(entry.filename).archive.inputStream()

        fun replace(vararg artifacts: TestArtifact) {
            contents = artifacts.associateBy { it.entry.filename }
        }
    }

    private companion object {
        const val TEST_KEY_ID = "unit-test"
        val hex: HexFormat = HexFormat.of()
    }
}
