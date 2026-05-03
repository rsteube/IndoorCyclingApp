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
Standard BLE APIs will be used to scan, connect, and set the ERG target power on the smart trainer. Since it targets KMM, we will eventually wrap BLE operations in expected/actual patterns or use a multiplatform BLE library like juul/kable. Zwift Click (v1) controller integration for +/- power adjustments will also be added.

## Development Workflow
When making changes, always ensure that KMM logic stays in the `shared` module and that UI additions in the Android client utilize the `app.indoorbike` namespace. Use Material 3 in Jetpack Compose, maintaining a high-fidelity dark aesthetic.

## Android UI Architecture
The Android client implements the **Cadence design system** (see [cadence-design-system/README.md](cadence-design-system/README.md)). Code is organized under `app.indoorbike.ui.*`:

- `ui/theme/` — design tokens. `CadenceColors` (gravel-warm dark palette + brand ember + line/fg ramps), `CadenceType` (display/body/mono scale), `Spacing`, `Radii`, and `CadenceTheme` (M3 ColorScheme + Typography wrapper).
- `ui/zones/PowerZones.kt` — 7-zone enum mapping FTP% → `PowerZone` with calibrated cool→hot color spectrum. Use `PowerZone.fromFtpPercent()` whenever an FTP% needs a zone color.
- `ui/components/Primitives.kt` — reusable building blocks: `AppBar`, `IconBtn`, `PrimaryBtn`, `SecondaryBtn`, `ZoneBadge`, `MetaPill`, `ZoneBar`, `TabBar`, `TrainerStatusStrip`, `FilterChip`.
- `ui/workout/WorkoutSummary.kt` — derives display stats from a `Workout` definition (planned ride view: duration, IF, TSS, kJ, dominant tag, zone-bar segments, normalized power). Uses `UserSettings.ftp` (default 200, persisted in SharedPreferences).
- `ui/screens/` — `WorkoutListScreen`, `WorkoutDetailScreen`, `RideScreen` (live HUD), `WorkoutOverviewScreen` (post-ride). The list screen derives filter chips from the loaded workouts (no hardcoded categories); the detail screen truncates the step list to 6 with a "tap to expand" pill; the overview deletes a session via the `HistoryRepository.deleteSession()` cascade.
- `ui/HistoryTab.kt` — week-grouped session list with totals card (rides / time / kJ) at the top. Empty state shown when no sessions exist.
- `ui/ProgressTab.kt` — local progression: streak hero (current days + longest + 14-day mini-grid), rider-level meter with kJ thresholds, badge grid. Computes everything from `HistoryRepository.getAllSessions()` via the shared `progression/Progression.kt` helper. No backend, no account.
- `ui/SettingsTab.kt` — connection status, FTP input (commits on focus loss / IME-Done; rejects values outside 50–600 W), Forget-Trainer action, About card with version + license info.
- `ui/TrainerPairingScreen.kt` — BLE scan screen with live "Scanning · N found" indicator and an Open-Settings escape hatch when permissions are denied.
- `MainActivity.kt` — loads `workouts*.json` from assets, holds a `Navigator` over `androidx.navigation3` back stacks. Each top-level tab keeps its own back stack; the bottom `TabBar` only renders when on a top-level route at depth 1.

### Shared (KMM) helpers

- `shared/src/commonMain/kotlin/app/indoorbike/shared/progression/Progression.kt` — pure functions for streak / rider-level / badge / time-in-zone / NP+IF+TSS+kJ computations. All inputs are `WorkoutSession` lists or 1Hz telemetry samples; no UI dependencies. Reused by Android `ProgressTab` and `WorkoutOverviewScreen`.
- `shared/src/commonMain/kotlin/app/indoorbike/shared/session/WorkoutSessionManager.kt` — also exposes `targetBiasPercent` (for Zwift Click-style ±difficulty fallback in the UI), `adjustDifficulty(deltaPercent)`, and `skipSegment()`. Bias is applied multiplicatively when computing trainer target watts.

### F-Droid hygiene checklist

- **Fonts**: design specifies Bebas Neue / Inter / JetBrains Mono. OFL TTFs must be bundled locally (no Google Fonts CDN) — drop them into `mobile_clients/android/src/main/res/font/` and rebind `DisplayFontFamily` / `BodyFontFamily` / `MonoFontFamily` in `ui/theme/Type.kt`.
- **Icons**: design specifies Lucide; current implementation uses `androidx.compose.material:material-icons-extended` (Apache 2.0) as a visual stand-in.
- **Mock trainer toggle**: in-ride mock toggle is gated behind `BuildConfig.DEBUG` so release builds don't expose dev affordances.
- **FIT export**: not yet implemented. Sessions persist to SQLite via SqlDelight (`HistoryDatabase`); a future change should add a FIT writer for sharing.
