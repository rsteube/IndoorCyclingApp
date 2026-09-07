import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.kotlinSerialization)
}

// Release signing: load credentials from a gitignored keystore.properties at
// repo root. If the file is missing (e.g. CI without secrets, or a contributor
// cloning the repo), release builds fall back to the debug keystore so the
// project still builds — those APKs are not redistributable.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "app.indoorbike"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.indoorbike"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "2.0.2"

        // Community workout-repository registry. Hardcoded so the app picks it
        // up automatically once the file is hosted; absence is handled
        // gracefully (Browse just shows an empty state).
        buildConfigField("String", "REGISTRY_URL", "\"https://indoorbike.app/registry.json\"")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Side-by-side install: the debug build has its own application id
            // and label so it sits next to a production build on the same device.
            // Combined with the grayscale launcher icon (src/debug/res), the two
            // are unambiguous at a glance.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            // R8 in shrink + optimise mode. Compose, kotlinx-serialization,
            // SqlDelight, Kable, AndroidX, and ZXing all ship consumer
            // ProGuard rules so the only project-specific extras live in
            // proguard-rules.pro (currently just defensive keeps for our
            // @Serializable models in shared/sync).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The `Instantiatable` check claims `MainActivity` doesn't extend
        // `Activity`. It does — via `androidx.activity.ComponentActivity` —
        // but Lint can't always trace that chain across module boundaries.
        // Disable just this single false-positive check; everything else
        // still runs, including the lintVitalRelease pass on release builds.
        disable += "Instantiatable"
    }

    dependenciesInfo {
        // AGP embeds an extra APK signing block ("Dependency metadata")
        // with hashes of every dependency, intended for Google Play's
        // app-review tooling. We do not publish to Play, so it is payload
        // nobody reads, and it is exactly the kind of Google-oriented
        // metadata this app has no business shipping. Disabled for both the
        // APK and the AAB.
        //
        // F-Droid's APK scanner rejecting unrecognised signing blocks
        // (https://issuetracker.google.com/issues/189966237) is what first
        // put this here; the setting outlived that reason on its own merits.
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    // No `jvmToolchain(...)` — see settings.gradle.kts. Bytecode target is
    // set via JvmTarget.JVM_17 here (Kotlin) and sourceCompatibility /
    // targetCompatibility above (Java).
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xexpect-actual-classes")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
    implementation(libs.osmdroid.android)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

    debugImplementation(libs.compose.ui.tooling)
}

tasks.register("prepareKotlinBuildScriptModel") {}
tasks.register("prepareKotlinIdeaImport") {
    dependsOn(project(":shared").tasks.named("prepareKotlinIdeaImport"))
}
