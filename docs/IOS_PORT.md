# iOS port

The iOS app is SwiftUI on top of the shared Kotlin module. The rides, the trainer control, the physics, the workouts, the history database and FIT export are already Kotlin in `shared/`, and the iOS app runs that same code. Only the UI and the platform glue are written again. It runs on **iPhone and iPad**, and every screen is built for both.

Status as of 2026-09-23: you can ride a structured ERG workout from the bundled library on iPhone and iPad, and the ride is saved to history. Trainer connection is verified on a real trainer. The full workout ride is verified on the demo trainer in the Simulator, on iPhone and on a 13-inch iPad, but hasn't yet been ridden on a real trainer.

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

The Simulator has no Bluetooth. **Use the demo trainer** connects the shared mock trainer instead. Debug builds also accept two launch arguments:

- `-demo-trainer` connects the demo trainer and starts it pedalling.
- `-demo-ride [workout id]` does the same and opens the ride on that workout, or on the first workout if no id follows. `-demo-ride sample-test` runs a 30-second ride, which is the quickest way to reach the completion and save path.

```sh
xcrun simctl launch booted app.indoorbike.debug -demo-ride sample-test
```

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
- [x] **2. Ride a workout.**
  - **Storage:** `IosActiveSessionStore`, `IosActiveTelemetryStore` and `IosRouteProgressStore`, using the Android file names and behaviour, in Application Support.
  - **Settings:** `IosUserSettings` in `NSUserDefaults`, with the Android key names and limits. Secrets stay in memory until the Keychain arrives in milestone 7.
  - **Container:** `IosAppContainer` wires the managers as Android's `AppContainer` does. It reconnects the paired trainer and heart rate monitor at launch and scans only while the app is in front or a ride is running.
  - **Saving:** the ride write moved into `shared` as `writeRideToHistory`, and Android's `saveRideToHistory` now calls it.
  - **Screens:**
    - The bundled library, from the same `workouts_sample.json` Android ships, in a split view.
    - A workout detail with TSS, IF, kJ and intervals.
    - A rider sheet for FTP and weight.
    - The ride: pedal to start, a countdown, power in its zone colour against the target, cadence, heart rate, time left in the interval, what comes next, the workout profile with progress, difficulty ±5 %, pause and resume, skipping an interval, ending the ride, and a completion card once the ride is saved. The ride screen keeps the display awake and scales its numbers up on an iPad.
  - **Background:** `bluetooth-central` is declared in `Info.plist`.
- [ ] **3. History and progression.** Ride list, ride detail, streaks, levels and badges (already computed in `shared/progression`), and FIT export through the share sheet. Also the unfinished-ride banner. The container already loads the persisted snapshot at launch, but nothing on iOS yet offers to save or discard it. Until then, starting a new workout discards it, as it does on Android.
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
