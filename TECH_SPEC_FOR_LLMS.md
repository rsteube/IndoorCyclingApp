# Technical Specification for LLMs

## Project Overview
This project is an indoor cycling app that connects to a Wahoo Kickr Core over BLE to perform structured workouts in ERG mode. The app is built using Kotlin Multiplatform Mobile (KMM).
- **Core logic**: The `shared` module, written in KMM, will handle parsing structured workouts, defining models, and handling Bluetooth Low Energy (BLE) interactions (using Kable or a similar cross-platform BLE library).
- **Android App**: The `mobile_clients/android` module is purely native, built with Jetpack Compose.
- **iOS App**: To be added later (`mobile_clients/ios`), using SwiftUI.

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
- `ui/SettingsTab.kt` — connection status, FTP input (commits on focus loss / IME-Done; rejects values outside 50–600 W), Forget-Trainer action, About card with version + license info.
- `ui/TrainerPairingScreen.kt` — BLE scan screen with live "Scanning · N found" indicator and an Open-Settings escape hatch when permissions are denied.
- `MainActivity.kt` — loads `workouts*.json` from assets, holds a `Navigator` over `androidx.navigation3` back stacks. Each top-level tab keeps its own back stack; the bottom `TabBar` only renders when on a top-level route at depth 1.

### Shared (KMM) helpers

- `shared/src/commonMain/kotlin/app/indoorbike/shared/progression/Progression.kt` — pure functions for streak / rider-level / badge / time-in-zone / NP+IF+TSS+kJ computations. All inputs are `WorkoutSession` lists or 1Hz telemetry samples; no UI dependencies. Reused by Android `ProgressTab` and `WorkoutOverviewScreen`.
- `shared/src/commonMain/kotlin/app/indoorbike/shared/session/WorkoutSessionManager.kt` — also exposes `targetBiasPercent` (for Zwift Click-style ±difficulty fallback in the UI), `adjustDifficulty(deltaPercent)`, and `skipSegment()`. Bias is applied multiplicatively when computing trainer target watts.

### Tests
- `shared/src/commonTest/kotlin/.../progression/ProgressionTest.kt` — streak (with TZ offset), rider level, time-in-zone, RideStats (NP/IF/TSS), badges.
- `shared/src/commonTest/kotlin/.../workout/WorkoutSummaryTest.kt` — tag inference, NP=IF×FTP, formatClock HH:MM:SS edge, formatDurationPretty.
- 23 tests, all green. Run via `./gradlew :shared:testDebugUnitTest`.

### F-Droid hygiene checklist

- **Fonts**: bundled locally as OFL TTFs in `mobile_clients/android/src/main/res/font/` — no Google Fonts CDN, no runtime download. Sources are the OFL distribution at rest in `github.com/google/fonts`:
    - `bebas_neue_regular.ttf` (60 KB, static) — display headlines (`displayXl/L/M`, `h1`, `h2`).
    - `inter_variable.ttf` (856 KB, variable wght axis 100–900) — body text (`h3`, `body`, `bodyStrong`, `small`, `micro`, `eyebrow`).
    - `jetbrains_mono_variable.ttf` (183 KB, variable wght axis 100–800) — monospace ticks (`tick`, `mono`).
  Variable fonts are wired via `Font(R.font.…, weight = FontWeight.X, variationSettings = FontVariation.Settings(FontVariation.weight(N)))` in [`ui/theme/Type.kt`](mobile_clients/android/src/main/kotlin/app/indoorbike/ui/theme/Type.kt). Requires `@OptIn(ExperimentalTextApi::class)` on Compose 1.7.5; the API graduates to stable in newer Compose versions.
- **Icons**: design specifies Lucide; current implementation uses `androidx.compose.material:material-icons-extended` (Apache 2.0) as a visual stand-in.
- **Mock trainer toggle**: in-ride mock toggle is gated behind `BuildConfig.DEBUG` so release builds don't expose dev affordances.
- **FIT export**: not yet implemented. Sessions persist to SQLite via SqlDelight (`HistoryDatabase`); a future change should add a FIT writer for sharing.
