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
- `ui/components/Primitives.kt` — reusable building blocks: `AppBar`, `IconBtn`, `PrimaryBtn`, `SecondaryBtn`, `ZoneBadge`, `MetaPill`, `ZoneBar`, `TabBar`, `TrainerStatusStrip`, `FilterChip`. All consume tokens from `ui/theme`.
- `ui/workout/WorkoutSummary.kt` — derives display stats (duration, IF, TSS, kJ, dominant tag, zone-bar segments) from a `Workout`. FTP is currently a hardcoded 248W placeholder until user FTP storage exists.
- `ui/screens/` — `WorkoutListScreen` and `WorkoutDetailScreen`. Ride / Summary / Progress screens are designed in the bundle but not yet implemented (they need BLE telemetry and completion data that don't exist yet).
- `MainActivity.kt` — loads `workouts*.json` from assets, holds the current screen in a `sealed interface Screen` state holder. No navigation library yet; add one when screen count justifies it.

**Fonts**: the design specifies Bebas Neue / Inter / JetBrains Mono. F-Droid requires locally bundled OFL TTFs — they are not yet present, so `Type.kt` falls back to `FontFamily.Default` / `Monospace`. Drop the TTFs into `mobile_clients/android/src/main/res/font/` and update `DisplayFontFamily` / `BodyFontFamily` / `MonoFontFamily` to bind them.

**Icons**: design specifies Lucide; current implementation uses `androidx.compose.material:material-icons-extended` (Apache 2.0, F-Droid clean) as a visual stand-in. Swap to a Lucide-for-Compose library or bundled SVG sprite if strict fidelity matters.
