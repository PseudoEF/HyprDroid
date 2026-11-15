package dev.hyprdroid.launcher.data

import android.content.Context
import android.content.pm.PackageManager
import dev.hyprdroid.launcher.BuildConfig
import java.security.MessageDigest

data class BuildIdentity(val applicationId: String, val versionName: String, val versionCode: Long,
    val commit: String, val runId: String, val distribution: Boolean, val certificateSha256: String)

/** Read the installed package, not the file name displayed by the user's download manager. */
@Suppress("DEPRECATION")
fun readBuildIdentity(context: Context): BuildIdentity {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val certificate = info.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()
    val digest = certificate?.let { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    return BuildIdentity(context.packageName, info.versionName ?: BuildConfig.VERSION_NAME, info.longVersionCode,
        BuildConfig.BUILD_COMMIT, BuildConfig.BUILD_RUN_ID, BuildConfig.DISTRIBUTION, digest ?: "недоступно")
}
