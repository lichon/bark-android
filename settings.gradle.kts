rootProject.name = "abark"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Engine modules are plain Kotlin/JVM so they can be unit-tested (and run from `tools/cli`) without an Android SDK;
// `:app` and `:core:update-android` need the Android Gradle Plugin, JDK 17 and build-tools 34 (see docs/DEVELOPING.md).
include(":core:net")
include(":core:apns")
include(":core:bark")
include(":core:update")
include(":core:update-android")
include(":tools:cli")
include(":app")
