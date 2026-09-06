pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
// The foojay-resolver-convention plugin auto-downloads JDK distributions from
// a third-party API when Gradle can't find a matching JVM toolchain locally.
// It is deliberately absent, along with `jvmToolchain(...)` in the module build
// scripts, so the build uses whatever JDK is running Gradle and never reaches
// the network for a toolchain. JDK 17 bytecode is still produced via
// sourceCompatibility / targetCompatibility (Java) and JvmTarget.JVM_17
// (Kotlin), which work cross-JDK.
//
// F-Droid's scanner is what originally forced this, and F-Droid is no longer a
// distribution target. The arrangement stays because it costs nothing and a
// build that fetches a JDK mid-run is worse than one that does not. If pinning
// the JDK per module ever becomes worth more than that, `jvmToolchain(17)` is
// the change and foojay is the plugin that makes it painless.

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CyclingApp"
include(":shared")
include(":mobile_clients:android")
