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
 * sysroot lacks (only libsqlite3 so far), staged out of the SDK's build engine
 * by stage-sysroot.sh. Without them the linuxArm64 klib still compiles, so a
 * checkout with no Sailfish SDK can build and test everything but the library.
 */
val sailfishSysroot: String = providers.gradleProperty("sailfish.sysroot")
    .getOrElse(layout.projectDirectory.dir("sailfish-sysroot").asFile.absolutePath)
val canLinkSailfish = File("$sailfishSysroot/lib/libsqlite3.so").exists()

/**
 * The host's libsqlite3 for the linuxX64 test build, which runs the shared
 * tests natively in WSL. SQLDelight's driver links `-lsqlite3`, and a host
 * without the -dev package has only `libsqlite3.so.0`, so the bare name is
 * linked to it in a folder of our own. Kotlin/Native's x86_64 sysroot is an old
 * glibc and the host library a new one, hence `--allow-shlib-undefined`: the
 * symbols resolve against the host's glibc when the tests run.
 */
val hostLibDir: File = layout.buildDirectory.dir("host-lib").get().asFile
val hostSqlite: File? = listOf("/usr/lib/x86_64-linux-gnu", "/lib/x86_64-linux-gnu", "/usr/lib64", "/usr/lib")
    .flatMap { listOf(File(it, "libsqlite3.so"), File(it, "libsqlite3.so.0")) }
    .firstOrNull { it.exists() }
if (hostSqlite != null) {
    hostLibDir.mkdirs()
    val link = File(hostLibDir, "libsqlite3.so").toPath()
    Files.deleteIfExists(link)
    Files.createSymbolicLink(link, hostSqlite.toPath())
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    linuxArm64 {
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
            }
        } else {
            logger.info("No Sailfish sysroot at $sailfishSysroot: run stage-sysroot.sh to link the library")
        }
    }

    linuxX64 {
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
            // Needs AES and P-256 from the platform, which the Sailfish core
            // gets from OpenSSL in milestone 10 of docs/SAILFISH_PORT.md. The
            // SHA-256 and HMAC it does have are tested in linuxTest.
            kotlin.exclude("app/indoorbike/shared/ble/zwift/ZwiftCryptoTest.kt")
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        linuxMain.dependencies {
            implementation(libs.sqldelight.native)
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
