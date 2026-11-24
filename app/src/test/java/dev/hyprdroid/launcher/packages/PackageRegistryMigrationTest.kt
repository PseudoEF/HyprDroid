package dev.hyprdroid.launcher.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageRegistryMigrationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun approvalRoundTripsThroughStrictRegistryMetadata() {
        val root = folder.newFolder()
        val manifest = PackageManifest("clock-tile", PackageVersion.parse("1.0.0"), 2, "lua",
            "main.lua", setOf("ui.tile", "system.time", "state.local"), emptyMap())
        val record = InstalledPackage(manifest, "pkg-1", hasSignedEvidence = true, fixtureMigrationApproved = true)
        val content = root.resolve("objects/pkg-1/content")
        check(content.mkdirs())
        content.resolve("manifest.json").writeText(manifest.encode().toString())
        content.resolve("main.lua").writeText("return")
        val registry = FilePackageRegistry(root)
        registry.publish(mapOf(manifest.id to record))
        assertEquals(record, registry.read()[manifest.id])
        val index = root.resolve("registry.json")
        index.writeText(index.readText().replace("\"fixtureMigrationApproved\":1", "\"fixtureMigrationApproved\":\"1\""))
        assertThrows(PackageException::class.java) { registry.read() }
    }
}
