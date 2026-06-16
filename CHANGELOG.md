# Changelog

All notable changes to **Indoor Bike** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); versions map to the Android
`versionName` (with `versionCode` in parentheses).

## [1.2.2] (5) - 2026-06-16

### Changed

- **Ride in any orientation.** The ride screen is no longer locked to landscape -
  it follows your device's own rotation, so you can train in portrait or
  landscape. The HUD now picks its layout from the actual screen space: a
  vertical stack in portrait (big power on top, cadence and heart rate beneath),
  a compact three-across on a landscape phone, and the full three-across with the
  live stats strip on tablets and foldables.

### Fixed

- **Heart rate (and the other metrics) no longer get cut off or overlap.** The
  HUD previously switched into the tablet layout on short screens and clipped the
  readouts; sizes now scale to the available height so nothing is hidden by
  accident. The portrait header also reflows to two rows instead of squashing the
  workout title and status into a sliver.

### Notes

- `versionCode 5`, signed with the project's release key.

## [1.2.1] (4) - 2026-06-16

### Fixed

- **ERG mode on the Zwift Hub (and other strict FTMS trainers).** These trainers
  reject target-power commands with "Control Not Permitted" unless control has
  genuinely been granted first. The app now takes control (FTMS Request Control)
  together with each target-power write until the trainer confirms the grant,
  and automatically re-requests control if a command is ever rejected, instead
  of giving up. If control still can't be obtained, the error now explains the
  usual cause: another app (e.g. Zwift) is connected to the trainer and holding
  control - close it and re-pair.

### Notes

- `versionCode 4`, signed with the project's release key.

## [1.2] (3) - 2026-06-16

### Added

- **Report a problem.** A new Settings entry (and a **Report** link on in-app
  error banners) drafts an email to the developer with recent diagnostic logs
  and your device model, Android version, and app version. No personal data
  (no name, email, body metrics, or location) is gathered, and nothing is sent
  until you press send in your email app.
- **"Connected" notification.** A brief in-app banner confirms when your
  trainer, heart-rate monitor, or controller connects, including silent
  reconnects after a device wakes from sleep.

### Changed

- **Much more persistent device connection.** Paired trainers, heart-rate
  monitors, and controllers now keep trying to reconnect instead of giving up
  after one scan: open the app before waking your trainer and it connects once
  the trainer starts advertising, and a mid-ride drop recovers on its own.
  Scanning is limited to when the app is in use or a ride is active, so it
  doesn't drain the battery in the background, and an already-paired device is
  no longer asked to pair again.

### Fixed

- **ERG mode on strict trainers (e.g. Zwift Hub).** The app now enables FTMS
  Control Point indications before sending target power, which these trainers
  require - so ERG mode holds the target watts instead of silently doing
  nothing. When ERG control does fail, a clear banner explains why instead of
  failing quietly.

### Notes

- `versionCode 3`, signed with the project's release key.

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
