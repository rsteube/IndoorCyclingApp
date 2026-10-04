import java.nio.file.Files

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

/** The shared Kotlin, compiled here as it is, with no copy of its own. */
val shared = "../../../shared/src"

/**
 * The phone's libraries the linker needs and Kotlin/Native's own aarch64
 * sysroot lacks (libsqlite3 and libcrypto), staged out of the SDK's build
 * engine by stage-sysroot.sh. Without them the linuxArm64 klib still compiles,
 * so a checkout with no Sailfish SDK can build and test everything but the
 * library.
 */
val sailfishSysroot: String = providers.gradleProperty("sailfish.sysroot")
    .getOrElse(layout.projectDirectory.dir("sailfish-sysroot").asFile.absolutePath)
val canLinkSailfish = listOf("libsqlite3.so", "libcrypto.so").all { File("$sailfishSysroot/lib/$it").exists() }

/**
 * The host's libsqlite3 and libcrypto for the linuxX64 test build, which runs
 * the shared tests natively in WSL. SQLDelight's driver links `-lsqlite3` and
 * the Zwift crypto `-lcrypto`, and a host without the -dev packages has only
 * the versioned files, so the bare names are linked to them in a folder of our
 * own. Kotlin/Native's x86_64 sysroot is an old glibc and the host libraries a
 * new one, hence `--allow-shlib-undefined`: the symbols resolve against the
 * host's glibc when the tests run.
 */
val hostLibDir: File = layout.buildDirectory.dir("host-lib").get().asFile
for ((name, versioned) in listOf("libsqlite3" to "libsqlite3.so.0", "libcrypto" to "libcrypto.so.3")) {
    val found = listOf("/usr/lib/x86_64-linux-gnu", "/lib/x86_64-linux-gnu", "/usr/lib64", "/usr/lib")
        .flatMap { listOf(File(it, "$name.so"), File(it, versioned)) }
        .firstOrNull { it.exists() } ?: continue
    hostLibDir.mkdirs()
    val link = File(hostLibDir, "$name.so").toPath()
    Files.deleteIfExists(link)
    Files.createSymbolicLink(link, found.toPath())
}

/** The OpenSSL calls the Zwift controller session makes; see libcrypto.def. */
val libcryptoDef = layout.projectDirectory.file("src/nativeInterop/cinterop/libcrypto.def")

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    linuxArm64 {
        compilations.getByName("main").cinterops.create("libcrypto") { definitionFile.set(libcryptoDef) }
        if (canLinkSailfish) {
            binaries.sharedLib {
                baseName = "indoorbikecore"
                linkerOpts("-L$sailfishSysroot/lib")
                // Kotlin/Native always asks for libcrypt.so.1, which glibc
                // 2.41 on Sailfish OS 5.1 no longer has, and nothing in the
                // library uses it. Without this the phone refuses to load it.
                linkerOpts("--as-needed")
                // A name of its own, which rpmlint asks of any shared library.
                linkerOpts("-soname", "libindoorbikecore.so")
                // One exported symbol, the bridge's; see exports.map.
                linkerOpts("--version-script=${layout.projectDirectory.file("exports.map").asFile.absolutePath}")
            }
        } else {
            logger.info("No Sailfish sysroot at $sailfishSysroot: run stage-sysroot.sh to link the library")
        }
    }

    linuxX64 {
        compilations.getByName("main").cinterops.create("libcrypto") { definitionFile.set(libcryptoDef) }
        binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
            linkerOpts("-L${hostLibDir.absolutePath}", "--allow-shlib-undefined")
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir("$shared/commonMain/kotlin")
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.coroutines)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
            }
        }
        commonTest {
            kotlin.srcDir("$shared/commonTest/kotlin")
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        linuxMain.dependencies {
            implementation(libs.sqldelight.native)
            // HTTPS, in the core rather than Qt: see LinuxHttp. Ktor's client is not in the
            // catalog (the other apps use their platforms' own), so it takes the catalog's
            // Ktor version.
            implementation("io.ktor:ktor-client-curl:${libs.versions.ktor.get()}")
        }
    }
}

sqldelight {
    databases {
        create("HistoryDatabase") {
            packageName.set("app.indoorbike.shared.db")
            srcDirs.setFrom("$shared/commonMain/sqldelight")
        }
    }
}
