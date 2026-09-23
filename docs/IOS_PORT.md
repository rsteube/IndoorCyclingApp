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
- `-demo-ride route` rides the first route in the library, and `-demo-ride free` starts a free ride.
- `-demo-section companion` opens the Companion screen and its socket; the port is in the diagnostics log ("Companion socket listening on …").
- `-demo-sync-folder` points sync at a folder in the app's own Documents (`sync-test`) and runs a round.
- `-demo-open <link>` opens a link or `indoorbike://` URL as if the system had, without iOS's confirmation.
- `-demo-tab history|progress|settings` opens on that tab, `-demo-section workouts|routes` opens that part of the Ride tab, and `-demo-latest-ride` opens the newest saved ride in History.

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
- [x] **3. History and progression.** The app has four tabs as on Android: Ride, History, Progress and Settings. They show as a tab bar on iPhone and a sidebar on iPad, so the minimum iOS is now 18.
  - **History:** totals, rides grouped by week in the rider's own calendar, and "Ride again" from a ride's context menu.
  - **Ride detail:** duration and average power, NP, IF, TSS, kJ, the estimated distance and speed, power against the plan, and time in zone. It exports FIT or TCX through the share sheet, shares a 1080 × 1080 picture, and deletes the ride.
  - **Progress:** rider level and title, the 14-week streak, and the badge grid with locked badges.
  - **Unfinished-ride banner:** save, continue or abandon, on the Ride, History and Progress tabs.
  - **Moved into `shared` for both apps:** the unfinished-ride rules (`describeUnfinishedRide`), the badge catalogue, level titles and XP formatting (`BADGE_CATALOG`, `riderLevelTitle`, `formatXp`, `sessionKilojoules`), and the export file name (`exportFileName`). Android now calls these.
  - **Not ported yet:** the route panel on a route ride's detail waits for milestone 5, and the upload cards wait for milestone 7.
- [x] **4. Settings and onboarding.**
  - **Settings tab:**
    - Profile: FTP, weight and max HR, saved as they're typed and put back if left invalid.
    - Devices: trainer, heart rate monitor and controller, each with a sheet to pair, disconnect or forget it, and a pairing screen.
    - Bike & gearing: bike weight with presets, and the drivetrain.
    - About.
    - "Report a problem": a Mail draft with the report as the body and as an attachment, or the share sheet on a device without Mail.
  - **Onboarding:** the welcome card and a two-step sheet (FTP and weight, then Bluetooth), followed by the "first ride" card.
  - **Ride screen:** the live heart rate is coloured by zone. A paired controller's buttons, and the screen's ± buttons, step the effort the way the ride understands it.
  - **iOS diagnostics:** a diagnostics log with Android's limits and files (`DiagnosticsLog` in `iosMain`), plus crash logging through Kotlin/Native's unhandled-exception hook.
  - **Moved into `shared` for both apps:**
    - The device status wording and tone (`DeviceStatusText.kt`).
    - The ± step for each ride mode (`adjustEffort`).
    - The bug report layout, subject and address (`BugReportText.kt`).
  - Android's settings, ride screen and bug report call these.
- [x] **5. Route rides, and Just ride.**
  - **Ride tab:** Android's "What are you riding?" with Workout, Route and Just ride, as a three-column split view on iPad.
  - **Route library:** the bundled route (Android's `assets/routes`, referenced in place); routes added from community repositories via the registry; and GPX files imported from Files. Routes can be removed, and the rider's saved progress offers "Continue" or "From the start".
  - **Route ride HUD:**
    - MapKit, following the rider or showing the whole route.
    - The gradient, over the next 500 m of road.
    - The virtual gear with its shifter and the cadence it asks for.
    - Power, cadence, heart rate, the elevation profile with position, and speed, distance to go and climbing.
  - **Just ride:** power against a target that moves ±10 W.
  - **Ride detail:** a route ride shows its road, what was ridden, and the climbing.
  - **iOS additions in `iosMain`:**
    - `IosHttp`: HTTPS only, no redirects, Android's timeouts and size caps, and SHA-256 for GPX checks.
    - `IosRouteLibrary`: Android's `user_routes/` layout and rules.
    - The registry, cached on disk.
  - **Moved into `shared` for both apps:**
    - `RepoClient`: repository manifests, and workout and route bundles, over each platform's HTTP.
    - `RouteLibrary.kt`: `RouteSource`, `RouteImportMeta`, id sanitising and collision rules, the catalogue merge, and `rideRoute`.
    - `RouteFormat.kt`: gradient, distance and km formatting, and the gradient colour bands.
  - Android's repository manager, route catalogue and importer, ride route and HUD formatting call these.
- [x] **6. Workout library.**
  - **Workout list:** search, filters for duration, favourites and zone, and a star on each row.
  - **Workout page:** favourite, and rename or delete an imported workout.
  - **Get workouts:**
    - Scan the web editor's QR code (VisionKit; needs a real device).
    - Import a file, or paste a link.
    - Browse the community registry and a repository's workouts, with filters and Add.
    - Manage imported workouts.
  - **Repositories:** follow from the registry or by URL, unfollow, and the "N new" status in the Ride sidebar.
  - **Opening from outside:** `indoorbike://import` links and `.json` files from Files or Mail open in the app. An import that would replace a workout asks first.
  - **Moved into `shared` for both apps:**
    - `WorkoutImportCore` and `mergeWorkoutLibrary`: the importer and library, behind a file-store interface.
    - `ImportQuestions`, `WorkoutSource` and `ImportMeta`.
    - `FollowedRepositories`, behind a `TextStore`.
    - `WorkoutLinkParser`, with `gunzip` as an `expect` function (zlib on iOS), tested on both platforms.
  - Android's importer, library and followed repositories now delegate to these.
- [x] **7. Sync and uploads.**
  - **Connections:**
    - Intervals.icu by athlete id and API key.
    - FitTrackee, signed in through the browser (`ASWebAuthenticationSession` and `indoorbike://oauth`, with PKCE), with the sport picker.
    - Auto-upload for either.
    - The credentials go in the Keychain (`KeychainStore`, this device only).
  - **Ride detail:** Upload and Re-upload cards for each connected service, with Android's messages.
  - **Backup & sync:**
    - A folder picked in Files and kept as a security-scoped bookmark.
    - Android's slice files (`indoorbike-device-<id>.json` and `.prev`), with coordinated reads and writes.
    - Device name, sync now, restore, and the paused review with both choices.
    - Sync runs at launch, as on Android.
  - **Verified in the Simulator:** a slice from an Android device ("Pixel 8"), dropped into the folder, merged into the iPhone's history.
  - **Moved into `shared` for both apps:**
    - `IntervalsIcuUploader`, `FitTrackeeUploader`, `RideUploads`, `SyncEngine` and `SyncStorage`.
    - Their platform needs as interfaces: `HttpClient` (with the multipart builder and form encoding), `KeyValueStore`, `SyncWorkoutLibrary`, and `sha256` and `formatLocalMinute` as `expect` functions.
  - Android keeps only `SafSyncStorage` and a small plumbing file (`AndroidSyncPlumbing.kt`).
  - **Not on iOS yet: Intervals.icu sign-in.** Its redirect is `https://indoorbike.app/oauth/intervals`. An iOS app receives that only through Universal Links, which need the Associated Domains entitlement and an `apple-app-site-association` file on indoorbike.app. The OAuth client is also compiled only into Android release builds. Until both are set up, iOS uses the API-key mode, as a build from source does on Android.
- [x] **8. Companion mode.**
  - **Screens:**
    - The Companion screen: address, port, pairing code, whether the phone is discoverable, remembered companions, and "show every reading".
    - The companion ride HUD: the game's name, icon and colours, the road it describes, power and power target, gradient, gear with big Easier and Harder paddles, every reading on request, the game's remote-control pad, a screenshot button, and "Take back control".
    - The between-rides session panel.
    - The pairing prompt.
  - **Moved into `shared` for both apps:** `CompanionHost` itself, as Ktor CIO, which runs on both platforms. Its platform needs came out as seams:
    - `CompanionAdvertiser` and `CompanionBeacon`;
    - `ScreenshotSink` and `BrandSink`;
    - an icon decoder;
    - `localIPv4Addresses()` (`NetworkInterface` on Android, `getifaddrs` on iOS);
    - the common `LockedMap`, `CopyOnWriteList` and Kotlin atomics in place of the JVM's.
  - **Also shared now:** `CompanionPairing`, `CompanionRideRecorder` and `JsonPairedCompanionStore`.
  - **Android keeps:** the NSD advertiser, the UDP beacon, and its file stores.
  - **iOS provides:**
    - Bonjour through `NSNetService`, with Android's TXT records.
    - Stores laid out as Android's.
    - `NSLocalNetworkUsageDescription` and `NSBonjourServices`.
  - **Verified in the Simulator:** a test game, a plain WebSocket client, connected with the pairing code and negotiated 0.10, then started the ride with `setState`. It streamed telemetry, shifted a gear and finished, and the host saved the ride.
  - **Not on iOS: the UDP beacon.** It needs Apple's multicast entitlement (`com.apple.developer.networking.multicast`), which Apple grants on request. Until then, companions find an iPhone through Bonjour or its address and port.
  - **Background:** iOS closes listening sockets in the background, so a companion ride keeps the app in front with the screen awake.
- [x] **9. Zwift controllers.** `ZwiftCrypto` on iOS:
  - The P-256 key pair and ECDH come from the Security framework (`SecKeyCreateRandomKey`, `SecKeyCopyKeyExchangeResult`).
  - The public key goes over the air as the bare 64-byte point, the same as on Android.
  - AES comes from CommonCrypto (`CCCrypt` in ECB mode, one block at a time, under the shared CCM code), and HMAC-SHA256 also comes from CommonCrypto.
  - The 5 AES tests that failed on iOS now pass.
  - New tests: both sides agree on the same 32-byte secret, a new key pair gives a new secret, and a key of the wrong length is refused.
  - Not yet tried with a real Zwift Click or Ride.
- [ ] **10. Release.**
  - [x] **App icon:** the Slate icon, drawn at 1024 px by `mobile_clients/ios/scripts/render_icon.swift` from the shapes in `slate-design-system/logo`.
  - [x] **Launch screen:** the icon's slate colour with the rider mark in the middle (`UILaunchScreen` in Info.plist).
  - [ ] App Store metadata.
  - [ ] TestFlight through the Snapp Mobile team.

## Things to know

- **Session tests can time out on iOS under load.** `SessionLifecycleTest` and `FreeRideSessionTest` wait in real time for the mock trainer. When the machine is busy (for example with both platforms' tests running at once), one of them now and then hangs until the 60-second `runTest` limit. They pass when run alone. The ride engine is unchanged on this branch, so this isn't from the port. Still worth finding out why the wait doesn't hit its own shorter timeout first.

- **Device ids differ by platform.** A paired trainer's id is its MAC address on Android and a CoreBluetooth UUID on iOS, so a backup restored across platforms won't reconnect the paired trainer automatically.
- **Background.** iOS suspends an app in the background unless a background mode applies. The `bluetooth-central` mode, added in milestone 2, keeps a ride going while the trainer keeps sending data.
- **iPad.** Every screen is laid out for both size classes. The ride screen is designed first for a landscape iPad on a stand, and for a portrait or landscape iPhone on the bars.
