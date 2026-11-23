package dev.hyprdroid.launcher.packages

import android.content.Context

/** Repository trust is supplied by the APK. The installation sample uses its separate key. */
internal object AndroidPackages {
    private const val ASSET_DIRECTORY = "hypr-packages"
    internal const val SAMPLE_KEY_ID = "bundled-sample-v1"
    internal const val SAMPLE_PUBLIC_KEY = "fd0745ace1a4be23d7f618f2921bca733d206771753832df17c5f080d57d69da"

    // Set only after the owner approves the publisher key and bootstrap revision.
    private val repositoryTrust: PackageRepositoryTrust? = null

    private var instance: HyprPackageManager? = null

    @Synchronized
    fun manager(context: Context): HyprPackageManager = instance ?: context.applicationContext.let { app ->
        val root = app.noBackupFilesDir.resolve("hypr-packages")
        val bundled = BundledPackageCatalog { app.assets.open("$ASSET_DIRECTORY/catalog.json") }
        val keys = mapOf(SAMPLE_KEY_ID to SAMPLE_PUBLIC_KEY) +
            repositoryTrust?.let { mapOf(it.keyId to it.publicKey) }.orEmpty()
        HyprPackageManager(
            root = root,
            catalog = bundled,
            artifactSource = PackageArtifactSource { app.assets.open("$ASSET_DIRECTORY/${it.filename}") },
            verifier = HypPackageVerifier(keys),
            repository = SignedPackageRepository(
                repositoryTrust, root.resolve("github-catalog-v1.json"), GitHubPackageDownload,
                bundled.packages().map { it.manifest.id }.toSet(),
            ),
        ).also { instance = it }
    }
}
