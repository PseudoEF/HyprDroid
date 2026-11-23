plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val distribution = providers.environmentVariable("HYPRDROID_DISTRIBUTION").orNull == "true"
// Local builds stay ahead of v0.6 (8); distributed codes must be allocated by CI.
val testExperimental = providers.gradleProperty("testExperimental").orNull == "true"
val coreApplicationId = "dev.hyprdroid.launcher"
val experimentalApplicationId = "$coreApplicationId.experimental"
val buildCode = requireNotNull(providers.gradleProperty("buildVersionCode").orElse("17").get().toLongOrNull()) { "Invalid versionCode" }
require(buildCode in 1..2_100_000_000L) { "buildVersionCode must be within Android's supported range" }
val buildName = providers.gradleProperty("buildVersionName").orElse("0.9.0").get()
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
        applicationId = coreApplicationId
        minSdk = 29
        targetSdk = 35
        versionCode = buildCode.toInt()
        versionName = buildName
        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        buildConfigField("String", "BUILD_RUN_ID", "\"$buildRun\"")
        buildConfigField("boolean", "DISTRIBUTION", distribution.toString())
        buildConfigField("boolean", "EXTERNAL_LUA", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true; aidl = true }
    testBuildType = if (testExperimental) "experimental" else "debug"
    buildTypes {
        create("experimental") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".experimental"
            matchingFallbacks += "debug"
            ndk.abiFilters += listOf("arm64-v8a", "x86_64")
            buildConfigField("boolean", "DISTRIBUTION", "false")
            buildConfigField("boolean", "EXTERNAL_LUA", "true")
        }
    }
    sourceSets["experimental"].java.srcDir("src/externalLua/java")
    sourceSets["experimental"].aidl.srcDir("src/externalLua/aidl")
    listOf("debug", "release").forEach { sourceSets[it].java.srcDir("src/noExternalLua/java") }
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
abstract class GenerateBuildIdentity : DefaultTask() {
    @get:Input abstract val identity: Property<String>
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun generate() {
        output.file("build-info.json").get().asFile.apply {
            parentFile.mkdirs()
            writeText(identity.get())
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("experimental")) { variant ->
        variant.enable = !distribution
    }
    onVariants { variant ->
        val experimental = variant.buildType == "experimental"
        val id = if (experimental) experimentalApplicationId else coreApplicationId
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val task = tasks.register<GenerateBuildIdentity>("generate${variantName}BuildIdentity") {
            output.set(layout.buildDirectory.dir("generated/buildInfoAssets/${variant.name}"))
            identity.set("""{"applicationId":"$id","versionCode":$buildCode,"versionName":"$buildName","commit":"$buildCommit","runId":"$buildRun","distribution":${distribution && !experimental}}""")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, GenerateBuildIdentity::output)
    }
}
dependencies {
    add("experimentalImplementation", project(":external-lua-native"))
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.luaj:luaj-jse:3.0.1")
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
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
