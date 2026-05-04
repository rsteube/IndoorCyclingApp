# Technical Specification for LLMs

**Current version: 1.0** (`versionCode = 1`, `versionName = "1.0"` in [mobile_clients/android/build.gradle.kts](mobile_clients/android/build.gradle.kts)).

## Project Overview
This project is an indoor cycling app that connects to a Wahoo Kickr Core over BLE to perform structured workouts in ERG mode. The app is built using Kotlin Multiplatform Mobile (KMM).
- **Core logic**: The `shared` module, written in KMM, will handle parsing structured workouts, defining models, and handling Bluetooth Low Energy (BLE) interactions (using Kable or a similar cross-platform BLE library).
- **Android App**: The `mobile_clients/android` module is purely native, built with Jetpack Compose.
- **iOS App**: To be added later (`mobile_clients/ios`), using SwiftUI.

## Build variants & install (v1.0)

Two install slots so debug + release coexist on a single device:

- **Release**: `applicationId = app.indoorbike`, label "Indoor Bike", brand-orange launcher icon. Build with `:mobile_clients:android:assembleRelease` (or `installRelease` for debuggable variants).
- **Debug**: `applicationId = app.indoorbike.debug`, `versionName = 1.0-debug`, label "Indoor Bike Debug", **grayscale** launcher icon (resource overlay in [src/debug/res/](mobile_clients/android/src/debug/res/) — debug-specific `ic_launcher_foreground.xml` swaps brand colors for grays; `colors.xml` overrides `ic_launcher_background`; `strings.xml` overrides the app name). Build with `:mobile_clients:android:installDebug`.

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
- **Fitness Machine Service** (`0x1826`) → Indoor Bike Data (`0x2AD2`) is a fallback for power/cadence on FTMS-only trainers; FTMS Control Point (`0x2AD9`) is used to write target power in ERG mode (opcode 0x05, sint16-LE watts; opcode 0x00 requests control on connect).
- **Heart Rate Service** (`0x180D`) → Heart Rate Measurement (`0x2A37`) is subscribed if the peripheral exposes it.
- The UI consumes a domain `TrainerCandidate(id, name, rssi)` rather than `com.juul.kable.Advertisement`, so swapping BLE libraries doesn't ripple into the UI layer.
- `peripheral.state` listener and per-characteristic `observe` jobs are explicitly tracked and cancelled across reconnects (no leaks).
- Zwift Click (v1) controller integration is not yet implemented; the in-app `±difficulty` stepper in `RideScreen` calls `WorkoutSessionManager.adjustDifficulty(±5)` as the fallback path.

## Development Workflow
When making changes, always ensure that KMM logic stays in the `shared` module and that UI additions in the Android client utilize the `app.indoorbike` namespace. Use Material 3 in Jetpack Compose, maintaining a high-fidelity dark aesthetic.

## Runtime Architecture (Android)

### `IndoorBikeApp` and `AppContainer`
- `IndoorBikeApp : Application` is declared in the manifest (`android:name=".IndoorBikeApp"`) and constructs an [`AppContainer`](mobile_clients/android/src/main/kotlin/app/indoorbike/IndoorBikeApp.kt) once.
- The container owns the long-lived singletons: `TrainerManager`, `WorkoutSessionManager`, `HistoryRepository`, `UserSettings`, `ActiveSessionStore`. Each has a `dispose()` that cancels its `SupervisorJob + Dispatchers.Default` scope.
- Composables read the container via `LocalAppContainer.current`. `MainActivity.setContent { CompositionLocalProvider(LocalAppContainer provides container) { … } }` installs it.
- This replaces the previous `object`-singleton pattern. Tests instantiate the same classes directly with fakes.

### Persistence
- **SQLite via SqlDelight**: `HistoryDatabase` (`schema in shared/.../db/History.sq`). Foreign keys are enabled in the Android driver via `setForeignKeyConstraintsEnabled(true)`; `WorkoutTelemetry` cascades on session delete.
- **DataStore Preferences** for `UserSettings` (FTP, paired trainer id) — replaces the legacy `SharedPreferences` singleton.
- **Active-session snapshot** (`ActiveSessionStore`): a small JSON file in `filesDir` written every 5 s during an active ride. Atomic write via tmp + rename. Used for process-death recovery (the snapshot is cleared on `stopWorkout()` / `completeWorkout()`).

### Foreground Service for active rides
[`RideSessionService`](mobile_clients/android/src/main/kotlin/app/indoorbike/service/RideSessionService.kt) runs while a workout is active. It:
- Posts an ongoing notification with current target watts and elapsed time (channel `ride_session`, importance LOW).
- Holds a `PARTIAL_WAKE_LOCK` (capped at 4 hours) so BLE notifications and the wall-clock timer don't get suspended when the screen sleeps.
- Declares `foregroundServiceType="connectedDevice"` (manifest); `FOREGROUND_SERVICE_CONNECTED_DEVICE` and `POST_NOTIFICATIONS` permissions are declared.
- Started/stopped via a `DisposableEffect(Unit)` in `RideScreen` so it's tightly coupled to the ride being on screen.
- **Android 14+ caveat**: starting a `connectedDevice` FGS requires at least one runtime-granted `BLUETOOTH_*` permission. `RideScreen` requests `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` (or `ACCESS_FINE_LOCATION` on API < 31) before starting the service so a brand-new user who reaches Start without ever opening the pairing flow doesn't crash. The service additionally wraps `startForeground` in `try/catch SecurityException` and `stopSelf()`s gracefully — defense in depth.

### Heart Rate Monitor (separate from trainer)

[`HeartRateMonitor`](shared/src/commonMain/kotlin/app/indoorbike/shared/ble/HeartRateMonitor.kt) is an optional independent BLE peripheral, parallel to `TrainerManager`. Lives in `AppContainer`. Lets users ride with just a trainer, just an HRM, or both — `WorkoutSessionManager.effectiveHeartRate` prefers the dedicated HRM if it's emitting samples, otherwise falls back to whatever the trainer reports.

- **Scan filter quirk**: scans **without** a service-UUID filter and post-filters by either (a) the 0x180D Heart Rate Service appearing in the advertisement payload, or (b) the device name matching one of `HRM`, `Polar`, `TICKR`, `Coros`, `Suunto`, `Magene`, `Verity`, `Heart`. Garmin HRM-Pro / HRM-Pro Plus omit 0x180D from their advertisement (they only expose it after GATT connect), so a service-UUID filter would never match them.
- Auto-reconnect on app launch via `autoConnectIfPaired(pairedHrmId)`.

### First-run onboarding (soft, non-gating)

Implemented inside [`WorkoutListScreen.kt`](mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/WorkoutListScreen.kt):

- A **welcome banner** is the top item on the Rides tab when `userSettings.hasCompletedOnboarding == false`. Tapping **Get Started** opens a Material 3 `ModalBottomSheet` with FTP + Weight inputs.
  - The FTP row has a brand-colored "Don't know your FTP? Start at 150W" link that fills the field with the shared constant `UNKNOWN_FTP_DEFAULT` (150).
  - **Save & Continue** validates against `MIN_FTP..MAX_FTP` / `MIN_WEIGHT_KG..MAX_WEIGHT_KG`; any sheet dismissal (Save, swipe-down, back) marks `hasCompletedOnboarding = true`.
- After onboarding, the welcome banner is replaced by the existing **Pair a trainer** nudge (until any trainer is paired, real or mock), which is replaced in turn by `TrainerStatusStrip`.
- A **"Start here" pinned card** (orange-bordered, eyebrow `START HERE · YOUR FIRST RIDE`) is inserted as the first list item when `hasCompletedOnboarding && historyRepository.getAllSessions().isEmpty() && starter != null`. The starter workout is identified by the constant `STARTER_WORKOUT_ID = "starter-15min"` (defined in [workouts_sample.json](mobile_clients/android/src/main/assets/workouts_sample.json) — 15 min mixed-zone intro). The card disappears after the first completed ride. While pinned, the same workout is filtered out of the regular list to avoid duplication.

### Trainer-required gating

[`WorkoutDetailScreen`](mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/WorkoutDetailScreen.kt) observes `trainerManager.connectionState` + `isMockTrainer`. The bottom CTA is state-aware:

- **Connected** (real or mock) → primary "Start workout" button (existing behaviour).
- **Not connected** → primary "Pair a trainer to ride" button + small subtitle, taps route to the trainer pairing screen via `onNavigateToPairing`. Mock counts as connected.

This is the only gating on Start; users can still browse workout details without a trainer.

### Workout session timing
`WorkoutSessionManager` drives elapsed/segment progress from **wall-clock time** (`elapsed = (now - activePhaseStartEpochMs) / 1000`) rather than counting `delay(1000)` ticks, so the ride doesn't drift over a 60+ minute session if the dispatcher hiccups. The phase anchor is reset on every pause/resume/skip.

## Android UI Architecture
The Android client implements the **Cadence design system** (see [cadence-design-system/README.md](cadence-design-system/README.md)). Code is organized under `app.indoorbike.ui.*`:

- `ui/theme/` — design tokens. `CadenceColors` (gravel-warm dark palette + brand ember + line/fg ramps), `CadenceType` (display/body/mono scale), `Spacing`, `Radii`, and `CadenceTheme` (M3 ColorScheme + Typography wrapper).
- `ui/zones/PowerZones.kt` — 7-zone enum mapping FTP% → `PowerZone` with calibrated cool→hot color spectrum. Use `PowerZone.fromFtpPercent()` whenever an FTP% needs a zone color.
- `ui/components/Primitives.kt` — reusable building blocks: `AppBar`, `IconBtn`, `PrimaryBtn`, `SecondaryBtn`, `ZoneBadge`, `MetaPill`, `ZoneBar`, `TabBar`, `TrainerStatusStrip`, `FilterChip`.
- Planned-ride summary stats (`shared/.../workout/WorkoutSummary.kt` — IF, TSS, kJ, NP, dominant tag) live in shared so they're testable and reusable across platforms. `ui/workout/WorkoutSummaryUi.kt` adds the Android-only `zoneSegments()` extension that maps to UI `ZoneSegment` (color-aware).
- `ui/screens/` — `WorkoutListScreen`, `WorkoutDetailScreen`, `RideScreen` (live HUD), `WorkoutOverviewScreen` (post-ride). The list screen derives filter chips from the loaded workouts (no hardcoded categories); the detail screen truncates the step list to 6 with a "tap to expand" pill; the overview deletes a session via the `HistoryRepository.deleteSession()` cascade.
- `ui/HistoryTab.kt` — week-grouped session list with totals card (rides / time / kJ) at the top. Empty state shown when no sessions exist.
- `ui/ProgressTab.kt` — local progression: streak hero (current days + longest + 14-day mini-grid), rider-level meter with kJ thresholds, badge grid. Computes everything from `HistoryRepository.getAllSessions()` via the shared `progression/Progression.kt` helper. No backend, no account.
- `ui/SettingsTab.kt` — three grouped sections: **Profile** (FTP + Weight rows, commit on focus loss / IME-Done, validated against `MIN/MAX_FTP` and `MIN/MAX_WEIGHT_KG`), **Devices** (single grouped card with Trainer + HRM rows; tapping a row opens a Material 3 `ModalBottomSheet` for that device with state-aware Pair / Disconnect / Forget actions), **About** (version, build type, license). The bottom-sheet pattern keeps the destructive `Forget` action out of the always-visible scroll.
- `ui/TrainerPairingScreen.kt` — BLE scan screen with live "Scanning · N found" indicator and an Open-Settings escape hatch when permissions are denied. Includes a "Use Mock Trainer" secondary button for users without hardware.
- `ui/HrmPairingScreen.kt` — parallel to the trainer pairing screen; uses `HeartRateMonitor` and the name-heuristic-based filter (so Garmin HRM-Pro shows up).
- `ui/HistoryTab.kt` / `ui/ProgressTab.kt` empty states each show a primary "Pick a workout" button that calls `onNavigateToRides` — wired in [`MainActivity.kt`](mobile_clients/android/src/main/kotlin/app/indoorbike/MainActivity.kt) to `navigator.navigate(TabRides)`.
- `MainActivity.kt` — loads `workouts*.json` from assets, holds a `Navigator` over `androidx.navigation3` back stacks. Each top-level tab keeps its own back stack; the bottom `TabBar` only renders when on a top-level route at depth 1.

### Shared (KMM) helpers

- `shared/src/commonMain/kotlin/app/indoorbike/shared/progression/Progression.kt` — pure functions for streak / rider-level / badge / time-in-zone / NP+IF+TSS+kJ computations. All inputs are `WorkoutSession` lists or 1Hz telemetry samples; no UI dependencies. Reused by Android `ProgressTab` and `WorkoutOverviewScreen`.
- `shared/src/commonMain/kotlin/app/indoorbike/shared/session/WorkoutSessionManager.kt` — also exposes `targetBiasPercent` (for Zwift Click-style ±difficulty fallback in the UI), `adjustDifficulty(deltaPercent)`, and `skipSegment()`. Bias is applied multiplicatively when computing trainer target watts.

### Tests

Two layers, both documented in [TESTING.md](TESTING.md):

**Shared-module unit tests** — pure logic, no Android dependencies:
- `shared/src/commonTest/kotlin/.../progression/ProgressionTest.kt` — streak (with TZ offset), rider level, time-in-zone, RideStats (NP/IF/TSS), badges.
- `shared/src/commonTest/kotlin/.../workout/WorkoutSummaryTest.kt` — tag inference, NP=IF×FTP, formatClock HH:MM:SS edge, formatDurationPretty.
- Run via `.\gradlew.bat :shared:allTests` (PowerShell) or `./gradlew :shared:testDebugUnitTest`.

**Smoke test** ([scripts/smoke_test.ps1](scripts/smoke_test.ps1)) — installs the debug APK, clears data, walks through onboarding → mock-trainer pair → starter ride, captures a screenshot at each step into `build/test-artifacts/<timestamp>/`, and greps logcat for `FATAL` / `AndroidRuntime: FATAL`. Uses `uiautomator dump` + `input tap` (`Tap-Text` helper) to hit buttons by label, so renaming a CTA breaks the script — keep label strings in sync. Run with `.\scripts\smoke_test.ps1` (single connected device) or `.\scripts\smoke_test.ps1 -DeviceId <serial>`.

**Manual test plan** — see [TESTING.md](TESTING.md) for the full checklist organised by feature area (onboarding, pairing, ride, history, progress, side-by-side install, etc.). Use this for anything the smoke test can't assert (BLE, label correctness, layout).

### F-Droid hygiene checklist

- **Fonts**: bundled locally as OFL TTFs in `mobile_clients/android/src/main/res/font/` — no Google Fonts CDN, no runtime download. Sources are the OFL distribution at rest in `github.com/google/fonts`:
    - `bebas_neue_regular.ttf` (60 KB, static) — display headlines (`displayXl/L/M`, `h1`, `h2`).
    - `inter_variable.ttf` (856 KB, variable wght axis 100–900) — body text (`h3`, `body`, `bodyStrong`, `small`, `micro`, `eyebrow`).
    - `jetbrains_mono_variable.ttf` (183 KB, variable wght axis 100–800) — monospace ticks (`tick`, `mono`).
  Variable fonts are wired via `Font(R.font.…, weight = FontWeight.X, variationSettings = FontVariation.Settings(FontVariation.weight(N)))` in [`ui/theme/Type.kt`](mobile_clients/android/src/main/kotlin/app/indoorbike/ui/theme/Type.kt). Requires `@OptIn(ExperimentalTextApi::class)` on Compose 1.7.5; the API graduates to stable in newer Compose versions.
- **Icons**: design specifies Lucide; current implementation uses `androidx.compose.material:material-icons-extended` (Apache 2.0) as a visual stand-in.
- **Mock trainer toggle**: in-ride mock toggle is gated behind `BuildConfig.DEBUG` so release builds don't expose dev affordances.
- **FIT export**: not yet implemented. Sessions persist to SQLite via SqlDelight (`HistoryDatabase`); a future change should add a FIT writer for sharing.
