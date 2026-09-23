# iOS port

The iOS app is SwiftUI on top of the shared Kotlin module. The rides, the trainer control, the physics, the workouts, the history database and FIT export are already Kotlin in `shared/`, and the iOS app runs that same code. Only the UI and the platform glue are written again. It runs on **iPhone and iPad**, and every screen is built for both.

Status as of 2026-09-23: the app connects to a real smart trainer on an iPhone and shows live power, cadence and heart rate. You can't ride a workout on iOS yet.

## Building and running

Requirements: Xcode 26 or later, and JDK 17 on the `PATH` (Xcode's build step runs Gradle).

```sh
open mobile_clients/ios/IndoorBike.xcodeproj
```

Pick the **IndoorBike** scheme and a simulator or a connected device, then press Run. Xcode's first build step runs `./gradlew :shared:embedAndSignAppleFrameworkForXcode`, which builds the Kotlin framework for the selected SDK and configuration, so the first build takes a few minutes.

From the command line:

```sh
# Simulator
xcodebuild -project mobile_clients/ios/IndoorBike.xcodeproj -scheme IndoorBike \
  -destination 'platform=iOS Simulator,name=iPhone 17' build

# Device: pass a development team you belong to (it is not stored in the project)
xcodebuild -project mobile_clients/ios/IndoorBike.xcodeproj -scheme IndoorBike \
  -destination 'platform=iOS,id=<device id>' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=<team id> build
```

The Simulator has no Bluetooth. **Use the demo trainer** connects the shared mock trainer instead. In Debug builds, launching with the `-demo-trainer` argument connects it and starts it pedalling.

The Debug bundle id is `app.indoorbike.debug` and Release is `app.indoorbike`, so a development build installs alongside a release one, as on Android.

To run the shared tests on iOS:

```sh
./gradlew :shared:iosSimulatorArm64Test
```

## How it fits together

| Piece | Where | Notes |
|---|---|---|
| Kotlin framework | `shared`, target `iosArm64` / `iosSimulatorArm64` / `iosX64` | Static framework `shared`, linked with `-framework shared -lsqlite3` |
| iOS platform code in Kotlin | `shared/src/iosMain` | `actual`s, file-backed stores, settings, the app container |
| Flow bridge | `shared/src/iosMain/.../ios/FlowWatcher.kt` | Swift observes a `StateFlow` with a callback on the main thread; no Gradle plugin needed |
| App | `mobile_clients/ios/IndoorBike` | SwiftUI. The Xcode project uses a synchronized folder, so a new `.swift` file there is built with no project edit |

Swift wraps each shared manager in a small `@Observable` model that copies the manager's flows into properties (`TrainerModel` is the pattern). The models forward taps and don't keep ride logic of their own. Logic the Android app keeps outside `shared/` moves into `shared/` when iOS first needs it, and the Android app then calls the shared version, so nothing is written twice.

## Plan

Each milestone ends with the app working on a real device, on iPhone and iPad.

- [x] **0. Shared builds for iOS.** Common code fixed for Kotlin/Native: Kable's device `Identifier` is a `Uuid` on Apple, so `Advertisement.deviceId` returns it as a String, and a JVM-only `putIfAbsent` is replaced with `getOrPut`. The shared tests compile on iOS (Kotlin/Native doesn't allow commas in test names). `secureRandomBytes` uses `arc4random_buf` and `hmacSha256` uses CommonCrypto.
- [x] **1. Trainer.** Scan, connect, disconnect, demo trainer, and live power, cadence and heart rate. Verified against a real trainer on an iPhone 13 mini.
- [ ] **2. Ride a workout.** iOS stores for the active session, telemetry log and route progress. Settings in `NSUserDefaults`. An iOS app container matching Android's `AppContainer`. The bundled workout library. A ride screen with countdown, power against target, the interval and what comes next, pause and resume, difficulty and ending the ride. Saving to history, with `saveRideToHistory` moved into `shared`. Bluetooth background mode so a locked screen doesn't end the ride, and the screen kept awake while riding.
- [ ] **3. History and progression.** Ride list, ride detail, streaks, levels and badges (already computed in `shared/progression`), and FIT export through the share sheet.
- [ ] **4. Settings and onboarding.** FTP, weight, max HR, bike, and drivetrain. Heart rate monitor pairing. Onboarding.
- [ ] **5. Route rides.** Route catalogue with the bundled routes and GPX import. Map (MapKit), elevation profile, virtual gears. The simulation engine is already shared.
- [ ] **6. Workout library.** Import from Files, a URL or a QR code (VisionKit), the community registry, and favourites. Workout fetching and import move into `shared`.
- [ ] **7. Sync and uploads.** Intervals.icu (OAuth through `ASWebAuthenticationSession`), FitTrackee, and the backup folder (Files, with a security-scoped bookmark). Secrets in the Keychain. The uploaders move into `shared` behind a small HTTP interface.
- [ ] **8. Companion mode.** Companion server on the local network, Bonjour advertising, and the Local Network permission. The UDP beacon needs Apple's multicast entitlement, which Apple has to approve. Without it, companions find the phone through Bonjour only.
- [ ] **9. Zwift controllers.** The ECDH (P-256) key exchange and AES block encryption on iOS (Security and CommonCrypto). Until then those calls throw on iOS, and their 5 tests fail there.
- [ ] **10. Release.** App icon, launch screen, App Store metadata, TestFlight through the Snapp Mobile team.

## Things to know

- **Device ids differ by platform.** A paired trainer's id is its MAC address on Android and a CoreBluetooth UUID on iOS, so a backup restored across platforms won't reconnect the paired trainer automatically.
- **Background.** iOS suspends an app in the background unless a background mode applies. The `bluetooth-central` mode, added in milestone 2, keeps a ride going while the trainer keeps sending data.
- **iPad.** Every screen is laid out for both size classes. The ride screen is designed first for a landscape iPad on a stand, and for a portrait or landscape iPhone on the bars.
