plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // No `jvmToolchain(...)` - see settings.gradle.kts for why. Bytecode target is set via JvmTarget.JVM_17 below
    // (Kotlin) and sourceCompatibility / targetCompatibility (Java).

    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                    freeCompilerArgs.addAll("-Xexpect-actual-classes")
                }
            }
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.coroutines)
            api(libs.kable.core) // BLE operations
            // The companion host's WebSocket server, on both platforms.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "app.indoorbike.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    buildFeatures {
        // BuildConfig.DEBUG is consulted by the Android-side `Log` actual to
        // silence diagnostics in release builds.
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

sqldelight {
    databases {
        create("HistoryDatabase") {
            packageName.set("app.indoorbike.shared.db")
            // The schema as it first shipped, so the build replays every
            // migration from it and fails if the result is not History.sq.
            // Nothing checked that before: a migration that drifted from the
            // table it was meant to produce would only be found on a rider's
            // phone.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
