// The Sailfish OS core: the shared Kotlin compiled with Kotlin/Native into the
// shared library the Sailfish app links (libindoorbikecore.so).
//
// A Gradle build of its own rather than a target in :shared, because :shared
// applies the Android library plugin and the root build includes the Android
// app, and neither should need an Android SDK inside WSL where the Sailfish SDK
// lives. It compiles shared/src/commonMain by source directory, so there is one
// copy of every shared file, and building it is a standing check that common
// code still compiles for Linux. See docs/SAILFISH_PORT.md.
//
// Run with the repository's wrapper:
//   ./gradlew -p mobile_clients/sailfish/core linuxX64Test
//   ./gradlew -p mobile_clients/sailfish/core linkReleaseSharedLinuxArm64
rootProject.name = "indoorbike-sailfish-core"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // The repository's own catalog, read in place rather than copied, so the
    // core builds the shared code against the library versions Android and
    // iOS use.
    versionCatalogs {
        create("libs") {
            from(files("../../../gradle/libs.versions.toml"))
        }
    }
}
