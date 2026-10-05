# Sailfish OS port

The Sailfish app is a native Silica/QML app on top of the shared Kotlin, compiled with Kotlin/Native into a shared library. It is built the same way as Snapp's two earlier Sailfish ports, Nextcloud Talk for Sailfish (`nextcloud-sailfish`) and the Companion for UNA (`unawatch/mobile/sailfish`). There is no JVM and no Compose on Sailfish, so the UI is written again in QML, the way the iOS app wrote it again in SwiftUI. Everything below the UI runs the same Kotlin as Android and iOS: rides, trainer control, physics, workouts, history, FIT and TCX export, sync, uploads and the companion host.

Target: Sailfish OS 5.1 on **aarch64** (Jolla C2). Kotlin/Native dropped 32-bit ARM (`linuxArm32Hfp`) and the build engine cannot run i486 binaries under WSL2, so armv7hl and i486 phones are out of reach.

Status as of 2026-10-04: milestone 0 is done apart from the iOS build and a real-trainer ride on Android. The shared code compiles for Linux, its common tests pass there natively, and `libindoorbikecore.so` links for the phone and loads its dependencies on a Jolla C2. There is no Sailfish app yet.

## Building the core

In the WSL clone, with the Sailfish SDK's build engine running:

```sh
mobile_clients/sailfish/core/stage-sysroot.sh            # once: the phone's libsqlite3, for the linker
./gradlew -p mobile_clients/sailfish/core linuxX64Test   # the shared tests, natively in WSL
./gradlew -p mobile_clients/sailfish/core linkReleaseSharedLinuxArm64
```

The library and its C header land in `mobile_clients/sailfish/core/build/bin/linuxArm64/releaseShared/`. Run Gradle on JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`); Kotlin 2.2.10 predates JDK 25.

## What carries over and what does not

Checked against Maven Central on 2026-10-03: every library `shared/` depends on publishes `linuxArm64` at the version already pinned (coroutines 1.8.0, serialization 1.6.3, SQLDelight native driver 2.0.1, Ktor server CIO 3.1.3, `com.benasher44:uuid`). The one exception is **Kable**, which has no Linux target at any version. It is used directly in `commonMain`, in seven files under `ble/` (about 3,500 lines). That is the only refactor that blocks a Linux build of the shared code.

| Shared code | On Sailfish |
|---|---|
| `session`, `simulation`, `physics`, `workout`, `progression`, `route`, `fit`, `activity`, `library`, `repo`, `sync`, `feedback`, `companion` protocol and session, `ble` parsers and FTMS codecs | As is |
| `ble` managers (`TrainerManager`, `HeartRateMonitor`, `ControllerManager`) | As is, once they talk to a transport interface instead of Kable (milestone 0) |
| `CompanionHost` (Ktor server CIO) | As is; listening on the LAN under Sailjail to be confirmed on the phone |
| `db` (SQLDelight) | `NativeSqliteDriver`, linked to the phone's `libsqlite3` |
| `expect`s: `DatabaseDriverFactory`, `Log`, `getCurrentEpochMillis`, `secureRandomBytes`, `ReentrantLock`, `sha256`, `formatLocalMinute`, `gunzip`, `localIPv4Addresses`, `ZwiftCrypto` | New Linux `actual`s, mostly ports of the iOS ones onto `platform.posix`, zlib and OpenSSL |
| Interfaces: `HttpClient`, `TextFetcher`, `KeyValueStore`, `TextStore`, `UserSettings`, the session, telemetry and route-progress stores, `SyncStorage`, `CompanionAdvertiser`, `CompanionBeacon`, sinks | New Linux implementations, laid out like `iosMain`'s |

What is written new: the BLE transport (C++, BlueZ over D-Bus), the Qt bridge, the QML screens, the drawn items (charts, map, share picture) and the packaging.

## How it fits together

```
QML / Silica pages, cover
        |  core.send({...}), core.state.*, list models
C++ host (Qt 5.6)
  - IndoorBikeCore: the bridge to the Kotlin library, events to the Qt thread
  - BlueZ transport: scan, connect, GATT over D-Bus and acquired sockets
  - list models, painted items (charts, route map, share image)
        |  4 exported C functions, JSON in and out
libindoorbikecore.so (Kotlin/Native, linuxArm64)
  - shared/src/commonMain, unchanged
  - linuxMain: actuals, stores, settings, curl HTTP, LinuxAppContainer, Bridge
```

### Decisions

1. **A separate Gradle root, `mobile_clients/sailfish/core`, compiles `shared/src/commonMain` by source directory.** Both earlier ports do this. The root build includes the Android app, and `shared` applies the Android library plugin, so building `shared` itself in WSL would need an Android SDK there. The separate root reads the same version catalog (`gradle/libs.versions.toml`), points SQLDelight at `shared/src/commonMain/sqldelight`, and runs `shared/src/commonTest` on `linuxX64` natively in WSL. It leaves the Android and iOS builds untouched. Building it is also a standing check that `commonMain` still compiles for Linux: anything that brings a JVM-only or Apple-only API into common code fails here.
   - The Linux `actual`s and platform code live in `core/src/linuxMain`, not in `shared/`. That is the one difference from the iOS port, and it is the price of keeping the Android SDK out of WSL.

2. **Kable goes behind a transport seam, in `shared`, before anything else.** Add a small `BleCentral` interface in `commonMain`: availability, scan by service UUID, connect by id, a connection-state flow, discovered services, and observe, write and read on a characteristic. The Kable implementation moves into a new intermediate source set, `kableMain` (Android and iOS), and the three managers take a `BleCentral`. The Sailfish core leaves Kable out simply by not reading `kableMain`. `WorkoutSessionManager`'s `uuid4()` (from `com.benasher44`, which arrives through Kable) becomes `kotlin.uuid.Uuid`, already used in `RideHistory`. The managers' `internal` byte-level entry points (`onIndoorBikeData`, `onCyclingPower`, `onHeartRate`, `handleControlPointResponse`) and the built-in mock trainer stay as they are.

3. **The BLE transport is C++, BlueZ over QtDBus**, taken from the UNA app's `watchmanager` and `gattpipe`. Qt Bluetooth is not in the Sailfish target, and the `Bluetooth` Sailjail permission grants exactly D-Bus access to `org.bluez`. The Kotlin side is a `BridgedCentral` that implements `BleCentral` by sending commands to C++ and receiving frames from it. The data rate is low (Indoor Bike Data at about 4 Hz, heart rate at 1 Hz), so JSON with base64 frames is fine.
   - Notifications through `AcquireNotify` where BlueZ allows it. The FTMS control point (0x2AD9) is indicate-only, so it uses `StartNotify` and `WriteValue` with `type: request`.
   - Discovery with `SetDiscoveryFilter { Transport: le, UUIDs: [FTMS, Cycling Power, Heart Rate, Zwift] }`.
   - Devices are found by their `Address` property, never by a D-Bus path built from it.
   - Acquired sockets are released before `Disconnect`, or BlueZ keeps the notifications claimed until `bluetoothd` restarts.
   - Device ids are MAC addresses, as on Android, so a paired trainer may carry over in a sync between Android and Sailfish.

4. **One core thread.** Kotlin/Native has no main dispatcher on Linux. The core runs its app graph on `newSingleThreadContext("core")`, as the UNA app does. `send()` returns a short acknowledgement at once and never waits on the network or the radio. Results and state come back as events through the C callback, which C++ queues onto the Qt thread with `QMetaObject::invokeMethod(..., Qt::QueuedConnection)`.

5. **The bridge is four exported functions and a JSON bus:** `indoorbikeStart(dataDir, configDir, cacheDir, packageDir)`, `indoorbikeSubscribe(callback)`, `indoorbikeSend(json)` and `indoorbikeStop()`. Both earlier ports do this, because the shared managers expose many flows and keep changing.
   - A topic without a dot is state. It goes into a property map (`core.state.trainer`, `core.state.ride`).
   - A topic with a dot is a one-shot event (`ride.saved`).
   - Lists use one generic keyed list model that diffs updates into inserts and changes, so lists keep their scroll position.
   - Live ride topics are throttled and deduplicated on the Kotlin side.
   - C++ copies every string it is handed and gives Kotlin's back with `DisposeString`.
   - `LinuxAppContainer` wires the managers the way `IosAppContainer` does.

6. **HTTPS goes through Ktor's curl engine in Kotlin, never through Qt.** Qt 5.6's TLS failed against modern servers on Sailfish 5.1 and took the process with it. A Linux `HttpClient` implements the shared interface with `ktor-client-curl`, added in `linuxMain` only, with the same contract as `IosHttp`: https only, no redirects, Android's timeouts and size caps. The engine must be registered by hand. In a Kotlin/Native shared library the linker drops its eager registration, and every request then fails with "Failed to find HTTP client engine implementation".

7. **Storage stays in the three folders Sailjail allows.** The directories come from C++ (`QStandardPaths`) at start, and Kotlin never builds a home path itself.
   - History: SQLDelight's native driver with an explicit `basePath` and foreign keys turned on.
   - The active-session, telemetry and route-progress files keep Android's and iOS's names and layout.
   - Settings: a JSON file in the config folder, with Android's keys and limits.
   - Credentials: a separate file readable only by the owner. Sailfish Secrets is missing on the Jolla C2. This is not secure storage, and the app should not call it that.

8. **Companion mode runs the shared `CompanionHost` as is.** The UDP beacon is a POSIX socket in Kotlin. Bonjour can go through Avahi over D-Bus if the phone has it (not yet checked). Without it, the beacon and the typed address still work.

9. **The UI follows Silica, not Android's layout.** Four tabs do not translate to Sailfish.
   - **Navigation:**
     - Ride ("What are you riding?") is the first page.
     - History is its attached page, reached by swiping forward.
     - Progress and Settings are in the pull-down menu.
     - Destructive actions use a remorse timer instead of a confirmation dialog.
   - **Ride screen:** works in portrait and landscape. It keeps the display on (`DisplayBlanking`) and the device awake (`KeepAlive`) while a ride runs.
   - **Cover:** a minimised app shows its cover on Home, and here that is the live ride: power against target, heart rate and time left, with pause or resume as a cover action.
   - **Charts, the route map and the share picture** are C++ painted items, never QML `Canvas`, which goes blank after the app has been in the background. The map reuses the UNA app's raster tile map.
   - **Power zone colours and boundaries move into `shared`.** iOS copied them into Swift; Sailfish would be the third copy.

10. **The package is Harbour-clean from the first build.**
    - Name: `harbour-indoorbike`, QML namespace `harbour.indoorbike`, Sailjail `app.indoorbike` / `indoorbike`.
    - The library is bundled in `/usr/share/harbour-indoorbike/lib`.
    - `sfdk check` runs on every build, whether the app ends up in the Jolla Store or not.
    - Permissions: `Bluetooth;Internet;Documents;Downloads` (for exports, GPX import and the sync folder). Ride positions come from the game, so `Location` is not needed.

### Layout

```
mobile_clients/sailfish/
  core/                         Gradle root: shared/commonMain + linuxMain -> libindoorbikecore.so
    settings.gradle.kts, build.gradle.kts, gradle.properties (-Xmx6g for the release link)
    src/linuxMain/              actuals, stores, settings, curl HTTP, BridgedCentral, LinuxAppContainer, Bridge
    src/linuxTest/              bridge and store tests
  harbour-indoorbike.pro / .desktop
  rpm/harbour-indoorbike.spec   strips both binaries; excludes the bundled library from provides/requires
  src/                          C++: bridge, BlueZ, list models, painted items, single instance
  qml/                          harbour-indoorbike.qml, pages/, components/, cover/
  icons/{86,108,128,172}/       from slate-design-system/logo
  tests/                        QML load tests and C++ tests, run in the build engine under qemu
  sync-core.sh, build.sh, push-core.sh, dev-launcher.sh, publish-release.sh
```

The sailfish-mcp inspect surface (`harbour-inspect/`) is vendored byte for byte and contains em dashes, which this repo forbids. As in the UNA repo, keep it untracked and include it behind `exists(...)`.

## Plan

Each milestone ends with the app on the Jolla C2, driven and screenshotted through sailfish-mcp. A milestone is not done because it compiled. As on iOS, logic the Android app keeps outside `shared/` moves into `shared/` when Sailfish first needs it, and Android then calls the shared version.

- [ ] **0. Groundwork.** No Sailfish UI yet.
  - [ ] `BleCentral` seam and the `kableMain` source set (decision 2). Done when Android and iOS tests pass unchanged and a ride on a real trainer works on Android.
    - [x] Android: all 853 shared tests pass, and the app compiles.
    - [ ] iOS: not built yet. This Windows machine cannot build Apple targets. `KableCentral` uses only Kable calls `commonMain` already made, so it should compile there too.
    - [ ] A ride on a real trainer on Android.
  - [x] `mobile_clients/sailfish/core` with the Linux `actual`s.
    - **Common code:** compiled for Linux with no change.
    - **Tests:** `linuxX64Test` passes 852 tests: the 843 common tests, `ZwiftCryptoTest` among them since milestone 10, plus 9 SHA-256 and HMAC vector tests for the Linux implementations.
    - **The library:** `libindoorbikecore.so` is 12 MB for aarch64. It needs only `libsqlite3.so.0`, `libz`, `libm`, `libpthread`, `libdl`, `libgcc_s` and `libc`, nothing newer than glibc 2.17. The phone's loader resolves all of them on a Jolla C2.
    - **Linux `actual`s:**
      - SHA-256 and HMAC are written in Kotlin and tested against FIPS 180-4 and RFC 4231.
      - Randomness comes from `/dev/urandom`, the lock is a recursive pthread mutex, gzip uses zlib, and the network addresses come from `getifaddrs`.
      - The database lives in the data folder the Qt side passes in (`SailfishPaths`).
      - The radio reports Bluetooth unavailable (`NoRadio`) until the BlueZ bridge exists.
      - The Zwift key pair, ECDH and AES come from OpenSSL 3 (milestone 10).
  - [x] A WSL clone of this repo in `~/projects/cycling_app`. The repo's `.gitattributes` already pins LF for every file.
  - [x] sailfish-mcp for several apps. Its WSL wrapper (`~/.local/bin/sailfish-mcp`, from the `sailfish-on-windows` skill) maps the Windows project Claude Code starts it in to the WSL clone of the same name, and enters that clone's Sailfish app. Until `mobile_clients/sailfish/harbour-indoorbike.pro` exists it starts with no app; reconnect it (`/mcp`) after adding the `.pro`.
- [x] **1. Core on the phone.**
  - From `sfdk init`: the C++ host loads the library, starts the core and shows its version.
  - `--selftest` runs the core headless over SSH. Not built: driving the debug build with sailfish-mcp did the same job.
  - The demo trainer streams power and cadence to a QML page. That proves the bridge and threading before any radio is involved.
  - Done when `sfdk check` passes and the debug build can be driven with sailfish-mcp.
  - **Status:** done. The release RPM passed `sfdk check`'s Harbour validation.
- [ ] **2. Trainer and heart rate over BlueZ.**
  - Scan, connect, disconnect and forget.
  - Live power, cadence and heart rate.
  - ERG target power and simulation writes on the control point.
  - Reconnect after the link drops.
  - Done on a real trainer and strap from the *installed* RPM, since only the installed copy runs under Sailjail.
  - Settle the risks below on connecting and link timeouts here.
  - **Status:** built. `BlueTransport` drives BlueZ over D-Bus and the Kotlin managers run on it unchanged; advertisements from the phone's radio reach them (13 devices heard). Not yet done on a real trainer or strap, or from the installed RPM.
- [x] **3. Ride a workout.**
  - `LinuxAppContainer`, the stores and settings.
  - The bundled library (`workouts_sample.json` shipped in `/usr/share/harbour-indoorbike`) and the workout page.
  - The ride screen: countdown, power against target in its zone colour, cadence, heart rate, interval time, what comes next, the profile with progress, difficulty ±5 %, pause, skip and end.
  - Saving to history.
  - The live cover with its actions.
  - Keep-awake.
  - The unfinished-ride banner.
  - **Status:** done with the demo trainer: a workout rides through to the end and is saved.
- [x] **4. History and progress.**
  - Weekly history and the ride page with its charts.
  - FIT and TCX export through the share sheet (`Sailfish.Share`) and into Downloads.
  - The 1080 × 1080 share picture, drawn with `QPainter`.
  - Level, streak and badges.
  - **Status:** done. The ride page's chart is a painted C++ item: QML `Canvas` goes blank after the app is backgrounded. The share picture is iOS's layout, drawn with `QPainter` into a PNG.
- [x] **5. Settings and onboarding.**
  - Profile, devices, bike and gearing.
  - The diagnostics log, with crash logging through Kotlin/Native's unhandled-exception hook.
  - "Report a problem", through the share sheet.
  - Onboarding.
  - **Status:** done. Settings keep Android's key names, so the backup carries them between platforms.
- [x] **6. Route rides and Just ride.**
  - The route library: bundled GPX, the registry, and import through the file picker.
  - The tile map, gradient, virtual gear with its shifter, and FTMS simulation.
  - **Status:** done. OpenStreetMap's tiles are fetched by the core and kept, two at a time under the app's own User-Agent as OSM's tile policy asks, and dimmed through the Android app's night filter.
- [x] **7. Workout library.**
  - Registry, repositories, favourites and filters.
  - Import from a file or a pasted link.
  - Scanning the editor's QR code waits: it needs the camera and a decoder, and pasting the link does the same job.
  - **Status:** done. HTTPS is Ktor's curl engine, told where the phone keeps its certificates. Each request gets its own client: Ktor 3.1's curl engine took no new request until every running one had finished, so one slow host stalled the app's networking, and a timeout could reach another request's caller.
- [x] **8. Sync and uploads.**
  - The curl `HttpClient`.
  - Intervals.icu signs in through the browser, as on Android and iOS, when the core is built with the gitignored `intervals.properties` at the repository root (its build generates `IntervalsClient`); without it the app takes the athlete id and API key instead. Intervals.icu sends the browser to https://indoorbike.app/oauth/intervals, and Sailfish has no verified links, so that page's Sailfish link hands the code on to `indoorbike://oauth/intervals`. The rider can paste the address instead. This path does not depend on the signing key, so a debug build signs in too.
  - The sync folder, picked with `FolderPickerDialog`.
  - FitTrackee's PKCE sign-in needs its redirect to come back into the app. `Amber.Web.Authorization`'s redirect listener was not used: FitTrackee checks the redirect against the one the rider registered, `indoorbike://oauth`, which a listener on localhost is not.
  - **Status:** done. A sync round writes the same slice files as Android and iOS; an upload reaches Intervals.icu. The app is single instance and D-Bus activatable (`org.freedesktop.Application` on `app.indoorbike.indoorbike`), so `indoorbike://oauth` and GPX files reach the running copy. Whether the browser hands `indoorbike://` to the app needs the installed RPM to check; until then the rider can paste the redirect address.
- [x] **9. Companion mode.**
  - `CompanionHost` on the phone, the beacon, Avahi if present, pairing, the companion HUD and its remote pad.
  - Verified with Col de Berg on the same Wi-Fi.
  - **Status:** done with `scripts/companion_probe.py` from a PC: discovery through the UDP beacon, pairing, the HUD, a climb, shifts, pause, finish, and the ride saved once. The phone has no Avahi, so there is no DNS-SD; the beacon and the typed address are the ways in. Not yet ridden with Col de Berg.
- [x] **10. Zwift controllers.** `ZwiftCrypto` on OpenSSL 3's libcrypto through cinterop: P-256 and ECDH, AES-ECB and HMAC-SHA256. Done when it is verified against the shared tests and a real Click.
  - **Status:** the crypto is done; a real Click is still to try. P-256, ECDH and the AES block come from OpenSSL 3 through cinterop, declared in `libcrypto.def` without OpenSSL's headers. They resolve against the OpenSSL 3.3 that Ktor's curl engine already links into the core, so the core needs no libcrypto from the phone. All 852 shared tests pass on Linux, `ZwiftCryptoTest` among them, and a debug build's `app.cryptoCheck` passes on the phone. The core now exports one symbol, `libindoorbikecore_symbols`; it used to export all of libcurl and that OpenSSL, which another library in the process could have bound to.
- [x] **11. Release.**
  - Published for [Harpoon](https://github.com/JuhaniLehtimaeki/Harpoon) on the releases-only repository [SnappTechnology/harbour-indoorbike](https://codefloe.com/SnappTechnology/harbour-indoorbike), whose README carries the add-to-Harpoon QR code. The code stays here. A separate repository keeps the Android APK releases here out of Harpoon's way (it reads only the newest 100) and the RPM releases out of Android release trackers'.
  - Each release: raise `Version:` (or `Release:` for a rebuild) in the spec with a `.changes` entry, `./build.sh`, commit the release notes to CHANGELOG.md in the releases repository and push a tag equal to the bare version (`0.3.0`) on that commit, then `./publish.sh notes.md`. Every release needs a commit of its own there: Codefloe dates a release by its tag's commit, and two releases on one commit tie, so Harpoon may keep offering the older one (0.3.0 had to be published again for this). The script refuses a tag that shares a commit with an earlier release. The script creates the release, attaches the RPM, and checks through the public API that it is published with the RPM at its local size. Its token, with write:repository on that repository, is `~/.config/codefloe/release-token-indoorbike-sailfish` (`CODEFLOE_TOKEN` overrides it).
  - A Jolla Store submission if wanted.
  - **Status:** 0.3.0 published on 2026-10-05, after 0.2.0 on 2026-10-04, `harbour-indoorbike-0.2.0-1.aarch64.rpm`, which passes `sfdk check`'s Harbour validation. rpmlint's one error, an architecture-dependent file in `/usr/share`, is the core library where Harbour requires an app's own libraries to be.

A first public build could stop after milestone 5 plus Intervals.icu from milestone 8.

## Risks to settle early

- **Connecting without bonding.** The UNA app could not pair from inside an app: BlueZ refuses `Pair` and agent registration to anything outside the `privileged` group. Trainers and straps usually need no bond, but `Connect` on an unbonded LE device from a sandboxed app has not been tried. Settle it in milestone 2; if it fails, riders pair in Settings > Bluetooth first.
- **The 420 ms link timeout.** Sailfish gives up on an LE link after 420 ms of silence; Android waits about 5 s. A trainer or strap that stays quiet for longer is dropped, and only root can change the timeout. Measure how often it happens in a one-hour ride in milestone 2. If it happens, reconnecting quickly in the middle of a ride matters more than on Android.
- **Indications over D-Bus.** BlueZ can merge two property changes that arrive close together into one signal. Control-point responses are rare, so this probably does not matter. A test should still show that a response is never lost.
- **Listening sockets under Sailjail** for the companion host and beacon, and whether the phone has Avahi. The phone has no Avahi. The host listens and the beacon broadcasts from the debug build, which runs outside Sailjail; the Internet permission allows `inet` with the host's network, so the installed app should too, but that is still to check.
- **Package size.** Ktor's curl engine carries libcurl, nghttp2 and OpenSSL statically; the UNA library is 17 MB. Strip with `--strip-unneeded`, never a plain `strip`, which removes the exported symbols.
- **A stale library.** `sfdk` builds from a copy of the directory, and a binary built against an older generated header crashes before any QML loads. `build.sh` always relinks and copies the library, and a change to the shared public API needs a clean build.
- **Qt 5.6.**
  - No `padding` or `topPadding`.
  - `import` is a reserved word in its JavaScript.
  - QML is not compiled, so `build.sh` runs `qmllint` and the QML load tests run in the build engine.
