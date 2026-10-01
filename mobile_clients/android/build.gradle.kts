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

// Intervals.icu OAuth client: a gitignored intervals.properties at repo root,
//
//   intervalsClientId=...
//   intervalsClientSecret=...
//
// Intervals.icu's OAuth has no PKCE, so signing a rider in needs the client
// secret, and this repository is public. A build without the file compiles
// both fields as empty strings and offers the rider's personal API key instead,
// which is what anyone building from source gets. Release builds must have it.
val intervalsPropsFile = rootProject.file("intervals.properties")
val intervalsProps = Properties().apply {
    if (intervalsPropsFile.exists()) intervalsPropsFile.inputStream().use { load(it) }
}
fun buildConfigString(value: Any?): String =
    "\"" + (value as String? ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
// Blank counts as absent: a file created from the template and not yet filled
// in is the likely way to end up with a release that cannot sign anybody in.
if ((intervalsProps["intervalsClientId"] as String? ?: "").isBlank() ||
    (intervalsProps["intervalsClientSecret"] as String? ?: "").isBlank()
) {
    logger.warn(
        "intervals.properties " + (if (intervalsPropsFile.exists()) "is not filled in" else "not found") +
            ": this build signs in to Intervals.icu with an API key only"
    )
}

android {
    namespace = "app.indoorbike"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.indoorbike"
        minSdk = 26
        targetSdk = 36
        versionCode = 27
        versionName = "2.8.2"

        // Community workout-repository registry. Hardcoded so the app picks it
        // up automatically once the file is hosted; absence is handled
        // gracefully (Browse just shows an empty state).
        buildConfigField("String", "REGISTRY_URL", "\"https://indoorbike.app/registry.json\"")

        buildConfigField("String", "INTERVALS_CLIENT_ID", buildConfigString(intervalsProps["intervalsClientId"]))
        buildConfigField("String", "INTERVALS_CLIENT_SECRET", buildConfigString(intervalsProps["intervalsClientSecret"]))
    }

    signingConfigs {
        // The debug key, shared. Each machine used to sign debug builds with
        // its own ~/.android/debug.keystore, so a debug build from one machine
        // could not update one installed from another: Android refused it as a
        // different signer, and the only way past was uninstalling, which
        // takes the rides and pairings on the device with it. It is a debug
        // key with the stock passwords, published with the source, and it
        // signs nothing that is distributed.
        //
        // Published, so nothing may trust it: website/.well-known/assetlinks.json
        // lists the release key alone. It used to list this one too, and a
        // published key a verified link trusts is anyone's to sign an app
        // with that answers the Intervals.icu sign-in. A debug build therefore
        // connects Intervals.icu with an API key, not the sign-in.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
        // AGP embeds a "Dependency metadata" blob holding hashes of every
        // dependency. Play reads it to flag known-vulnerable libraries in
        // the Console.
        //
        // The AAB keeps it. That artifact goes to Play and nowhere else, and
        // the vulnerability warnings are worth having.
        //
        // The APK drops it. That artifact goes to CodeFloe for ObtainX,
        // where the blob is an unrecognised extra APK signing block that
        // nobody reads and that scanners have rejected outright
        // (https://issuetracker.google.com/issues/189966237).
        includeInApk = false
        includeInBundle = true
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

    // Floors, not dependencies. Nothing here is imported by this app: they are
    // what CameraX's view module brings with it, and what it brings is a
    // 2019 appcompat and the 2019 fragment underneath it. A constraint raises
    // the version if something asks for the module and adds nothing if nothing
    // does, so the day CameraX stops asking, this stops applying.
    constraints {
        implementation(libs.androidx.appcompat) {
            because("camera-view asks for 1.1.0; Play reports its fragment as outdated")
        }
        implementation(libs.androidx.fragment) {
            because("appcompat 1.1.0 asks for fragment 1.1.0, which Play reports as outdated")
        }
    }
}

tasks.register("prepareKotlinBuildScriptModel") {}
tasks.register("prepareKotlinIdeaImport") {
    dependsOn(project(":shared").tasks.named("prepareKotlinIdeaImport"))
}
