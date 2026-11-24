import java.util.Properties

plugins { id("com.android.library") }

val runtimeFixtures = providers.gradleProperty("v2FixtureLua").orNull == "true" &&
    providers.gradleProperty("testExperimental").orNull == "true"
require(!runtimeFixtures || providers.environmentVariable("HYPRDROID_DISTRIBUTION").orNull != "true")
val workerNdkVersion = "28.2.13676358"
val localSdk = rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { input ->
    Properties().apply { load(input) }.getProperty("sdk.dir")
}
val sdk = localSdk ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val workerNdkInstalled = sdk?.let { file("$it/ndk/$workerNdkVersion/source.properties").isFile } == true

android {
    namespace = "dev.hyprdroid.launcher.external.nativeworker"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    // Normal app builds can configure this module without an NDK because they never use it.
    if (workerNdkInstalled) {
        ndkVersion = workerNdkVersion
        if (runtimeFixtures) defaultConfig.externalNativeBuild.cmake.arguments += "-DHYPR_RUNTIME_TEST_FIXTURES=ON"
        defaultConfig.ndk.abiFilters += listOf("arm64-v8a", "x86_64")
        externalNativeBuild.cmake {
            path = file("src/main/native/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

tasks.named("preBuild") {
    doFirst {
        check(workerNdkInstalled) { "Install Android NDK $workerNdkVersion to build HyprDroid Experimental" }
    }
}
