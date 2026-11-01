plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Release version and signing come from Gradle properties or environment
// variables, never from the repository; see android/README.md.
fun releaseSetting(name: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.vvideo"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.vvideo"
        minSdk = 28
        targetSdk = 36
        versionCode = releaseSetting("VVIDEO_VERSION_CODE")?.toInt() ?: 1
        versionName = releaseSetting("VVIDEO_VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        releaseSetting("VVIDEO_KEYSTORE_FILE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = requireNotNull(releaseSetting("VVIDEO_KEYSTORE_PASSWORD")) {
                    "VVIDEO_KEYSTORE_PASSWORD is required with VVIDEO_KEYSTORE_FILE"
                }
                // PKCS12 key stores use one password for the store and the key.
                keyPassword = storePassword
                keyAlias = releaseSetting("VVIDEO_KEY_ALIAS") ?: "vvideo"
            }
        }
    }
    buildTypes {
        release {
            // Without a key store the release APK stays unsigned.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
            testProguardFiles("test-proguard-rules.pro")
        }
        create("device") {
            initWith(getByName("profile"))
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    sourceSets.getByName("device").manifest.srcFile("src/profile/AndroidManifest.xml")
    testBuildType = providers.gradleProperty("deviceTestBuildType").orElse("debug").get()
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = true
        warningsAsErrors = true
        baseline = file("lint-baseline.xml")
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

room { schemaDirectory("$projectDir/schemas") }
dependencies {
    implementation(project(":core:media"))
    implementation(libs.media3.common)
    implementation(libs.media3.ui)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines)
    implementation(libs.coil)
    implementation(libs.coil.network)
    implementation(libs.datastore)
    implementation(libs.room)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.profileinstaller)
    testImplementation(libs.junit)
    // JVM tests need a real org.json implementation; the Android stub returns null.
    testImplementation(libs.json.test)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.android.test.runner)
    // Device tests read decoder names through the ExoPlayer analytics listener.
    androidTestImplementation(libs.media3.exoplayer)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.android.test.core)
    androidTestImplementation(libs.errorprone.annotations)
    androidTestImplementation(libs.uiautomator)
}
