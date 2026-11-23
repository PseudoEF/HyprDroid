plugins { id("com.android.library") }

android {
    namespace = "dev.hyprdroid.launcher.external.nativeworker"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 29
        ndk.abiFilters += listOf("arm64-v8a", "x86_64")
    }
    externalNativeBuild.cmake {
        path = file("src/main/native/CMakeLists.txt")
        version = "3.22.1"
    }
}
