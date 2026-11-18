plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.vvideo.core.media"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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

dependencies {
    constraints {
        implementation(libs.guava.android)
    }
    // The module exposes only Player; callers declare the Media3 parts they use.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.cache)
    implementation(libs.media3.okhttp)
    implementation(libs.okhttp)
    implementation(libs.media3.db)
    implementation(libs.coroutines)
    testImplementation(libs.junit)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.coroutines.test)
}
