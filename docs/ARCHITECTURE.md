# Architecture

A working reference for how Indoor Bike fits together, kept in step with the code. If you're trying to find where to make a change or why something is shaped the way it is, start here. For build / install / test instructions see [CONTRIBUTING.md](../CONTRIBUTING.md); for the project pitch see [README.md](../README.md).

**Current version: 1.1** (`versionCode = 2`, `versionName = "1.1"` in [mobile_clients/android/build.gradle.kts](../mobile_clients/android/build.gradle.kts)).

## Overview

Indoor Bike connects to a smart trainer (Wahoo Kickr Core as the first-class target, any FTMS-compatible trainer in principle) over BLE to perform structured workouts in ERG mode. It's built with Kotlin Multiplatform Mobile so a future iOS port can reuse the core logic.

- **Core logic** - the `shared` module, written in KMM, handles workout parsing, BLE interactions (via [juul/kable](https://github.com/JuulLabs/kable)), session timing, FIT export, and progression math.
- **Android client** - the `mobile_clients/android` module is purely native, built with Jetpack Compose.
- **iOS client** - planned (`mobile_clients/ios`, SwiftUI), not yet started.

## Build variants & install (v1.0)

Two install slots so debug + release coexist on a single device:

- **Release**: `applicationId = app.indoorbike`, label "Indoor Bike", brand-orange launcher icon. Build with `:mobile_clients:android:assembleRelease` (or `installRelease` for debuggable variants).
- **Debug**: `applicationId = app.indoorbike.debug`, `versionName = 1.0-debug`, label "Indoor Bike Debug", **grayscale** launcher icon (resource overlay in [src/debug/res/](../mobile_clients/android/src/debug/res/) - debug-specific `ic_launcher_foreground.xml` swaps brand colors for grays; `colors.xml` overrides `ic_launcher_background`; `strings.xml` overrides the app name). Build with `:mobile_clients:android:installDebug`.

Each install has its own DataStore + SQLDelight DB, so they don't share settings or history. Both fully usable, ride to ride.

## Rules & Constraints
- **F-Droid Release**: The app must not include any proprietary Google services or trackers. This means no Google Play Services (e.g., no Firebase Analytics or Crashlytics, no Google Maps). Open-source alternatives should be preferred.
- **Authentication**: Auth0 will be used, but it must be completely optional. The app must function fully locally and allow structured workout execution and progression tracking without logging in.
- **Progression System**: Initially local only. Later sync capability will be added via CleverCloud, ensuring it remains fully functional locally when offline or not logged in.
- **UI System**: A clean, modern, and engaging user interface. Dark theme by default, rich visuals for power zones and progression tracking. Avoid generic styles. 

## Workout Models (JSON / Shared KMM)
Structured workouts use Power Zones based on percentages of Functional Threshold Power (FTP). 
- Models: `PowerZone` and `Workout` in `app.indoorbike.shared`.

## BLE Connection Structure
Implemented in `shared/.../ble/TrainerManager.kt` using **juul/kable** (KMM-friendly, F-Droid clean). The manager is a regular `class` (not an `object`); a single instance is owned by `AppContainer`.

- **Cycling Power Service** (`0x1818`) → Cycling Power Measurement (`0x2A63`) is the primary source for instantaneous power and crank-revolution-derived cadence.
- **Fitness Machine Service** (`0x1826`) → Indoor Bike Data (`0x2AD2`) is a fallback for power/cadence on FTMS-only trainers; FTMS Control Point (`0x2AD9`) is used to write target power in ERG mode (opcode 0x05, sint16-LE watts; opcode 0x00 requests control on connect). Indications on the Control Point are enabled (via `observe`) before any command is written, because the spec requires it and strict trainers such as the Zwift Hub silently reject control writes otherwise.
- **Heart Rate Service** (`0x180D`) → Heart Rate Measurement (`0x2A37`) is subscribed if the peripheral exposes it.
- The UI consumes a domain `TrainerCandidate(id, name, rssi)` rather than `com.juul.kable.Advertisement`, so swapping BLE libraries doesn't ripple into the UI layer.
- `peripheral.state` listener and per-characteristic `observe` jobs are explicitly tracked and cancelled across reconnects (no leaks).
- Zwift Click v1 controller integration lives in [`shared/.../ble/ControllerManager.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/ble/ControllerManager.kt) - see "Rider Controller" section below. The in-app `±difficulty` stepper in `RideScreen` is the always-available fallback and remains the only path when no controller is paired.

## Development Workflow
When making changes, always ensure that KMM logic stays in the `shared` module and that UI additions in the Android client utilize the `app.indoorbike` namespace. Use Material 3 in Jetpack Compose, maintaining a high-fidelity dark aesthetic.

## Runtime Architecture (Android)

### `IndoorBikeApp` and `AppContainer`
- `IndoorBikeApp : Application` is declared in the manifest (`android:name=".IndoorBikeApp"`) and constructs an [`AppContainer`](../mobile_clients/android/src/main/kotlin/app/indoorbike/IndoorBikeApp.kt) once.
- The container owns the long-lived singletons: `TrainerManager`, `WorkoutSessionManager`, `HistoryRepository`, `UserSettings`, `ActiveSessionStore`. Each has a `dispose()` that cancels its `SupervisorJob + Dispatchers.Default` scope.
- Composables read the container via `LocalAppContainer.current`. `MainActivity.setContent { CompositionLocalProvider(LocalAppContainer provides container) { … } }` installs it.
- This replaces the previous `object`-singleton pattern. Tests instantiate the same classes directly with fakes.

### Persistence
- **SQLite via SqlDelight**: `HistoryDatabase` (`schema in shared/.../db/History.sq`). Foreign keys are enabled in the Android driver via `setForeignKeyConstraintsEnabled(true)`; `WorkoutTelemetry` cascades on session delete.
- **DataStore Preferences** for `UserSettings` (FTP, paired trainer id) - replaces the legacy `SharedPreferences` singleton.
- **Active-session snapshot** (`ActiveSessionStore`): a small JSON file in `filesDir` written every 5 s during an active ride. Atomic write via tmp + rename. Used for process-death recovery (the snapshot is cleared on `stopWorkout()` / `completeWorkout()`).

### Foreground Service for active rides
[`RideSessionService`](../mobile_clients/android/src/main/kotlin/app/indoorbike/service/RideSessionService.kt) runs while a workout is active. It:
- Posts an ongoing notification with current target watts and elapsed time (channel `ride_session`, importance LOW).
- Holds a `PARTIAL_WAKE_LOCK` (capped at 4 hours) so BLE notifications and the wall-clock timer don't get suspended when the screen sleeps.
- Declares `foregroundServiceType="connectedDevice"` (manifest); `FOREGROUND_SERVICE_CONNECTED_DEVICE` and `POST_NOTIFICATIONS` permissions are declared.
- Started/stopped via a `DisposableEffect(Unit)` in `RideScreen` so it's tightly coupled to the ride being on screen.
- **Android 14+ caveat**: starting a `connectedDevice` FGS requires at least one runtime-granted `BLUETOOTH_*` permission. `RideScreen` requests `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` (or `ACCESS_FINE_LOCATION` on API < 31) before starting the service so a brand-new user who reaches Start without ever opening the pairing flow doesn't crash. The service additionally wraps `startForeground` in `try/catch SecurityException` and `stopSelf()`s gracefully - defense in depth.

### Rider Controller (Zwift Click v1)

[`ControllerManager`](../shared/src/commonMain/kotlin/app/indoorbike/shared/ble/ControllerManager.kt) is a third optional BLE peripheral, parallel to `TrainerManager` and `HeartRateMonitor`. It surfaces a `SharedFlow<ControllerEvent>` of `Plus` / `Minus` press-edges; `RideScreen` collects this and calls `WorkoutSessionManager.adjustDifficulty(±5)` so the controller hits the same code path as the in-app stepper.

- **Service / characteristics** (Zwift custom GATT, unencrypted on Click v1):
  - Service `0x00000001-19CA-4651-86E5-FA29DCDD09D1`
  - Async (notify) `0x00000002-…` - keypad notifications arrive here.
  - Sync RX (write) `0x00000003-…` - host writes the literal bytes `"RideOn"` (`52 69 64 65 4F 6E`) to open the pipe.
  - Sync TX (indicate) `0x00000004-…` - handshake reply.
- **Wire format**: each keypad notification starts with byte `0x37`, followed by a tiny protobuf with two varint fields. Inverse logic: 0 = pressed, 1 = released. Field 1 = Plus, field 2 = Minus. The pure parser (`parseClickKeypadStatus`) lives in the same file and is unit-tested under [shared/src/commonTest/.../ble/ClickKeypadParserTest.kt](../shared/src/commonTest/kotlin/app/indoorbike/shared/ble/ClickKeypadParserTest.kt).
- **Press edge detection**: only `released → pressed` transitions emit on `events`; holding does *not* auto-repeat. Acquiring auto-repeat is left to the consumer if/when needed.
- **Scan filter**: unfiltered scan + name-keyword post-filter (`Zwift Click` / `Click` / `BC1` / `Zwift Play` / `Zwift Ride`), same approach as the HRM. Encrypted Play / Ride controllers advertise via the same custom service so they appear in the list, but the encrypted handshake (ECDH + AES-CCM) is not yet implemented - connecting to a Play / Ride will surface a connection-error.
- **Auto-reconnect** on app launch via `autoConnectIfPaired(pairedControllerId)`; the paired id persists in `UserSettings.pairedControllerId` (DataStore key `paired_controller_id`).

### Heart Rate Monitor (separate from trainer)

[`HeartRateMonitor`](../shared/src/commonMain/kotlin/app/indoorbike/shared/ble/HeartRateMonitor.kt) is an optional independent BLE peripheral, parallel to `TrainerManager`. Lives in `AppContainer`. Lets users ride with just a trainer, just an HRM, or both - `WorkoutSessionManager.effectiveHeartRate` prefers the dedicated HRM if it's emitting samples, otherwise falls back to whatever the trainer reports.

- **Scan filter quirk**: scans **without** a service-UUID filter and post-filters by either (a) the 0x180D Heart Rate Service appearing in the advertisement payload, or (b) the device name matching one of `HRM`, `Polar`, `TICKR`, `Coros`, `Suunto`, `Magene`, `Verity`, `Heart`. Garmin HRM-Pro / HRM-Pro Plus omit 0x180D from their advertisement (they only expose it after GATT connect), so a service-UUID filter would never match them.
- Auto-reconnect on app launch via `autoConnectIfPaired(pairedHrmId)`.

### First-run onboarding (soft, non-gating)

Implemented inside [`WorkoutListScreen.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/WorkoutListScreen.kt):

- A **welcome banner** is the top item on the Rides tab when `userSettings.hasCompletedOnboarding == false`. Tapping **Get Started** opens a Material 3 `ModalBottomSheet` driven by an internal `OnboardingStep` enum with two steps:
  - **Step 1 · Profile** - FTP + Weight inputs. The FTP row has a brand-colored "Don't know your FTP? Start at 150W" link that fills the field with the shared constant `UNKNOWN_FTP_DEFAULT` (150). **Continue** validates against `MIN_FTP..MAX_FTP` / `MIN_WEIGHT_KG..MAX_WEIGHT_KG` and persists the values immediately, then advances to step 2 - but **does not** mark onboarding complete yet (so a swipe-down mid-flow re-prompts on next launch via the welcome banner is acceptable, but a properly-finished step 1 doesn't lose the FTP/weight if the user backs out of step 2).
  - **Step 2 · Permissions** - pre-flight rationale for `BLUETOOTH_CONNECT/SCAN` (or `ACCESS_FINE_LOCATION` on API < 31) and `POST_NOTIFICATIONS` on API ≥ 33. Each permission is shown as an icon row explaining what it's used for ("Find and pair your smart trainer …", "Show the active-ride strip so Android keeps the app alive while you ride."). **Allow access** launches the multi-permission system dialog via `ActivityResultContracts.RequestMultiplePermissions`; the outcome doesn't block onboarding - `RideScreen` re-prompts on first ride if the user denied or skipped, so the onboarding step is purely a UX improvement (a contextual rationale before the system prompt is the Android UX guidelines' recommended pattern).
  - Sheet dismissal at any step (swipe-down, scrim tap, system back) marks `hasCompletedOnboarding = true` so the user isn't pestered next launch.
- After onboarding, the welcome banner is replaced by the existing **Pair a trainer** nudge (until any trainer is paired, real or mock), which is replaced in turn by `TrainerStatusStrip`.
- A **"Start here" pinned card** (orange-bordered, eyebrow `START HERE · YOUR FIRST RIDE`) is inserted as the first list item when `hasCompletedOnboarding && historyRepository.getAllSessions().isEmpty() && starter != null`. The starter workout is identified by the constant `STARTER_WORKOUT_ID = "starter-15min"` (defined in [workouts_sample.json](../mobile_clients/android/src/main/assets/workouts_sample.json) - 15 min mixed-zone intro). The card disappears after the first completed ride. While pinned, the same workout is filtered out of the regular list to avoid duplication.

### Trainer-required gating

[`WorkoutDetailScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/WorkoutDetailScreen.kt) observes `trainerManager.connectionState` + `isMockTrainer`. The bottom CTA is state-aware:

- **Connected** (real or mock) → primary "Start workout" button (existing behaviour).
- **Not connected** → primary "Pair a trainer to ride" button + small subtitle, taps route to the trainer pairing screen via `onNavigateToPairing`. Mock counts as connected.

This is the only gating on Start; users can still browse workout details without a trainer.

### Workout session timing
`WorkoutSessionManager` drives elapsed/segment progress from **wall-clock time** (`elapsed = (now - activePhaseStartEpochMs) / 1000`) rather than counting `delay(1000)` ticks, so the ride doesn't drift over a 60+ minute session if the dispatcher hiccups. The phase anchor is reset on every pause/resume/skip.

## Android UI Architecture
The Android client implements the **Cadence design system** (see [cadence-design-system/README.md](../cadence-design-system/README.md)). Code is organized under `app.indoorbike.ui.*`:

- `ui/theme/` - design tokens. `CadenceColors` (gravel-warm dark palette + brand ember + line/fg ramps), `CadenceType` (display/body/mono scale), `Spacing`, `Radii`, and `CadenceTheme` (M3 ColorScheme + Typography wrapper).
- `ui/zones/PowerZones.kt` - 7-zone enum mapping FTP% → `PowerZone` with calibrated cool→hot color spectrum. Use `PowerZone.fromFtpPercent()` whenever an FTP% needs a zone color.
- `ui/components/Primitives.kt` - reusable building blocks: `AppBar`, `IconBtn`, `PrimaryBtn`, `SecondaryBtn`, `ZoneBadge`, `MetaPill`, `ZoneBar`, `TabBar`, `TrainerStatusStrip`, `FilterChip`.
- Planned-ride summary stats (`shared/.../workout/WorkoutSummary.kt` - IF, TSS, kJ, NP, dominant tag) live in shared so they're testable and reusable across platforms. `ui/workout/WorkoutSummaryUi.kt` adds the Android-only `zoneSegments()` extension that maps to UI `ZoneSegment` (color-aware).
- `ui/screens/` - `WorkoutListScreen`, `WorkoutDetailScreen`, `RideScreen` (live HUD), `WorkoutOverviewScreen` (post-ride). The list screen derives filter chips from the loaded workouts (no hardcoded categories); the detail screen truncates the step list to 6 with a "tap to expand" pill; the overview deletes a session via the `HistoryRepository.deleteSession()` cascade.
- `ui/HistoryTab.kt` - week-grouped session list with totals card (rides / time / kJ) at the top. Empty state shown when no sessions exist.
- `ui/ProgressTab.kt` - local progression: streak hero (current days + longest + 14-day mini-grid), rider-level meter with kJ thresholds, badge grid. Computes everything from `HistoryRepository.getAllSessions()` via the shared `progression/Progression.kt` helper. No backend, no account.
- `ui/SettingsTab.kt` - three grouped sections: **Profile** (FTP + Weight rows, commit on focus loss / IME-Done, validated against `MIN/MAX_FTP` and `MIN/MAX_WEIGHT_KG`), **Devices** (single grouped card with Trainer + HRM + Controller rows; tapping a row opens a Material 3 `ModalBottomSheet` for that device with state-aware Pair / Disconnect / Forget actions), **About** (version, build type, license). The bottom-sheet pattern keeps the destructive `Forget` action out of the always-visible scroll.
- `ui/TrainerPairingScreen.kt` - BLE scan screen with live "Scanning · N found" indicator and an Open-Settings escape hatch when permissions are denied. Includes a "Use Mock Trainer" secondary button for users without hardware.
- `ui/HrmPairingScreen.kt` - parallel to the trainer pairing screen; uses `HeartRateMonitor` and the name-heuristic-based filter (so Garmin HRM-Pro shows up).
- `ui/ControllerPairingScreen.kt` - parallel to the HRM pairing screen; uses `ControllerManager`. Click v1 only wakes when a button is pressed, so the empty-state copy explicitly tells the user to press a button before scanning.
- `ui/HistoryTab.kt` / `ui/ProgressTab.kt` empty states each show a primary "Pick a workout" button that calls `onNavigateToRides` - wired in [`MainActivity.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/MainActivity.kt) to `navigator.navigate(TabRides)`.
- `MainActivity.kt` - loads `workouts*.json` from assets, holds a `Navigator` over `androidx.navigation3` back stacks. Each top-level tab keeps its own back stack; the bottom `TabBar` only renders when on a top-level route at depth 1.

### Shared (KMM) helpers

- `shared/src/commonMain/kotlin/app/indoorbike/shared/progression/Progression.kt` - pure functions for streak / rider-level / badge / time-in-zone / NP+IF+TSS+kJ computations. All inputs are `WorkoutSession` lists or 1Hz telemetry samples; no UI dependencies. Reused by Android `ProgressTab` and `WorkoutOverviewScreen`.
- `shared/src/commonMain/kotlin/app/indoorbike/shared/session/WorkoutSessionManager.kt` - also exposes `targetBiasPercent` (for Zwift Click-style ±difficulty fallback in the UI), `adjustDifficulty(deltaPercent)`, and `skipSegment()`. Bias is applied multiplicatively when computing trainer target watts.

### Tests

Two layers, both documented in [TESTING.md](../TESTING.md):

**Shared-module unit tests** - pure logic, no Android dependencies:
- `shared/src/commonTest/kotlin/.../progression/ProgressionTest.kt` - streak (with TZ offset), rider level, time-in-zone, RideStats (NP/IF/TSS), badges.
- `shared/src/commonTest/kotlin/.../workout/WorkoutSummaryTest.kt` - tag inference, NP=IF×FTP, formatClock HH:MM:SS edge, formatDurationPretty.
- Run via `.\gradlew.bat :shared:allTests` (PowerShell) or `./gradlew :shared:testDebugUnitTest`.

**Smoke test** ([scripts/smoke_test.ps1](../scripts/smoke_test.ps1)) - installs the debug APK, clears data, walks through onboarding → mock-trainer pair → starter ride, captures a screenshot at each step into `build/test-artifacts/<timestamp>/`, and greps logcat for `FATAL` / `AndroidRuntime: FATAL`. Uses `uiautomator dump` + `input tap` (`Tap-Text` helper) to hit buttons by label, so renaming a CTA breaks the script - keep label strings in sync. Run with `.\scripts\smoke_test.ps1` (single connected device) or `.\scripts\smoke_test.ps1 -DeviceId <serial>`.

**Manual test plan** - see [TESTING.md](../TESTING.md) for the full checklist organised by feature area (onboarding, pairing, ride, history, progress, side-by-side install, etc.). Use this for anything the smoke test can't assert (BLE, label correctness, layout).

### Ride share image

A 1080×1080 PNG generated on-demand from a completed ride and surfaced via a Share button on the AppBar of [`WorkoutOverviewScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/WorkoutOverviewScreen.kt). Lives under `app.indoorbike.share`:

- [`RideShareCard.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/share/RideShareCard.kt) - the Composable. Header (date eyebrow + workout name in Bebas Neue caps), three KPI tiles (Duration / Avg Power in ember / TSS), a power-chart panel mirroring the on-screen "Power Adherence" chart (planned-zone bars + actual-power line in `Fg1`), a "TOUGHEST INTERVAL" callout (longest Z4+ segment by default, falls back to peak-power for unstructured rides), and an `INDOOR BIKE` wordmark + `indoorbike.app` footer. Pure Compose, no SVG assets - the brand mark and wordmark are drawn from primitives.
- [`ShareImageGenerator.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/share/ShareImageGenerator.kt) - capture + persist + share helpers. `captureLayer(GraphicsLayer)` returns an Android `Bitmap` from `GraphicsLayer.toImageBitmap()`. `saveToGallery()` writes to `MediaStore.Images` with `RELATIVE_PATH = Pictures/Indoor Bike` on Q+, falling back to `Environment.DIRECTORY_PICTURES + /Indoor Bike` + the legacy `MediaStore.Images.Media.DATA` column on 26..28. `stageForShare()` writes to `cacheDir/shared_images/` and returns a `content://` URI via `FileProvider`. `launchShareChooser()` builds an `ACTION_SEND` intent with `image/png`, `EXTRA_STREAM`, `ClipData`, and `FLAG_GRANT_READ_URI_PERMISSION`, wrapped in `Intent.createChooser`.
- [`ShareSheet.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/share/ShareSheet.kt) - Material 3 `ModalBottomSheet` with a thumbnail preview and Save / Share buttons. The card is rendered once inside a `CompositionLocalProvider(LocalDensity provides Density(1f, 1f))` so dp/sp/px are 1:1 in the captured bitmap (1080dp × 1080dp = 1080px × 1080px regardless of device density). The visible thumbnail is the same composable visually scaled via `Modifier.graphicsLayer(scaleX, scaleY)` - the GraphicsLayer always records the unscaled drawing.

**Permissions / manifest:**
- `WRITE_EXTERNAL_STORAGE` is declared with `android:maxSdkVersion="28"` ([AndroidManifest.xml](../mobile_clients/android/src/main/AndroidManifest.xml)). Q+ uses scoped storage and skips the runtime prompt; pre-Q the share sheet requests it via `ActivityResultContracts.RequestPermission` before the first save.
- `androidx.core.content.FileProvider` is registered with authority `${applicationId}.fileprovider` and `xml/file_paths.xml` exposing `cache-path/shared_images`. The cache directory is auto-cleaned by Android when storage is low, so staged share files are ephemeral by design.

**F-Droid hygiene:** no third-party deps. Bitmap rendering uses `androidx.compose.ui:ui` (Compose 1.7+ `GraphicsLayer.toImageBitmap`), persistence uses `MediaStore` + AndroidX `FileProvider`. No analytics, no Firebase Dynamic Links, no network share endpoints - the user picks the destination via the system chooser.

### Workout library - sources, import, repositories

The Rides list aggregates workouts from two local sources, both merged through [`WorkoutLibrary`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/WorkoutLibrary.kt) (`AppContainer.workoutLibrary`):

1. **Bundled** - JSON files in `assets/workouts*.json`. Loaded once at startup via the same shared `WorkoutRepository.parseWorkouts()` parser as before.
2. **Imported** - JSON files persisted to `filesDir/user_workouts/<sanitised-id>.json` by [`WorkoutImporter`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/WorkoutImporter.kt). Atomic write via `tmp` + rename. Filename uses the workout's own id so re-importing is idempotent. Sources include the file picker, share-target, QR scan, deep-link, **and** the in-app community-repo browser - when a rider taps Add on a workout in a remote repo, [`WorkoutImporter.saveWorkoutFromRepo`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/WorkoutImporter.kt) writes the JSON plus a `<id>.meta.json` sidecar carrying the source repo's id + display name for attribution. The local copy is the source of truth from then on; it isn't auto-updated when the upstream repo changes.

Duplicate workout ids are de-duped first-wins with priority **Imported > Bundled** - an explicit user import wins over a bundled copy with the same id.

**Source attribution** - `WorkoutLibrary.entries` exposes a `StateFlow<List<WorkoutEntry>>` where each entry carries a `WorkoutSource` (`Bundled` / `Imported(sourceRepoId, sourceRepoName)`). The Rides list tag-eyebrow row appends "· IMPORTED" or the repo's display name in ember accent for imported workouts; bundled stay clean.

[`RepositoryManager`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/RepositoryManager.kt) keeps a small registry cache at `filesDir/registry/cache.json` (the community list shown on the More Workouts screen) and provides an on-demand `fetchRepoWorkouts(manifestUrl)` that downloads a manifest plus its bundles in memory for the remote-repo browser. **Nothing else is persisted** - earlier versions kept per-repo bundles under `filesDir/repos/<id>/...` for incremental sync, but that on-disk mirror was removed when the explicit-add model replaced subscriptions.

**On-disk schemas** (defined in [`shared/.../repo/Manifests.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/repo/Manifests.kt)):

- `RepositoryManifest` (schemaVersion **2**, current) - `id`, `name`, `description?`, `author?`, `homepage?`, `updatedEpoch?`, **`bundles: List<WorkoutBundleEntry>`**. The manifest itself contains no segments; that's the whole point of v2.
- `WorkoutBundleEntry` - `id`, `name?`, `schemaVersion` (bundle file format, currently 1 = bare JSON array of `Workout` objects), `version` (content version - bumped on any change to any workout in the bundle), `url` (absolute or relative to the manifest URL).
- `Workout` (schemaVersion **1**) - `schemaVersion: Int = 1`, `id`, `name`, `description?`, `screenshot?`, `url?`, `segments: List<PowerZone>`. Workouts whose `schemaVersion` exceeds `WORKOUT_SCHEMA_VERSION_MAX_SUPPORTED` are silently skipped at parse time so future formats can land in an existing bundle without breaking older clients.
- `RegistryIndex` - `repositories: List<RegistryEntry>` where each entry has `id`, `name`, `description?`, `author?`, `manifestUrl`, `tags`.

**Schema version v1 (legacy, no longer accepted)**: workouts were embedded inline in the manifest. `parseRepositoryManifest` throws on any `schemaVersion > REPO_MANIFEST_SCHEMA_VERSION_MAX_SUPPORTED` - pre-launch break, no migration path, repos must move to v2.

`ManifestParser.classify(text)` heuristically identifies whether arbitrary JSON is a single workout, list of workouts (= a bundle), repository manifest (top-level `bundles` key), or registry index - used by the universal import pipeline so the user doesn't pick a mode up front.

**Community registry URL** - hardcoded `BuildConfig.REGISTRY_URL = "https://indoorbike.app/registry.json"` ([app/build.gradle.kts](../mobile_clients/android/build.gradle.kts)). Absence is graceful: `RepositoryManager.refreshRegistry()` returns `RegistryState.Failed(error, cachedIndex)` and the Library's "Browse community" section shows an empty hint pointing at the Scan QR / Add by URL escape hatches. When the URL goes live, the next refresh picks it up automatically - no app update needed.

**Networking** - [`HttpFetcher`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/HttpFetcher.kt) is a thin `HttpURLConnection` wrapper. F-Droid clean (no OkHttp / Ktor dep), HTTPS-only (rejects `http://`), 12s connect/read timeouts, 2 MB body cap so a malicious URL can't OOM the device.

### Import entry points (4 paths, 1 pipeline)

All four route through `WorkoutImporter.importJsonText()` → `ImportResult` (`Workouts` | `Manifest` | `Failure`):

1. **In-app file picker** - `LibraryScreen` → `ActivityResultContracts.OpenDocument(arrayOf("application/json", "*/*"))`.
2. **Share-target** - manifest intent-filter on `MainActivity` accepts `ACTION_SEND` with `application/json`. Any app's share sheet (Gmail, Files, Drive, browser) can hand a JSON to Indoor Bike.
3. **File open** - manifest intent-filter on `ACTION_VIEW` with `content://` or `file://` schemes + `application/json` MIME. Tapping a downloaded `.json` from a file manager opens it in Indoor Bike.
4. **Deep link** - `indoorbike://import?url=<encoded-https-url>` intent-filter. The web editor (or any chat message) can use this to "Send to phone": app fetches the URL, classifies, and routes through the same import pipeline.

`MainActivity.handleImportIntent()` reads `intent.data` / `EXTRA_STREAM`, clears it after consumption (so config-change recreations don't re-trigger), and surfaces results as Toasts. Manifest results aren't auto-added under the explicit-add model - the toast points the rider at More Workouts → Browse so they can pick individual workouts. Single-workout / list payloads import directly.

### Library screen + QR scanner

[`LibraryScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/LibraryScreen.kt) is a single-page surface reachable from a `LibraryBooks` icon in the Rides tab AppBar. Sections:

- **Action row** - three tiles: Import file (file picker), Scan QR (→ `RouteQrScanner`), By URL (modal sheet with HTTPS URL input - manifest URLs route to the remote-repo browser, single workouts / lists run through the importer).
- **Browse community** - registry-driven cards. Tapping a card navigates to the remote-repo browser (`onOpenRepo(manifestUrl)`) where the rider picks individual workouts to add. Loading / empty / unreachable states are distinct.
- **Imported workouts** - flat list of user files with delete; workouts added from a known repo carry the sidecar attribution noted above.

[`QrScannerScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/QrScannerScreen.kt) uses **CameraX** (`camera-camera2` / `camera-lifecycle` / `camera-view`, all 1.4.0, Apache 2.0, F-Droid clean) for the preview and **ZXing core** 3.5.3 (`com.google.zxing:core` - Apache 2.0, *not* a Google product despite the package; pure Java, also F-Droid clean) for decoding. The analyzer feeds `PlanarYUVLuminanceSource` into `MultiFormatReader.decode()` per frame (back-pressure: latest only). On a hit it hops to main, decodes-or-fetches the URL, and routes the result through the same `WorkoutImporter` pipeline.

**URL forms accepted by the QR scanner *and* the `indoorbike://import` deep-link:**

| Form | Resolver |
| --- | --- |
| `https://example.com/whatever.json` | HTTP GET, parse JSON |
| `indoorbike://import?url=<https-url>` | HTTP GET that URL, parse JSON |
| `https://indoorbike.app/share/#wz=<base64-url-safe-gzip-utf8-json>` | Local decode - gzip-then-base64url. Current QR / link form from the website editor. |
| `https://indoorbike.app/share/#w=<base64-url-safe-utf8-json>` | Local decode - legacy uncompressed form, still accepted. |
| `indoorbike://import?dataz=<base64-url-safe-gzip-utf8-json>` | Local decode - gzipped deep-link form for chats / NFC etc. |
| `indoorbike://import?data=<base64-url-safe-utf8-json>` | Local decode - legacy uncompressed deep-link form, still accepted. |

The decoder is [`WorkoutLinkParser.extractEmbeddedJson()`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/WorkoutLinkParser.kt). When it returns non-null, the network fetch is skipped entirely - important because the website's `share/` URL serves HTML to a browser, not JSON, so a naive GET wouldn't work. Decoding uses `android.util.Base64.decode(s, URL_SAFE | NO_PADDING | NO_WRAP)` to match the JS encoder in [`website/editor/editor.js`](website/editor/editor.js); gzipped forms are then inflated via `java.util.zip.GZIPInputStream`. Compression cuts a typical 1 KB workout URL roughly in half - enough to drop the QR several versions and make modules chunky enough for phone cameras to scan reliably.

**Permissions** - `CAMERA` declared in manifest with `<uses-feature android:required="false">` so devices without a camera (rare but possible) don't get filtered out of the Play / F-Droid index. Runtime prompt is requested on screen entry; "Not now" leaves the screen with a polite no-permission state.

### Editor URL workout API (third-party deep links)

The browser editor accepts a **readable query-param API** so a third-party site (e.g. VertMatch) can generate a workout and open the editor with it pre-loaded - no backend, no SDK, no base64. Example: `https://indoorbike.app/editor/?name=Threshold+3x8&ftp=250&s=10m@50,3x(8m@100;4m@55),5m@50`. The user then uses the existing **Show QR & link** / **Share to phone** path to import it into the app, so this is a pure front-of-funnel feature with no new device-side code.

- Parser lives in [`website/editor/editor.js`](../website/editor/editor.js) (`readWorkoutFromQuery` + `parseSegmentsDSL`), wired into `init()` ahead of the binary `#wz=`/`#w=` hash path (query wins if both present).
- The `s=` DSL is a comma-separated list of `DURATION@POWER` segments with repeat groups `Nx(seg;seg)` and shorthand `NxSEG`; durations take `s`/`m` units. Segments map to the same `{lengthInSeconds, powerPercentFTP, intervalType:"CONSTANT"}` model - no per-segment labels in v1 because the editor stores duration+power only.
- **Two docs, cross-linked:** human-readable [docs/EDITOR_URL_API.md](EDITOR_URL_API.md), and a machine/LLM spec served at a stable URL - [`website/editor/llms.txt`](../website/editor/llms.txt) → `https://indoorbike.app/editor/llms.txt`. A third party can point an LLM at the `llms.txt` URL to emit valid links.

### Activity export (FIT / TCX) + Intervals.icu cloud sync

Two ways a finished ride leaves the device, both surfaced on [`WorkoutOverviewScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/WorkoutOverviewScreen.kt): a **manual file export** (FIT or TCX, via the OS share sheet) and the optional **Intervals.icu upload**. Both reuse the same in-shared encoders; no third-party activity-format library.

**Manual file export.** An `Export file` card (shown whenever the ride has telemetry) opens an [`ExportSheet`](../mobile_clients/android/src/main/kotlin/app/indoorbike/share/ExportSheet.kt) that lets the rider pick **FIT** or **TCX**. [`ActivityExporter`](../mobile_clients/android/src/main/kotlin/app/indoorbike/share/ActivityExporter.kt) encodes the bytes off the main thread, stages them in `cacheDir/shared_files/` (a second `<cache-path>` in [`file_paths.xml`](../mobile_clients/android/src/main/res/xml/file_paths.xml)), and hands a `FileProvider` `content://` URI to an `ACTION_SEND` chooser - the same staging pattern as the ride-share image. Indoor rides carry no GPS, so GPX is intentionally not offered: FIT and TCX both express time + power + HR + cadence without a position track and cover Strava / Garmin Connect / intervals.icu / TrainingPeaks.

**TCX writer - hand-rolled in shared, no third-party dep.** [`TcxBuilder`](../shared/src/commonMain/kotlin/app/indoorbike/shared/activity/TcxBuilder.kt) emits Garmin TrainingCenterDatabase v2 XML: `Sport="Biking"`, one `<Lap>` per workout segment (or a single whole-ride lap), one `<Trackpoint>` per 1Hz sample with `<HeartRateBpm>` (omitted when no HRM), `<Cadence>`, and power via the `ns3:TPX/Watts` ActivityExtension. Timestamps are ISO-8601 UTC from a pure-Kotlin `isoUtc()` (Hinnant civil-from-days) since commonMain has no `kotlinx-datetime`. Covered by [`TcxBuilderTest`](../shared/src/commonTest/kotlin/app/indoorbike/shared/activity/TcxBuilderTest.kt) (trackpoint/lap counts, HR omission, ISO formatting of known instants).

End-to-end activity sync to [intervals.icu](https://intervals.icu). Strict opt-in posture - no network call happens unless the user explicitly configures credentials and either taps Upload or turns auto-upload on.

**FIT writer - hand-rolled in shared, no third-party dep.** Lives in [`shared/src/commonMain/kotlin/app/indoorbike/shared/fit/`](../shared/src/commonMain/kotlin/app/indoorbike/shared/fit/):

- [`FitWriter.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/fit/FitWriter.kt) - low-level: 14-byte header (with header CRC), little-endian integer helpers, definition-message builder, data-message header byte, the FIT-spec nibble-table CRC-16, and `finalizeAndCrc()` that patches data-size + header-CRC + appends file-CRC. `FitBaseType`, `FitMessage` (global message numbers), `FIT_EPOCH_OFFSET_SECONDS = 631065600` (UTC 1989-12-31).
- [`RideFitBuilder.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/fit/RideFitBuilder.kt) - high-level `build(session, telemetry, workout, ftp): ByteArray`. Emits file_id (manufacturer = 255 *development*), file_creator, timer-start event, one record per 1Hz telemetry sample (timestamp + power + HR + cadence), one lap per workout segment (interleaved with the records covering that segment), timer-stop event, session message (sport=cycling, sub_sport=indoor_cycling, plus avg/max power/HR/cadence, NP, IF, TSS×10, kJ, FTP), and an activity wrapper. Falls back to a single whole-ride lap when there's no structured workout.
- [`FitWriterTest.kt`](../shared/src/commonTest/kotlin/app/indoorbike/shared/fit/FitWriterTest.kt) - verifies header magic, header-CRC, file-CRC, data-size patching, and round-trips a 30-min synthetic ride. Run via `.\gradlew.bat :shared:allTests`.

We claim manufacturer `255` (the FIT spec's *development* slot) - the honest answer for a non-commercial app, accepted by all major analysis platforms. CRC algorithm is the spec's nibble-table implementation; matches Garmin's SDK byte-for-byte.

**Intervals.icu uploader - Android.**

- [`HttpFetcher.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/HttpFetcher.kt) gained `postMultipartFile(url, fileFieldName, fileName, fileBytes, fileMimeType, extraFormFields, headers): PostResult` - a vanilla `HttpURLConnection` multipart POST. HTTPS-only, generous timeouts for uploads, body capped at 4096 chars in the result.
- [`IntervalsIcuUploader.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/sync/IntervalsIcuUploader.kt) - composes the upload: builds FIT bytes via `RideFitBuilder`, normalises athlete id (always `i…` prefix, never doubled), HTTP-Basic auth with literal username `"API_KEY"` + the user's API key as password (per Intervals.icu's documented scheme), POST to `https://intervals.icu/api/v1/athlete/{id}/activities`. Returns `Outcome.Success(activityId?)` / `NotConfigured` / `Failure(message)`. Cheap regex sniff for `"id":…` in the success body - no JSON parser pulled in just for that.

**Settings + persistence.** Three new fields on the [`UserSettings`](../shared/src/commonMain/kotlin/app/indoorbike/shared/settings/UserSettings.kt) interface:

| field | type | persisted in |
| --- | --- | --- |
| `intervalsAthleteId` | `String?` | DataStore (plaintext) |
| `intervalsApiKey` | `String?` | DataStore (plaintext) |
| `intervalsAutoUpload` | `Boolean` | DataStore |

The API key sits in plaintext DataStore for now. When device-credential hardening lands we'll move it to AndroidX `security-crypto` without touching the `UserSettings` interface (the property type is already `String?`).

**Settings UI.** New `CLOUD SYNC` section between `DEVICES` and `ABOUT` in [`SettingsTab.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/SettingsTab.kt) hosting an `IntervalsIcuCard`: athlete-id input, API-key input (`PasswordVisualTransformation` with a show/hide eye toggle), and an auto-upload `Switch` that's disabled until both credential fields are filled. Copy explicitly states the F-Droid posture ("only contacts intervals.icu when an upload is triggered").

**Upload triggers.** [`WorkoutOverviewScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/WorkoutOverviewScreen.kt):

- **Manual** - `IntervalsUploadCard` at the bottom of the scroll content, visible only when credentials are configured. "Upload" button posts a Toast with the result.
- **Auto** - a `LaunchedEffect(activeSession.id, fromRide, autoUpload, intervalsConfigured)` fires once when the screen arrives from a finished ride. A `uploadAttemptedForSession` flag prevents config-change recompositions from re-firing the upload.

**F-Droid posture.** Triggers `NonFreeNet` only when the user opts in. Manifest declares `INTERNET` (already present for the registry feature). No third-party libs added - FIT writing is pure Kotlin in shared, multipart POST is `HttpURLConnection`. Auto-upload is off by default and disabled in the UI until both credentials are provided. Can be unilaterally disabled from Settings without losing locally-stored ride history.

### Backup & sync safety (anti-wipe guard)

The folder-based "Backup & sync" feature ([`SyncEngine`](../mobile_clients/android/src/main/kotlin/app/indoorbike/sync/SyncEngine.kt)) writes one slice per device (`indoorbike-device-<deviceId>.json`) and **infers deletes by diffing** local state against the device's previous own-slice: anything in the slice but absent locally is tombstoned ([`BackupSliceBuilder.computeSessionDeletes`](../shared/src/commonMain/kotlin/app/indoorbike/shared/sync/BackupSliceBuilder.kt)). That inference is correct for a deliberate in-app delete but dangerous when local data vanished for another reason (reinstall, cleared data, a failed DB migration): the diff reads as "the rider deleted everything" and the tombstones propagate the loss into the backup and every peer. Auto-sync runs on every launch, so the wipe could happen with no interaction.

Three layers now guard against that:

- **Suspicious-reset detection.** [`BackupSafety.isSuspiciousReset`](../shared/src/commonMain/kotlin/app/indoorbike/shared/sync/BackupSafety.kt) (pure, unit-tested) flags a round that would delete a large share of the backup - local emptied while the backup holds data, or a single round removing >= 5 items and >= half the backup. Normal one-off deletes don't trip it. When tripped, `SyncEngine` sets a persisted `UserSettings.syncPaused` flag and returns `SyncStatus.NeedsReview(local…, backup…)` **without writing anything** (local and folder both untouched).
- **Paused = no automated writes.** `syncNow()` is the single chokepoint for both the launch auto-sync and the manual button; while `syncPaused` it only does a read-only `assess()` and surfaces the review state. The rider resolves via `resolveRestoreFromBackup()` (pull the backup back onto this device, infer no deletes) or `resolveKeepLocal()` (confirm the deletes), both of which clear the flag. The Settings "Backup & sync" card renders the warning + both buttons, plus an always-available **Restore from backup** action.
- **One-deep slice backup.** [`SafSyncStorage.writeOwnSlice`](../mobile_clients/android/src/main/kotlin/app/indoorbike/sync/SafSyncStorage.kt) renames the existing slice to `…json.prev` (skipped by `readAllSlices`) before promoting the new one, so any overwrite is recoverable.

Note `deviceId` is a random UUID in DataStore, which Auto Backup **excludes** ([`data_extraction_rules.xml`](../mobile_clients/android/src/main/res/xml/data_extraction_rules.xml)); a true data wipe regenerates it, so the device writes a fresh slice rather than clobbering the old one. The guard above covers the residual case where the id survives while the DB is emptied.

### F-Droid hygiene checklist

- **Fonts**: bundled locally as OFL TTFs in `mobile_clients/android/src/main/res/font/` - no Google Fonts CDN, no runtime download. Sources are the OFL distribution at rest in `github.com/google/fonts`:
    - `bebas_neue_regular.ttf` (60 KB, static) - display headlines (`displayXl/L/M`, `h1`, `h2`).
    - `inter_variable.ttf` (856 KB, variable wght axis 100–900) - body text (`h3`, `body`, `bodyStrong`, `small`, `micro`, `eyebrow`).
    - `jetbrains_mono_variable.ttf` (183 KB, variable wght axis 100–800) - monospace ticks (`tick`, `mono`).
  Variable fonts are wired via `Font(R.font.…, weight = FontWeight.X, variationSettings = FontVariation.Settings(FontVariation.weight(N)))` in [`ui/theme/Type.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/theme/Type.kt). Requires `@OptIn(ExperimentalTextApi::class)` on Compose 1.7.5; the API graduates to stable in newer Compose versions.
- **Icons**: design specifies Lucide; current implementation uses `androidx.compose.material:material-icons-extended` (Apache 2.0) as a visual stand-in.
- **Mock trainer toggle**: in-ride mock toggle is gated behind `BuildConfig.DEBUG` so release builds don't expose dev affordances.
- **Activity export**: FIT and TCX are written by hand-rolled pure-Kotlin encoders in `shared/` (no third-party dep); the rider exports a past activity to a file via the OS share sheet, or uploads to Intervals.icu. See "Activity export (FIT / TCX) + Intervals.icu cloud sync" above.

### Diagnostics & bug reports

There is no remote logger or crash reporter (F-Droid posture). `Log` ([`shared/.../util/Logger.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/util/Logger.kt)) writes to Logcat in debug builds only, and mirrors every line into [`DiagnosticsLog`](../shared/src/androidMain/kotlin/app/indoorbike/shared/util/DiagnosticsLog.kt) - an in-memory ring buffer (last 300 lines) that is kept in **all** build types so release users have something to attach to a report. It is RAM-only: never persisted, never sent, surfaced only when the user explicitly drafts a report.

"Report a problem" (Settings → FEEDBACK card, and a **Report** link on each in-app error banner) routes through [`BugReport`](../mobile_clients/android/src/main/kotlin/app/indoorbike/feedback/BugReport.kt), which assembles device info (model, Android version, app version) + the buffered logs and opens an `ACTION_SEND` email draft to the developer (recipient/subject/body pre-filled, logs also attached as a `.txt` via the existing `FileProvider`). Nothing is sent automatically - the user reviews and sends. A [`ReportProblemDialog`](../mobile_clients/android/src/main/kotlin/app/indoorbike/feedback/ReportProblemDialog.kt) discloses what's collected before the draft opens.

**No personal data is ever logged** - this is a hard invariant, since these lines can leave the device. The app logs only technical state (BLE connection events, FTMS protocol bytes, parse failures, counts); body metrics / credentials / names are never logged, and BLE device identifiers are masked via [`redactDeviceId`](../shared/src/commonMain/kotlin/app/indoorbike/shared/util/Redaction.kt). Keep it that way when adding log calls.
