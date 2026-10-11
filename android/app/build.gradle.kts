plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose); alias(libs.plugins.ksp) }

@org.gradle.api.tasks.CacheableTask
abstract class ConnectionPolicyResources : org.gradle.api.DefaultTask() {
 @get:org.gradle.api.tasks.Input abstract val httpHost: org.gradle.api.provider.Property<String>
 @get:org.gradle.api.tasks.OutputDirectory abstract val outputDir: org.gradle.api.file.DirectoryProperty
 @org.gradle.api.tasks.TaskAction fun generate() {
  val host=httpHost.get()
  if(host.isNotEmpty()) {
   val parts=host.split('.').map { it.toIntOrNull() }
   require(parts.size==4 && parts.all { it!=null && it in 0..255 } && parts.joinToString(".")==host &&
    (parts[0]==10 || parts[0]==172 && parts[1]!! in 16..31 || parts[0]==192 && parts[1]==168)) { "lanHttpHost must be one explicit private LAN IPv4 address" }
  }
  val root=outputDir.get().asFile
  val xml=root.resolve("xml/network_security_config.xml");xml.parentFile.mkdirs()
  val domain=if(host.isEmpty()) "" else "<domain includeSubdomains=\"false\">$host</domain>"
  xml.writeText("""<network-security-config>
<base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system"/></trust-anchors></base-config>
<domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="false">localhost</domain><domain includeSubdomains="false">127.0.0.1</domain><domain includeSubdomains="false">::1</domain>$domain</domain-config>
</network-security-config>""")
  val values=root.resolve("values/connection_policy.xml");values.parentFile.mkdirs()
  values.writeText("""<resources><string name="trusted_lan_http_host" translatable="false">$host</string></resources>""")
 }
}
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
androidComponents.onVariants { variant ->
 val task=tasks.register<ConnectionPolicyResources>("generate${variant.name.replaceFirstChar { it.uppercase() }}ConnectionPolicy") {
  httpHost.set(providers.gradleProperty("lanHttpHost").orElse(""))
 }
 variant.sources.res?.addGeneratedSourceDirectory(task,ConnectionPolicyResources::outputDir)
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
 implementation(project(":core:media")); implementation(platform(libs.compose.bom)); implementation(libs.compose.ui); implementation(libs.compose.foundation); implementation(libs.compose.material); implementation(libs.activity.compose); implementation(libs.lifecycle.compose); implementation(libs.lifecycle.viewmodel); implementation(libs.coroutines); implementation(libs.coil); implementation(libs.coil.network); implementation(libs.datastore); implementation(libs.room); implementation(libs.room.ktx); ksp(libs.room.compiler); implementation(libs.okhttp); implementation(libs.profileinstaller); testImplementation(libs.junit); androidTestImplementation(libs.android.test.runner); androidTestImplementation(libs.android.test.junit); androidTestImplementation(libs.android.test.core); androidTestImplementation(libs.errorprone.annotations); androidTestImplementation(libs.uiautomator)
}
