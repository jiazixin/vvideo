buildscript {
    val catalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")

    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Kotlin 2.4 needs a newer D8/R8 than the one embedded in AGP 8.13.
        classpath(catalog.findLibrary("r8").get())
        classpath(platform(catalog.findLibrary("netty-bom").get()))
        constraints {
            // Pin patched transitive versions on the build classpath only.
            for (alias in listOf(
                "guava-jre",
                "bouncycastle-pkix",
                "bouncycastle-provider",
                "bouncycastle-util",
                "commons-compress",
                "jose4j",
                "jdom",
            )) {
                add("classpath", catalog.findLibrary(alias).get())
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
