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
