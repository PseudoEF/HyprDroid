package dev.hyprdroid.launcher.packages

import android.content.Context
import dev.hyprdroid.launcher.BuildConfig

/** Repository trust is supplied by the APK. The installation sample uses its separate key. */
internal object AndroidPackages {
    private const val ASSET_DIRECTORY = "hypr-packages"
    internal const val SAMPLE_KEY_ID = "bundled-sample-v1"
    internal const val SAMPLE_PUBLIC_KEY = "fd0745ace1a4be23d7f618f2921bca733d206771753832df17c5f080d57d69da"

    internal val repositoryTrust = PackageRepositoryTrust(
        keyId = "github-v1",
        publicKey = "6c8604bfb78dcadbd20554d47c686b04d3ea98f6339eaa3a4b54ef5f2839c093",
        minimumRevision = 1,
    )

    private var instance: HyprPackageManager? = null

    @Synchronized
    fun manager(context: Context): HyprPackageManager = instance ?: context.applicationContext.let { app ->
        val root = app.noBackupFilesDir.resolve("hypr-packages")
        val bundled = BundledPackageCatalog { app.assets.open("$ASSET_DIRECTORY/catalog.json") }
        val fixtures = if (BuildConfig.V2_FIXTURE_LUA) BundledPackageCatalog {
            app.assets.open("hypr-packages-v2/catalog.json")
        }.packages() else emptyList()
        val fixtureKey = if (fixtures.isNotEmpty()) mapOf(
            V2_FIXTURE_KEY to app.assets.open("hypr-packages-v2/publisher.hex").use {
                readPackageBytes(it, 65).decodeToString().trim()
            },
        ) else emptyMap()
        val keys = mapOf(SAMPLE_KEY_ID to SAMPLE_PUBLIC_KEY) +
            mapOf(repositoryTrust.keyId to repositoryTrust.publicKey) + fixtureKey
        HyprPackageManager(
            root = root,
            catalog = bundled,
            artifactSource = PackageArtifactSource {
                val directory = if (it.keyId == V2_FIXTURE_KEY) "hypr-packages-v2" else ASSET_DIRECTORY
                app.assets.open("$directory/${it.filename}")
            },
            verifier = HypPackageVerifier(keys),
            repository = SignedPackageRepository(
                repositoryTrust, root.resolve("github-catalog-v1.json"), GitHubPackageDownload,
                (bundled.packages() + fixtures).map { it.manifest.id }.toSet(),
                if (BuildConfig.V2_OFFICIAL_LUA) SignedPackageRepository.CATALOG_V2_URL else SignedPackageRepository.CATALOG_URL,
            ),
            v2Fixtures = fixtures,
            officialV2 = BuildConfig.V2_OFFICIAL_LUA,
        ).also { instance = it }
    }
}


internal const val V2_FIXTURE_KEY = "v2-local-fixture"
internal val v2FixtureIds = setOf("clock-tile", "battery-tile")

internal fun allowsExternalTile(id: String): Boolean =
    BuildConfig.EXTERNAL_LUA && (id == HELLO_TILE_ID || (BuildConfig.V2_FIXTURE_LUA || BuildConfig.V2_OFFICIAL_LUA) && id in v2FixtureIds)
