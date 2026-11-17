plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val distribution = providers.environmentVariable("HYPRDROID_DISTRIBUTION").orNull == "true"
// Local builds stay ahead of v0.6 (8); distributed codes must be allocated by CI.
val buildCode = requireNotNull(providers.gradleProperty("buildVersionCode").orElse("16").get().toLongOrNull()) { "Invalid versionCode" }
require(buildCode in 1..2_100_000_000L) { "buildVersionCode must be within Android's supported range" }
val buildName = providers.gradleProperty("buildVersionName").orElse("0.8.0").get()
require(buildName.matches(Regex("[0-9A-Za-z.+_-]{1,100}")))
val buildCommit = providers.environmentVariable("HYPRDROID_BUILD_COMMIT").orElse(providers.provider {
    runCatching { providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim() }.getOrDefault("unknown")
}).get()
require(buildCommit == "unknown" || buildCommit.matches(Regex("[0-9a-f]{40,64}")))
val buildRun = providers.environmentVariable("GITHUB_RUN_ID").orElse("local").get()
require(buildRun == "local" || buildRun.matches(Regex("[0-9]+")))
if (distribution) {
    require(providers.gradleProperty("buildVersionCode").isPresent && buildCommit != "unknown") { "Distribution requires allocated versionCode and commit" }
}
android {
    namespace = "dev.hyprdroid.launcher"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.hyprdroid.launcher"
        minSdk = 29
        targetSdk = 35
        versionCode = buildCode.toInt()
        versionName = buildName
        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        buildConfigField("String", "BUILD_RUN_ID", "\"$buildRun\"")
        buildConfigField("boolean", "DISTRIBUTION", distribution.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    if (distribution) {
        signingConfigs.getByName("debug") {
            storeFile = file(requireNotNull(System.getenv("HYPRDROID_KEYSTORE_FILE")) { "Persistent signing key is required" })
            require(storeFile!!.isFile) { "Persistent signing key is missing; refusing ephemeral fallback" }
            storeType = "PKCS12"
            storePassword = requireNotNull(System.getenv("HYPRDROID_KEYSTORE_PASSWORD"))
            keyAlias = "hyprdroid-ci-debug"
            keyPassword = storePassword
        }
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/buildInfoAssets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
val generateBuildIdentity = tasks.register("generateBuildIdentity") {
    val output = layout.buildDirectory.file("generated/buildInfoAssets/build-info.json")
    inputs.properties(mapOf("versionCode" to buildCode, "versionName" to buildName, "commit" to buildCommit, "runId" to buildRun, "distribution" to distribution))
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""{"applicationId":"dev.hyprdroid.launcher","versionCode":$buildCode,"versionName":"$buildName","commit":"$buildCommit","runId":"$buildRun","distribution":$distribution}""")
        }
    }
}
tasks.named("preBuild").configure { dependsOn(generateBuildIdentity) }
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
