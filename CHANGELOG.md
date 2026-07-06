# Changelog

All notable changes to **Indoor Bike** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); versions map to the Android
`versionName` (with `versionCode` in parentheses).

## [1.4] (7) - 2026-07-06

A UI polish release. The Progress, History, and Settings screens are reworked
and the ride screen gains a few quiet celebration cues. Nothing changes what a
ride is worth or how the trainer is driven.

### Changed

- **Progress screen comes alive.** Rider level, XP, and the weekly streak now
  animate in on entry - the XP bar fills, the XP number counts up, and the
  streak flame breathes. A new **badges** section shows what you have unlocked,
  with locked badges hinting at what is next. All of it is decoration on top of
  the same pure progression functions in `shared/progression`.
- **Redesigned ride history.** Each session leads with a calendar date tile
  (weekday + day number) instead of a date-as-text line, with the workout name
  and one compact stats line beside it. Each week group header now carries that
  week's own totals - rides, time, and kilojoules - so you can see how much you
  did that week without opening any ride.
- **Reorganised Settings.** Related fields are grouped into single cards instead
  of floating as three separate boxes, and the **Report a problem** block is now
  one tappable row, with the what-gets-collected disclosure moved into the
  confirmation dialog right before anything is sent.
- **Ride-screen cues.** Holding the ERG target now shows a soft "ON TARGET" glow
  behind the power number, the screen edges flash once in the new zone's colour
  as each interval begins, and completing a workout gets an animated trophy.

### Editor (web)

- **Keyboard shortcuts panel** listing the editor's shortcuts (select, toggle,
  range, box-select, duplicate, delete, nudge power/duration).
- **Cleaner workout-details section** for the name and description fields.

### Notes

- `versionCode 7`, signed with the project's release key.

## [1.3] (6) - 2026-07-05

### Added

- **End & save a paused ride.** When a ride is paused - either you paused it, or
  it auto-paused because you stopped pedaling - you can now tap **End & save** to
  finish and keep it in your history, instead of having to pedal all the way to
  the end. A short confirmation spells out that the ride so far is saved and
  can't be resumed.
- **Redesigned home screen.** The "Ride" screen now leads with an **Up next**
  hero that proposes one workout to do next (an unfinished ride, else your
  last-ridden workout, else a newest favourite), and starts it in a single tap
  when the trainer is connected. Below it, a **Quick picks** shelf surfaces your
  starred and most-ridden workouts.
- **Search and duration filters.** Find a workout by name or tag, and filter the
  library by length (up to 30 min, 30-60, or 60+) alongside the existing
  Favourites and tag filters.
- **Favourite from the list.** Star or unstar a workout straight from the library
  row, without opening it first.
- **Ride again from history.** Each past session now has a **Ride again** button
  that jumps straight back to that workout (shown only when the workout is still
  in your library).
- **Trainer status chip and QR scan in the top bar.** The home top bar now shows
  a single trainer-status chip (device name / pair / connecting) that opens
  pairing, plus a dedicated button to scan a workout QR code from the web editor.

### Changed

- **"Get workouts" screen reorganised.** Scanning a workout from the web editor
  is now the headline action, with Import file and Paste URL as secondary tiles;
  imported-workout management is tucked behind an expandable row and hidden
  entirely when you have none.
- **Cleaner ride header.** The landscape header drops the workout title so the
  live state and clock have room, and an auto-paused ride now reads
  "AUTO-PAUSED · PEDAL TO RESUME".

### Fixed

- **Shared ride card chart.** For a ride that ended early, the power curve is now
  plotted on the planned time axis so it lines up with the workout's zone bars
  instead of stretching across the whole width.
- **Filter no longer strands the list.** If the active filter disappears (you
  unstar your last favourite, or delete a workout), the list falls back to "All"
  instead of showing nothing.

### Notes

- `versionCode 6`, signed with the project's release key.

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
