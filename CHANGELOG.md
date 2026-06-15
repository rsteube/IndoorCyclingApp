# Changelog

All notable changes to **Indoor Bike** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); versions map to the Android
`versionName` (with `versionCode` in parentheses).

## [1.1] (2) - 2026-06-15

### Added

- **Activity export (FIT / TCX).** Export any past ride from its overview as a
  FIT or TCX file and hand it to the OS share sheet - send it to Strava, Garmin
  Connect, intervals.icu, TrainingPeaks, or save it anywhere. A small sheet lets
  you pick the format. Both encoders are pure-Kotlin in the shared module (no
  third-party libraries), and the whole flow is offline - nothing leaves the
  device except through the app you choose.
- **Tablet & large-screen UI.** The app now adapts to tablets and unfolded
  foldables:
  - A side **navigation rail** at wide widths (the bottom bar stays on phones).
  - A **multi-column workout list** instead of stretched full-width cards.
  - A reworked **ride HUD** with much larger power / cadence / heart-rate
    readouts, a taller workout-profile timeline, and a live stats strip showing
    elapsed time, average power, and interval progress, all tinted to the
    current power zone.

### Changed

- **Backup & sync is now wipe-safe.** If the app's local data looks accidentally
  reset (after a reinstall, cleared storage, or a bad migration), sync now
  **pauses instead of overwriting your backup**, and surfaces a clear choice:
  **Restore from backup** (pull your rides and settings back onto the device) or
  keep the device's data. Each backup file is also kept as a recoverable `.prev`
  copy before being overwritten. Automatic and manual syncs both respect the
  pause until you resolve it.

### Project / distribution

- The app is now distributable via **[Obtainium](https://github.com/ImranR98/Obtainium)**,
  which tracks signed APKs from the Codeberg releases for automatic updates.
  Install links / badge added to the README and the [indoorbike.app](https://indoorbike.app) home page.
- New **editor URL workout API**: third-party tools can open the in-browser
  workout editor with a workout preloaded via plain URL parameters. Human docs
  at [indoorbike.app/api](https://indoorbike.app/api/), machine spec at
  `indoorbike.app/editor/llms.txt`, plus optional "Open in Indoor Bike" badges.
- The editor now also loads workouts immediately with a non-blocking **Undo**
  instead of a confirmation dialog.

### Notes

- `versionCode 2`, signed with the project's release key.
- Indoor rides carry no GPS, so GPX export is intentionally not offered - FIT and
  TCX both express time, power, heart rate, and cadence without a position track
  and are accepted by the major training platforms.

## [1.0] (1)

- Initial pre-release: BLE FTMS smart-trainer control, ERG-mode power-zone
  workouts, plain-text JSON workouts with an in-browser editor, on-device
  progression (streaks, levels, badges, time-in-zone), FIT export with optional
  Intervals.icu sync, and folder-based cross-device backup. F-Droid posture
  throughout (no Google Play Services, no analytics, no proprietary blobs).
