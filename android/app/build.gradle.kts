plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose); alias(libs.plugins.ksp) }

android {
 namespace = "dev.vvideo"
 compileSdk = 36
 defaultConfig { applicationId = "dev.vvideo"; minSdk = 28; targetSdk = 36; versionCode = 1; versionName = "0.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro") }; create("profile") { initWith(getByName("release")); signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release"; isDebuggable = false; testProguardFiles("test-proguard-rules.pro") }; create("device") { initWith(getByName("profile")); isMinifyEnabled = false } }
 sourceSets.getByName("device").manifest.srcFile("src/profile/AndroidManifest.xml")
 testBuildType = providers.gradleProperty("deviceTestBuildType").orElse("debug").get()
 testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
 implementation(project(":core:media")); implementation(platform(libs.compose.bom)); implementation(libs.compose.ui); implementation(libs.compose.foundation); implementation(libs.compose.material); implementation(libs.activity.compose); implementation(libs.lifecycle.compose); implementation(libs.lifecycle.viewmodel); implementation(libs.coroutines); implementation(libs.coil); implementation(libs.coil.network); implementation(libs.datastore); implementation(libs.room); implementation(libs.room.ktx); ksp(libs.room.compiler); implementation(libs.okhttp); implementation(libs.profileinstaller); testImplementation(libs.junit); androidTestImplementation(libs.android.test.runner); androidTestImplementation(libs.android.test.junit); androidTestImplementation(libs.android.test.core); androidTestImplementation(libs.errorprone.annotations); androidTestImplementation(libs.uiautomator)
}
