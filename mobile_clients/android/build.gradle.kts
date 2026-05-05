plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "app.indoorbike"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.indoorbike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Community workout-repository registry. Hardcoded so the app picks it
        // up automatically once the file is hosted; absence is handled
        // gracefully (Browse just shows an empty state).
        buildConfigField("String", "REGISTRY_URL", "\"https://indoorbike.app/registry.json\"")
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
            isMinifyEnabled = false
            // Personal-use release builds: sign with the debug keystore so the
            // APK actually installs. Replace with a proper keystore when
            // shipping to F-Droid (or anywhere outside your own devices).
            signingConfig = signingConfigs.getByName("debug")
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
}

kotlin {
    jvmToolchain(17)
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
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)

    debugImplementation(libs.compose.ui.tooling)
}
