package dev.hyprdroid.launcher.packages

import android.content.Context

/** Only APK assets form the trusted catalogue and artifact source in v0.1. */
internal object AndroidPackages {
    private const val ASSET_DIRECTORY = "hypr-packages"
    internal const val SAMPLE_KEY_ID = "bundled-sample-v1"
    internal const val SAMPLE_PUBLIC_KEY = "fd0745ace1a4be23d7f618f2921bca733d206771753832df17c5f080d57d69da"

    private var instance: HyprPackageManager? = null

    @Synchronized
    fun manager(context: Context): HyprPackageManager = instance ?: context.applicationContext.let { app ->
        HyprPackageManager(
            root = app.noBackupFilesDir.resolve("hypr-packages"),
            catalog = BundledPackageCatalog { app.assets.open("$ASSET_DIRECTORY/catalog.json") },
            artifactSource = PackageArtifactSource { app.assets.open("$ASSET_DIRECTORY/${it.filename}") },
            verifier = HypPackageVerifier(mapOf(SAMPLE_KEY_ID to SAMPLE_PUBLIC_KEY)),
        ).also { instance = it }
    }
}
