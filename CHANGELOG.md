# Changelog

All notable changes to **Indoor Bike** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); versions map to the Android
`versionName` (with `versionCode` in parentheses).

## [Unreleased]

### Added

- **Interval targets in exported FIT files.** A lap in an exported ride now
  carries what the interval asked for, not just what you did: target power in
  watts, the same target as a percentage of FTP, and the cadence range where
  the interval sets one. Analysis platforms that read FIT custom fields will
  show these alongside your actuals, so a structured session no longer exports
  as an anonymous power trace. Rides with no workout behind them, and intervals
  with no cadence ask, mark the target as absent rather than reporting zero.

### Fixed

- **A heart rate monitor stuck on "waiting for first sample" now recovers, or
  tells you what to do.** The strap connected, the app said so, and no reading
  ever appeared - for every strap tried, across app restarts, until the phone
  was rebooted. The cause is on the phone rather than the strap: when the
  Bluetooth stack declines to switch a sensor's notifications on, that refusal
  was being swallowed one layer below the app, so the app had a connection it
  believed was healthy and a data stream that would never carry anything.
  Nothing recovered because, as far as either side was concerned, nothing had
  failed. The app now watches for the first reading after connecting, rebuilds
  the connection from scratch if none arrives within fifteen seconds, and after
  three silent attempts stops retrying and says the strap is connected but
  sending nothing, with the fix that actually works: turn Bluetooth off and on,
  or restart the phone. Closing the app never helped, because the stuck state
  lives in Android's Bluetooth service and not in the app.

- **Turning Bluetooth back on now reconnects straight away.** Sensors used to
  take up to a minute to come back, because every failed attempt while
  Bluetooth was off pushed the next retry further out, and the app kept
  counting that against the sensor once the radio returned. Since the advice
  for a stuck heart rate monitor is now to restart Bluetooth, a minute of
  nothing happening looked like the advice had not worked. The app waits for
  the radio instead of retrying into it, and starts looking the moment it comes
  back: measured on a Fairphone 4, reconnecting went from about 45 seconds to
  under a tenth of a second. Nothing is attempted and nothing fills the
  diagnostic log while Bluetooth is off, and a report drafted afterwards says
  why the app was waiting, which on older phones also covers location services
  being switched off (below Android 12 a sensor search silently finds nothing
  without them).

- **Bug reports about sensors are no longer blank.** The diagnostic log carried
  nothing at all from the Bluetooth library, which is how a report about a
  silent heart rate monitor could arrive without one line about the heart rate
  monitor in it. The library's own account of connecting, discovering services,
  switching notifications on, and quietly giving up on them now goes into the
  log with the rest. Per-reading traffic is left out so a long ride cannot push
  everything else out of the buffer, and sensor readings themselves are never
  recorded - only that one arrived. Device addresses stay masked.

- **"Auto-reconnect scan failed" no longer leads every bug report.** Two
  routine events, a scan window ending and scanning pausing when the app goes
  to the background, were being written down as failures. That put an alarming
  line at the top of reports about unrelated problems, one that had reliably
  sent people looking in the wrong place. Real scan failures are still
  reported.

- **A dropped heart rate connection could leave its reader running.** The
  subscription to the strap was started on one thread and recorded on another,
  so a disconnect arriving in between tidied up the wrong one and left the old
  reader alive. Same fix the trainer received earlier.

- **Bug reports sent after a crash are no longer empty.** The diagnostic log
  only ever lived in memory, so it died with the app: a report drafted after a
  crash could only show the launch that came after it, which is why reports
  arrived containing nothing but `App start`. The log is now also written to
  private storage as you ride, and a report includes the previous run's log
  alongside the current one. A crash costs at most the last five seconds of
  lines.

- **Reports now say why the app stopped.** Android keeps its own record of how
  each process ended, and the app never read it. A report now includes the last
  three, which distinguishes an app crash from the system reclaiming memory
  from a rider force-stopping the app - none of which the app's own logs can
  tell apart. Where Android captured a stack trace, that comes along too.
  Requires Android 11 or newer; below that the section says so.

- **A crash after a crash.** If the app was killed mid-ride, Android restarted
  its ride service in the background, and the app's attempt to stand that
  service down was refused - which threw, and killed the freshly started
  process. One kill could become a run of them. Both the start and stop paths
  now handle the refusal instead of dying on it.

## [1.5] (8) - 2026-07-28

Cadence targets for intervals, and two fixes for rides that could be lost
before they reached your history.

### Added

- **Cadence targets on intervals.** An interval can now ask for a cadence as
  well as a power - a single value or a range - so a workout can call for
  grinding a climb at 70-80 rpm or spinning leg speed at 105. The trainer holds
  the power in ERG mode exactly as before; the target only tells you how to
  spin while it does, and it is entirely optional. Set one per interval in the
  **workout editor** (tick CADENCE on a selected block, then give it a min and,
  if you want a range, a max).

  While riding, the CADENCE card shows your target with an arrow for which way
  to move - **▲** to spin up, **▼** to ease off, **✓** when you're there. The
  number turns green on target and amber off it, and a small bar under it shows
  how far off you are, because being 2 rpm under and 30 rpm under call for
  different responses. If your trainer isn't reporting cadence, the target is
  shown without a verdict rather than nagging you about a miss it can't see.

  Workouts carrying a cadence target are format v2. Ones without stay v1, so
  they still load in older versions of the app. Third-party links can set
  targets too, via a new `r` suffix in the editor's URL API
  (`45m@78r70-80`) - see [docs/EDITOR_URL_API.md](docs/EDITOR_URL_API.md).

### Fixed

- **Resuming a ride after a crash no longer throws away everything you rode
  before it.** The app remembered *where* you were in the workout but not what
  you had done to get there, so a session interrupted an hour in and resumed was
  saved with the full duration against a power curve covering only the minutes
  after the resume - and the exported `.fit` was that short too. Samples are now
  streamed to an append-only log as you ride and replayed into the session when
  you tap Continue, so a crash costs at most the last few seconds instead of the
  whole ride.
- **A finished ride can no longer be lost before it reaches your history.**
  Between the trophy screen appearing and you tapping View Summary, the ride
  existed only in memory - so anything that killed the app in that window took
  it with it, as did simply backing out of the completion screen. The ride is
  now held on disk until the save actually lands, and if it never did you get a
  **Ride not saved** banner offering to put it in your history.

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
