pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
// The foojay-resolver-convention plugin auto-downloads JDK distributions
// from a third-party API when Gradle can't find a matching JVM toolchain
// locally. F-Droid's scanner bans it because (1) it kills reproducibility
// (downloads can vary), (2) it pulls a non-free network dependency, and
// (3) the F-Droid buildserver provides its own JDK that the build should
// use. We removed it and dropped `jvmToolchain(...)` from the module
// build scripts so the build uses whatever JDK is running Gradle. JDK 17
// bytecode is still produced via sourceCompatibility / targetCompatibility
// (Java) and JvmTarget.JVM_17 (Kotlin) — those work cross-JDK.

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CyclingApp"
include(":shared")
include(":mobile_clients:android")
