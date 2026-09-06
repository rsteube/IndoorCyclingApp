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
- **No Google dependencies**: the app must run on an Android device that has no Google services on it - a de-Googled ROM, a Huawei handset, an emulator image without GApps. That rules out Google Play Services and everything built on it (Firebase, Crashlytics, Google Maps, the Play-services location and fitness APIs, the downloadable Google Fonts provider), and it rules out proprietary blobs and trackers generally. Open-source alternatives are preferred. This is a hard guideline and it is about *who can run the app*, not about where the app is listed.

    The project previously framed this as an F-Droid requirement and inherited F-Droid's packaging rules along with it. **F-Droid is no longer a distribution target** - releases go to CodeFloe and reach riders through ObtainX ([docs/RELEASE.md](RELEASE.md)) - but this rule survives that change untouched, because the store was never the reason for it.
- **Authentication**: Auth0 will be used, but it must be completely optional. The app must function fully locally and allow structured workout execution and progression tracking without logging in.
- **Progression System**: Initially local only. Later sync capability will be added via CleverCloud, ensuring it remains fully functional locally when offline or not logged in.
- **UI System**: A clean, modern, and engaging user interface. Dark theme by default, rich visuals for power zones and progression tracking. Avoid generic styles. 

## Workout Models (JSON / Shared KMM)
Structured workouts use Power Zones based on percentages of Functional Threshold Power (FTP). 
- Models: `PowerZone` and `Workout` in `app.indoorbike.shared`.

## BLE Connection Structure
Implemented in `shared/.../ble/TrainerManager.kt` using **juul/kable** (KMM-friendly, Apache-2.0, no Google dependencies). The manager is a regular `class` (not an `object`); a single instance is owned by `AppContainer`.

- **Cycling Power Service** (`0x1818`) → Cycling Power Measurement (`0x2A63`) is the primary source for instantaneous power and crank-revolution-derived cadence.
- **Fitness Machine Service** (`0x1826`) → Indoor Bike Data (`0x2AD2`) is a fallback for power/cadence on FTMS-only trainers; FTMS Control Point (`0x2AD9`) is used to write target power in ERG mode (opcode 0x05, sint16-LE watts; opcode 0x00 requests control on connect). Indications on the Control Point are enabled (via `observe`) before any command is written, because the spec requires it and strict trainers such as the Zwift Hub silently reject control writes otherwise.
- **Heart Rate Service** (`0x180D`) → Heart Rate Measurement (`0x2A37`) is subscribed if the peripheral exposes it.
- The UI consumes a domain `TrainerCandidate(id, name, rssi)` rather than `com.juul.kable.Advertisement`, so swapping BLE libraries doesn't ripple into the UI layer.
- `peripheral.state` listener and per-characteristic `observe` jobs are explicitly tracked and cancelled across reconnects (no leaks).
- **Persistent auto-reconnect.** All three managers (trainer, HRM, controller) share the same model: a single supervisor coroutine started in `init`, driven by two `StateFlow` flags — `wantReconnect` (standing intent to stay on `targetPairedId`) and `scanningAllowed` (lifecycle gate). It scans for the paired device in windows (tuning in [`BleReconnectPolicy`](../shared/src/commonMain/kotlin/app/indoorbike/shared/ble/BleReconnectPolicy.kt)) with growing backoff until it appears — so opening the app before the trainer wakes still connects once it advertises, and a mid-ride drop reconnects on its own. Public methods only flip the flags (never start/stop jobs), so concurrent lifecycle flips and the startup arm can't spawn competing loops. `autoConnectIfPaired(pairedId)` and any connect set `wantReconnect`; it's cleared only by an explicit `disconnect()` / `forget…()` / `connectMock()`, and `startScan()` (manual pairing) takes over until a successful pick re-arms it. Scanning is gated to "app in use" by [`IndoorBikeApp`](../mobile_clients/android/src/main/kotlin/app/indoorbike/IndoorBikeApp.kt) (an `ActivityLifecycleCallbacks` foreground count OR an active ride): `pauseAutoReconnect()` clears `scanningAllowed` when backgrounded with no active ride and `resumeAutoReconnect()` sets it again — an established connection is never dropped, only the scan worker is cancelled. Each manager exposes a `justConnected: SharedFlow<String>`; [`ConnectionToastHost`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/components/ConnectionToastHost.kt) overlays a transient "connected" banner so silent reconnects are visible to the rider.
- Zwift Click v1 controller integration lives in [`shared/.../ble/ControllerManager.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/ble/ControllerManager.kt) - see "Rider Controller" section below. The in-app `±difficulty` stepper in `RideScreen` is the always-available fallback and remains the only path when no controller is paired.

## Development Workflow
When making changes, always ensure that KMM logic stays in the `shared` module and that UI additions in the Android client utilize the `app.indoorbike` namespace. Use Material 3 in Jetpack Compose, maintaining a high-fidelity dark aesthetic.

## Runtime Architecture (Android)

### `IndoorBikeApp` and `AppContainer`
- `IndoorBikeApp : Application` is declared in the manifest (`android:name=".IndoorBikeApp"`) and constructs an [`AppContainer`](../mobile_clients/android/src/main/kotlin/app/indoorbike/IndoorBikeApp.kt) once.
- The container owns the long-lived singletons: `TrainerManager`, `WorkoutSessionManager`, `HistoryRepository`, `UserSettings`, `ActiveSessionStore`, `ActiveTelemetryStore`. Each has a `dispose()` that cancels its `SupervisorJob + Dispatchers.Default` scope.
- Composables read the container via `LocalAppContainer.current`. `MainActivity.setContent { CompositionLocalProvider(LocalAppContainer provides container) { … } }` installs it.
- This replaces the previous `object`-singleton pattern. Tests instantiate the same classes directly with fakes.

### Persistence
- **SQLite via SqlDelight**: `HistoryDatabase` (`schema in shared/.../db/History.sq`). Foreign keys are enabled in the Android driver via `setForeignKeyConstraintsEnabled(true)`; `WorkoutTelemetry` cascades on session delete.
- **DataStore Preferences** for `UserSettings` (FTP, paired trainer id) - replaces the legacy `SharedPreferences` singleton.
- **Active-session snapshot** (`ActiveSessionStore`): a small JSON file in `filesDir` written every 5 s during an active ride. Atomic write via tmp + rename. Records the ride's *position* (elapsed, segment index, FTP, bias) for process-death recovery. Deliberately kept by `stopWorkout()` so the unfinished-ride banner can offer a resume, and rewritten with `state = Completed` by `completeWorkout()` so a ride that finished but hasn't reached history yet is still recoverable.
- **Active-telemetry log** (`ActiveTelemetryStore`): an append-only CSV of the per-second samples, in `filesDir`, flushed on the same 5 s tick plus whenever the ride's timer stops. Records what the rider actually *did*, which the position snapshot cannot. Without it, resuming after a crash saved a full-length ride with a power curve covering only the post-resume minutes. Append-only rather than rewrite-in-place so a flush stays O(1) and a torn write can only damage the final record, which `ActiveTelemetryCodec.decode` drops. Reaped when a ride is abandoned or replaced, and at startup if no snapshot accompanies it.
- **Ride handover to history**: both recovery copies live until `WorkoutSessionManager.markRideSaved()`, which the UI calls only after `insertWorkoutSession` returns - the first moment the ride is durable anywhere else. Both save paths (tapping through the completion screen, and the banner's *Save ride* for a recovered one) go through `saveRideToHistory`, so a recovered ride is stored identically to one that finished normally and neither path can forget to release the copies.

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
- **First-sample watchdog**: a connected strap that never sends anything is a real and otherwise-invisible failure mode, so `armFirstSampleWatchdog` gives each connection `FIRST_SAMPLE_TIMEOUT_MS` (15 s) to move `lastSampleEpochMs`. If nothing arrives it drops the link and lets the reconnect supervisor rebuild the peripheral; after `MAX_SILENT_CONNECTS` (3) attempts it stops cycling and raises `sensorSilent`, which the UI turns into "restart Bluetooth" rather than an endless "waiting for first sample". The decision logic (`awaitFirstSample`, `firstSampleOutcome`) is lifted out of the class and unit-tested in [HrmFirstSampleWatchdogTest.kt](../shared/src/commonTest/kotlin/app/indoorbike/shared/ble/HrmFirstSampleWatchdogTest.kt).
  - **Why the app has to notice at all**: the underlying cause is on the phone. When the Bluetooth stack refuses to enable notifications, Kable 0.29 catches that refusal in an internal "suppress connection exceptions" block, so no exception reaches the app and the observation flow simply never emits. Every layer above believes the connection is healthy. Restarting the app does not help either, because the wedged state lives in the system Bluetooth process, not ours. Watching for the absence of data is the only signal available.

### Bluetooth availability gate

All three reconnect loops call `awaitBluetoothAvailable` (in [BleReconnectPolicy.kt](../shared/src/commonMain/kotlin/app/indoorbike/shared/ble/BleReconnectPolicy.kt)) before opening a scan window, parking on Kable's `Bluetooth.availability` rather than scanning into a disabled radio. It returns whether it had to wait, and a caller that waited resets its backoff to `INITIAL_BACKOFF_MS`.

This exists because the windowed-scan backoff alone cannot serve both requirements. With the adapter off a scan throws immediately instead of running its window, so retrying on the normal cadence spins and writes a failure line each time, which is what the growing backoff was protecting against. But that same growth is charged against the sensor: measured on a Fairphone 4, the loop waited about 45 s after Bluetooth returned before scanning again. Since the silent-strap UI now tells riders to restart Bluetooth, that delay read as the advice failing. Parking on the adapter fixes both ends, and the same measurement after the change was 95 ms from adapter-on to resumed scanning.

- Time spent unable to scan is not evidence the device is absent, which is why the wait resets the backoff rather than extending it.
- The reason is logged once per park, so a report shows *why* nothing was happening. Below API 31 this covers location services being disabled too, where a BLE scan returns nothing rather than failing, so the previous behaviour was to search forever and find nothing with no trace anywhere.
- A `BluetoothDisabledException` thrown mid-window is logged at info without its stack: it is expected, self-explanatory, and the stack alone ran to about fifteen lines of a 300-line buffer.

### Kable logging bridge

[`KableLogging.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/ble/KableLogging.kt) routes Kable's internal logging into `Log`, and so into the diagnostics buffer a bug report attaches. Without it, the failure described above leaves no trace anywhere: Kable's verbose `Suppressed failure` line is the only record it exists.

- Peripherals log at `Logging.Level.Events`, which is the lowest level at which Kable emits its verbose lines. Scans log at `Warnings`, because each scan window would otherwise add a start and a stop line every few seconds.
- `debug` is dropped by the engine. Every per-operation GATT callback logs at that level, and at one notification per second per characteristic it would flush the 300-line ring buffer within minutes of riding.
- Payload bytes never reach the log: Kable only renders them at `Level.Data`, which is never requested. This is deliberate, since heart rate is a body metric and these lines can leave the device in a report.
- Device identifiers are redacted via `redactDeviceId`. Kable defaults its log prefix to the platform identifier, which on Android is the hardware address, so `peripheralLogging` always passes an explicit redacted one.

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

### Free ride ("just ride")

An open-ended ERG session with no workout behind it. Defined in [`shared/.../workout/FreeRide.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/workout/FreeRide.kt); entered from the **Just ride** card on the Rides tab, which is trainer-gated the same way the Up next hero is.

- **Modelled as a synthetic single-segment `Workout`** (`id = "free-ride"`, one segment of `FREE_RIDE_MAX_SECONDS` = 6 h at `FREE_RIDE_START_PERCENT_FTP` = 40 %) rather than as a second kind of session. Everything that already keys off an active workout - the timer, telemetry capture, the crash-recovery snapshot, the foreground service, `saveRideToHistory` - therefore works unchanged. The six-hour segment is a backstop, not a plan: it is past the FGS's own four-hour wake-lock cap, so in practice the rider always ends the ride first.
- **The segment's FTP percentage is not what the trainer holds.** `WorkoutSessionManager.freeRideTargetWatts` is, opened at `freeRideStartWatts(ftp)` by `prepareFreeRide(ftp)` and moved by `adjustFreeRideTarget(deltaWatts)` in `FREE_RIDE_STEP_WATTS` (10 W) steps, clamped to `FREE_RIDE_MIN_WATTS..FREE_RIDE_MAX_WATTS` (30..1000). `updateTrainerTarget()` branches on `Workout.isFreeRide`. Unlike `adjustDifficulty`, the stepper works in every state, because the rider picks their opening effort while the HUD is still in "pedal to start" standby.
- **`RideScreen` divergences**, all driven by `workout?.isFreeRide`: the header clock drops the `/ total`; the Skip button is hidden (its one segment is the whole session, so skipping would end the ride); **End & save** is offered while Active as well as paused, since ending *is* how the mode finishes; the zone colour is read off the chosen wattage rather than the fixed segment percentage; the TARGET pill shows `freeRideTargetWatts` rather than `trainerManager.targetPower`, so it isn't stuck on "0 W" before the ride goes active; and the segment label / up-next / `RidgeTimeline` block is omitted entirely. A single `adjustEffort` lambda decides what a ± step means, so the in-app stepper and the Zwift Click can't drift apart.
- **Recovery.** `ActiveSessionSnapshot.freeRideTargetWatts` (nullable, defaulted, so older snapshots decode) persists the wattage alongside the position, and a resumed free ride comes back on it rather than at the opening 40 %. The synthetic workout is deliberately *not* in the rider's library, so `UnfinishedRideBanner` and anything else recovering a ride resolves it via `resolveRideWorkout(workoutId, library)`; the banner shows time ridden instead of a progress bar.
- **Not being in the library is load-bearing elsewhere too**: history's "Ride again" is already gated on the workout existing, and the FIT/TCX exporters already fall back to a single whole-ride lap when the workout is null - both of which are the right answer for a ride that had no plan. `WorkoutOverviewScreen` additionally drops its Power Adherence section for a free ride, since an empty chart there reads as missing data rather than as an answer.

### Simulation (route rides)

The third riding mode: a real-world GPX route under the wheels, with the road rather than a plan deciding what the legs are asked for. The trainer is **not** in ERG for this mode - nothing holds the rider to a wattage - so the whole mode hangs off a different control path.

**Where the pieces live**

| Concern | File |
|---|---|
| Route model, distance, smoothed elevation + gradient profiles | [`shared/.../route/RouteTrack.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/route/RouteTrack.kt) |
| GPX reading (hand-rolled, `commonMain`) | [`shared/.../route/GpxParser.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/route/GpxParser.kt) |
| Speed from power on a gradient, with momentum | [`shared/.../physics/RideDynamics.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/physics/RideDynamics.kt) |
| Virtual drivetrain | [`shared/.../simulation/VirtualGears.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/simulation/VirtualGears.kt) |
| The ride loop | [`shared/.../simulation/SimulationEngine.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/simulation/SimulationEngine.kt) |
| FTMS 0x11 encoding | [`shared/.../ble/FtmsSimulation.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/ble/FtmsSimulation.kt) |
| Synthetic workout | [`shared/.../workout/SimulationRide.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/workout/SimulationRide.kt) |
| Bundled routes | [`android/.../route/RouteCatalog.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/route/RouteCatalog.kt), `assets/routes/` |
| HUD | [`android/.../ui/screens/simulation/`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/simulation/) |

**The loop.** `SimulationEngine` ticks at 4 Hz (not the session manager's 1 Hz: at 1 Hz a descent jumps 17 m per frame and the map stutters) and closes a circle:

1. Read the power the trainer reports.
2. Integrate it against the gradient at the current position (`RideDynamics.step`) to get speed, and speed against time to get a new position.
3. Look up the gradient there, scale it by the rider's virtual gear and their real mass, and command that road back to the trainer as FTMS **Set Indoor Bike Simulation Parameters (0x11)**.
4. The rider feels the hill, pushes harder or shifts, and step 1 sees it.

**Physics.** `RideDynamics` integrates `m*dv/dt = P*eta/v - m*g*sin(theta) - Crr*m*g*cos(theta) - 0.5*rho*CdA*v^2` rather than solving for its fixed point, which is what gives momentum: coasting over a crest, accelerating down a descent, and a standing start that takes a moment. It reuses `VirtualSpeed`'s road constants (made public for exactly this), so a flat stretch of a route settles at the same speed a workout HUD would show for the same watts. Guards: the drive term's divisor is floored at `MIN_DRIVE_SPEED_MPS` (P/v is infinite at a standstill), descents cap at `MAX_DESCENT_SPEED_MPS`, a rider who stops on a climb stops rather than rolling backwards, and a single `step` never integrates more than two seconds so a frozen process cannot teleport anyone down the route.

**Gradient smoothing is not cosmetic.** Consumer GPS altitude wanders by a metre or two between points a few tens of metres apart; differentiated raw, that is tens of per cent of gradient on a flat road, and the rider feels it directly as resistance that flutters for no reason. `routeTrackOf` resamples elevation onto a fixed 10 m grid, applies a 50 m moving average either side, takes a centred difference over 40 m, and clamps the result to 30 %. Ascent is measured on the smoothed profile for the same reason.

**The trainer is commanded a force, not a road.** This is the single most important thing in the mode, and the first version got it wrong. Passing the route's gradient straight through and letting the machine's own road model do the rest fails on a real bike in two ways the rider feels at once:

- *Shifting on the flat did nothing.* The gradient is zero there, so scaling it by the gear scaled zero. All that was left were the rolling and aerodynamic terms, worth a few newtons across the whole cassette.
- *The trainer's aerodynamic term comes from its own flywheel speed*, which reflects the real gear the bike is parked in, not the virtual one. On a descent in a small real gear the flywheel barely turns, that term collapses, and the rider spins out against nothing.

So the road model lives in `SimulationEngine`, where the *virtual* speed is known. `RideDynamics.roadForceNewtons` gives the force the road is really pushing back with; the gear multiplies it; that force is what goes to the trainer. Rolling resistance and the wind coefficient are sent as **zero**, so the machine's own speed cannot re-enter the answer, and the force is expressed as a grade because FTMS has no force field - grade is the one term that produces a speed-independent force. It is divided by `ASSUMED_TRAINER_MASS_KG` (84 kg, the industry default) because the rider's real mass is already inside the force. A small `FREEWHEEL_DRAG_NEWTONS` floor, scaled by the gear, keeps the pedals from going dead on a descent and gives a big gear something to push against there.

**The ceiling is soft, and that is a bug fix rather than a refinement.** Arriving at a climb off a fast descent, in a big gear, stacks gravity, a large aerodynamic term and the gear multiplier into a force well past anything a trainer will apply. A hard clamp there made the first six downshifts command exactly the same number: the rider worked the shifter in the one moment they most needed it to respond and felt nothing. Above `SOFT_KNEE_NEWTONS` the curve now compresses (`tanh`) towards `MAX_TRAINER_FORCE_NEWTONS` instead of clipping, so it is strictly increasing and every gear keeps its own force. Below the knee, which is all ordinary riding, nothing is changed.

**Virtual gears are a load multiplier, nothing else.** A gear does not create speed: on a real bike, speed comes from power and the road, and the gear decides how power splits between force and cadence. So `Drivetrain.loadFactor(index)` scales that road force. Shift up, the trainer pushes back harder, holding cadence costs more watts, and those watts move the rider up the route faster.

**Gearing is real teeth**, not an abstract ladder: [`Drivetrain`](../shared/src/commonMain/kotlin/app/indoorbike/shared/simulation/Drivetrain.kt) holds chainrings, sprockets and a wheel circumference, with presets for a road double, a 1x gravel bike and a 1x mountain bike. Combinations within 2.5 % of one already in the ladder are dropped, so a 2x gives about twenty distinct gears rather than twenty-four near-duplicates. Two derived choices matter:

- `ASSUMED_REAL_DEVELOPMENT_M` is the gear the rider's own bike is assumed to be in on the trainer, and every load factor is a ratio against it. It is one constant shared by all presets rather than a position in each preset's ladder, which is what makes the presets mean something against each other: a mountain bike really does have less at the top and more at the bottom than a road bike. It is deliberately *low*, because assuming too high a real gear leaves the virtual top gear with nothing to give and the rider spinning out on descents. This is the one number to expose if riders ever want to say which gear their bike is actually in.
- `startIndex` is chosen by cadence rather than by ladder position: the gear four up from the bottom is a fine opener on a road bike and a spun-out 130 rpm on a mountain bike.

**Rider and bike mass** come from Settings: body weight as before, plus a bike weight and a drivetrain on their own [simulation settings page](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/SimulationSettingsScreen.kt). Both affect only a route ride; a workout and a free ride are pure wattage.

**Coasting is riding.** Auto-pause normally fires after three seconds without pedalling, which on a route would stop the ride every time the rider crested a hill and freewheeled. `WorkoutSessionManager` therefore treats a simulated ride still rolling above `COASTING_MPS` as active, so the pause only arrives once the bike has actually stopped.

**Control-point traffic is rate limited** two ways: routine sends never more often than `MIN_SEND_INTERVAL_MS`, and in between only when the commanded force moved by 1.5 N or the rider shifted, with an unconditional restatement every 5 s (a trainer that reconnected has forgotten it). A shift jumps that queue on its own much shorter `MIN_SHIFT_INTERVAL_MS` floor: on a descent the force changes every tick, so routine sends run at the floor continuously and a shift used to wait for the next slot, which is long enough to feel like the shifter is not working. A shift landing inside even that floor is remembered rather than dropped, so working the shifter coalesces into one write instead of losing gears. A control-point write shares the GATT queue with the telemetry notifications, and flooding it is what makes cadence drop out.

**Session integration** follows the free-ride precedent: a synthetic single-segment `Workout` with `id = "simulation:<routeId>"`, so the timer, telemetry, crash recovery, foreground service and save-to-history paths are unchanged. The differences:

- `WorkoutSessionManager.prepareSimulation(route, ftp)` loads the engine and wires `onRouteFinished` to `completeWorkout()` - **the route, not the clock, ends the ride**. The six-hour segment is only the wake-lock backstop.
- `updateTrainerTarget()` returns early for `Workout.isSimulation`: writing a wattage would drop the machine out of simulation mode and flatten the route's hills. Every transition into Active goes through `startModeControl()`, which hands the trainer to whichever thing owns it in this mode.
- `virtualDistanceMeters` comes from the engine rather than being integrated from power: on a route, distance is real ground rather than an estimate.
- `ActiveSessionSnapshot` gains `simulationDistanceMeters` / `simulationGearIndex` / `simulationElevationGainMeters` (all nullable and defaulted, so older snapshots decode). The route itself is not in the snapshot: it is named by the workout id and reloaded from `RouteCatalog` by the resume path in `MainActivity`. A route that has since gone missing resumes without one rather than refusing the resume.
- `resolveRideWorkout(workoutId, library, routeNames)` gained the third argument so the unfinished-ride banner can label a recovered route ride with the road's name.

**HUD.** `SimulationHud` replaces the workout HUD's metrics area wholesale. Reading order, the same in both layouts: the map (the only element that says *where*), gradient and gear side by side (the question and the rider's answer to it), the rider's own power / cadence / heart rate, then the elevation profile and progress pinned to the bottom. The zone colour is read off the power the rider is actually producing, since nothing is setting a target. The plus/minus affordance that means "difficulty" in a workout and "watts" in a free ride means "shift" here, through the same single `adjustEffort` lambda, so the Zwift Click is a shifter in this mode with no extra plumbing.

**The map** is OpenStreetMap raster tiles through [osmdroid](https://github.com/osmdroid/osmdroid) (Apache 2.0, no Play Services, no API key). Tiles are the only part of the mode that touches the network: the route, position, gradient and resistance all come from the GPX and the trainer, so with no connection the route draws on an empty ground and the ride is unaffected. osmdroid's cache and preferences are pointed at app-private storage (no storage permission), the user agent is set to the app id as OSM's tile policy requires, and tiles are drawn through an invert-desaturate-warm colour filter so a daylight map belongs on a dark HUD. Attribution is rendered over the map. The ridden polyline is *appended to* rather than rebuilt, since rewriting a few thousand points four times a second would allocate more than the map drawing does.

**Routes** are GPX files in `assets/routes/`, parsed once at startup by `RouteCatalog` and cached in memory (a 100 km route is on the order of a hundred kilobytes, and the alternative is a disk read between Start and the first pedal stroke). The parser is hand-rolled for the same reason the FIT and TCX encoders are: it has to run in `commonMain` and KMP has no XML parser in the standard library. It is deliberately generous - namespaced tags, either quote style, self-closing points, unknown children, `<rtept>` when there is no track, missing elevation as flat - and never throws; a file yielding fewer than two points simply does not appear in the catalogue.

**After the ride.** `WorkoutSession` gained `routeDistanceMeters` and `routeElevationGainMeters` (migration `4.sqm`): the one thing about a route ride nothing else can reconstruct once the engine is torn down, since the route itself is named by the workout id and reloaded from `RouteCatalog`. Both the post-ride summary and the share image render [`RouteSummaryPanel`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/simulation/RouteSummaryPanel.kt): the route's shape, how much of it was ridden, and the elevation profile, labelled **VIRTUAL ROUTE** with a line saying the ride happened indoors. The shape is drawn ([`RouteShape`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/simulation/RouteShape.kt), Web Mercator) rather than mapped: tiles need a network round trip a summary should not wait on, do not capture reliably into a bitmap, and carry an attribution obligation in a shared image that a drawn line does not.

**Not yet done.** A saved simulated ride carries power, cadence and heart rate like any other, but no per-sample GPS track or altitude: `TelemetryPoint` is the history schema and adding columns to it is another migration. So a route ride still exports and syncs as an indoor ride. Worth doing, separately.

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

**Dependencies:** none added. Bitmap rendering uses `androidx.compose.ui:ui` (Compose 1.7+ `GraphicsLayer.toImageBitmap`), persistence uses `MediaStore` + AndroidX `FileProvider`. No analytics, no Firebase Dynamic Links, no network share endpoints - the user picks the destination via the system chooser.

### Home screen and the pickers

The Ride tab is a chooser, not a list. [`HomeScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/HomeScreen.kt) offers Workout / Route / Just ride and nothing else above the fold; the specific workout or road is chosen on the next screen. The old `WorkoutListScreen` was split rather than rewritten: search, the duration and tag filters and the compact rows became [`WorkoutPickerScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/WorkoutPickerScreen.kt) unchanged, the welcome banner and first-run sheet became [`Onboarding.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/Onboarding.kt), and the up-next hero, quick-pick shelf, trainer chip and mode cards were deleted, because all four existed only to make a list serve as a landing screen. `RouteLibraryScreen` was already the route picker.

Riding stays trainer-gated. The two pickers are browsable without one, since choosing is not riding, but Just ride starts immediately and so checks on the home screen itself.

**Device status is one implementation.** [`DeviceStatusCard`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/components/DeviceStatus.kt) was lifted out of the Settings tab when home came to need it, along with the nine state-to-label helpers, which the pairing rows in Settings still import. Those labels are where the awkward truths live: a strap can be connected and sending nothing, and a controller can pair, report itself connected and answer no button press. Both read as "Connected" to anything that only inspects the connection state, which is precisely the case worth warning about before a ride.

**Dismissal.** The first-ride card persists through `hasDismissedStarterCard`. The unfinished-ride banner does not persist and is keyed to the ride, via `AppContainer.dismissedUnfinishedRideStart`: dismissing means "not now", so the banner returns next launch while the ride is still on disk, and a rider who waves away one unfinished ride and then abandons a second is still told about the second. Throwing a ride away remains the separate Abandon button.

### Followed repositories

[`FollowedRepositories`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/FollowedRepositories.kt) brings back a subscription concept that was removed once, but only the half that was never the problem. The original objection was to *content*: mirroring a repo let a maintainer change what sat on someone's device. Following restores only *knowing* - a followed repo is checked and reported on, and nothing enters the library until the rider opens it and taps Add, so every local copy is still the static import `WorkoutSource` and `RouteSource` describe.

**Cost of a check.** Naively this means downloading every bundle of every followed repo on every look, which for a 400-workout repository is a real transfer to answer a question whose answer is almost always "no". Instead a check fetches the manifest, which is small by design, and compares each bundle's `version` against the version last seen; a bundle downloads only when that number moves. This is the first use of a field the format has carried since v2 and never read, and it is why the contributor docs tell maintainers to bump it whenever bundle content changes. A maintainer who does not is invisible to the check, by contract.

**What "new" means.** Everything ever fetched from a repo is *known*; everything the rider has browsed is *seen*; new is the difference. Refreshing never marks anything seen, or nothing would stay new long enough to be noticed - only opening the repo browser does, via `markSeen`. Both sets persist under `filesDir/followed/seen/<id>.json`, so a count survives the app being killed.

**When it runs.** On home screen open, throttled to `REFRESH_INTERVAL_MS` (3 h) unless forced. No background worker and no scheduled work: nothing runs while the app is closed, which keeps the no-Google rule and the battery story simple. The registry itself, being about a kilobyte, is refetched every time the repositories screen opens - it was previously fetched only when no cache existed at all, which silently froze the discoverable list on the day of first use.

### Workout library - sources, import, repositories

The Rides list aggregates workouts from two local sources, both merged through [`WorkoutLibrary`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/WorkoutLibrary.kt) (`AppContainer.workoutLibrary`):

1. **Bundled** - JSON files in `assets/workouts*.json`. Loaded once at startup via the same shared `WorkoutRepository.parseWorkouts()` parser as before.
2. **Imported** - JSON files persisted to `filesDir/user_workouts/<sanitised-id>.json` by [`WorkoutImporter`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/WorkoutImporter.kt). Atomic write via `tmp` + rename. Filename uses the workout's own id so re-importing is idempotent. Sources include the file picker, share-target, QR scan, deep-link, **and** the in-app community-repo browser - when a rider taps Add on a workout in a remote repo, [`WorkoutImporter.saveWorkoutFromRepo`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/WorkoutImporter.kt) writes the JSON plus a `<id>.meta.json` sidecar carrying the source repo's id + display name for attribution. The local copy is the source of truth from then on; it isn't auto-updated when the upstream repo changes.

Duplicate workout ids are de-duped first-wins with priority **Imported > Bundled** - an explicit user import wins over a bundled copy with the same id.

**Source attribution** - `WorkoutLibrary.entries` exposes a `StateFlow<List<WorkoutEntry>>` where each entry carries a `WorkoutSource` (`Bundled` / `Imported(sourceRepoId, sourceRepoName)`). The Rides list tag-eyebrow row appends "· IMPORTED" or the repo's display name in ember accent for imported workouts; bundled stay clean.

[`RepositoryManager`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/RepositoryManager.kt) keeps a small registry cache at `filesDir/registry/cache.json` (the community list shown on the More Workouts screen) and provides an on-demand `fetchRepoWorkouts(manifestUrl)` that downloads a manifest plus its bundles in memory for the remote-repo browser. **Nothing else is persisted** - earlier versions kept per-repo bundles under `filesDir/repos/<id>/...` for incremental sync, but that on-disk mirror was removed when the explicit-add model replaced subscriptions.

**On-disk schemas** (defined in [`shared/.../repo/Manifests.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/repo/Manifests.kt)):

- `RepositoryManifest` (schemaVersion **2**, current) - `id`, `name`, `description?`, `author?`, `homepage?`, `updatedEpoch?`, **`bundles: List<WorkoutBundleEntry>`**. The manifest itself contains no segments; that's the whole point of v2. `homepage?` and `updatedEpoch?` are **website-only**: Android parses them into the data class and never reads them again, so a contributor's bad value (the classic being `updatedEpoch` in seconds, which renders as "updated 56 years ago") is invisible in the app and only shows in the community browser. `updatedEpoch` is milliseconds.
- `WorkoutBundleEntry` - `id`, `name?`, `schemaVersion` (bundle file format, currently 1 = bare JSON array of `Workout` objects), `version` (content version - bumped on any change to any workout in the bundle), `url` (absolute or relative to the manifest URL).
- `Workout` (schemaVersion **2**, current) - `schemaVersion: Int = 1`, `id`, `name`, `description?`, `screenshot?`, `url?`, `segments: List<PowerZone>`. Workouts whose `schemaVersion` exceeds `WORKOUT_SCHEMA_VERSION_MAX_SUPPORTED` are silently skipped at parse time so future formats can land in an existing bundle without breaking older clients.
- **Workout v2** added the optional cadence target on `PowerZone` (`cadenceMinRpm` / `cadenceMaxRpm`, both nullable). Read it through `PowerZone.cadenceTarget`, which normalises the pair into a `CadenceTarget` and owns the rules the UI must not re-derive: either bound alone is a single target, a single target is judged against a ±3 rpm band (nobody holds an exact rpm), a reversed pair is read as the range the author meant, and anything outside 20-200 rpm is treated as absent. Writers stamp the version via `workoutSchemaVersionFor(segments)`, which only returns 2 when a segment actually carries a target - a plain power workout stays v1 and therefore loadable by every build ever shipped.
- `RegistryIndex` - `repositories: List<RegistryEntry>` where each entry has `id`, `name`, `description?`, `author?`, `manifestUrl`, `tags`, `homepage?`. The entry's `homepage?` is currently **read by nobody**: the community browser's repo page links `manifest.homepage` instead, so a contributor can add or drop the link by editing their own file rather than opening a registry pull request. The field is kept because the registry list renders cards without fetching any manifests, so it's the only place a card-level outbound link could come from.

**Schema version v1 (legacy, no longer accepted)**: workouts were embedded inline in the manifest. `parseRepositoryManifest` throws on any `schemaVersion > REPO_MANIFEST_SCHEMA_VERSION_MAX_SUPPORTED` - pre-launch break, no migration path, repos must move to v2.

`ManifestParser.classify(text)` heuristically identifies whether arbitrary JSON is a single workout, list of workouts (= a bundle), list of routes (a top-level array containing `gpxUrl`), repository manifest (top-level `bundles` **or** `routeBundles` key), or registry index - used by the universal import pipeline so the user doesn't pick a mode up front.

**Community registry URL** - hardcoded `BuildConfig.REGISTRY_URL = "https://indoorbike.app/registry.json"` ([app/build.gradle.kts](../mobile_clients/android/build.gradle.kts)). Absence is graceful: `RepositoryManager.refreshRegistry()` returns `RegistryState.Failed(error, cachedIndex)` and the Library's "Browse community" section shows an empty hint pointing at the Scan QR / Add by URL escape hatches. When the URL goes live, the next refresh picks it up automatically - no app update needed.

**Networking** - [`HttpFetcher`](../mobile_clients/android/src/main/kotlin/app/indoorbike/library/HttpFetcher.kt) is a thin `HttpURLConnection` wrapper. No OkHttp / Ktor dependency, HTTPS-only (rejects `http://`), 12s connect/read timeouts, 2 MB body cap so a malicious URL can't OOM the device. `downloadTo()` is the exception for route GPX: it streams to a `.part` file and renames on completion (so a dead connection can't leave a truncated route where a valid one is expected), caps separately at `MAX_ROUTE_BYTES` = 12 MB, and returns a SHA-256 computed on the way past. Redirects stay unfollowed here as everywhere, which bites more often for routes since a `gpxUrl` may point at a third-party host - the thrown message names that case rather than surfacing a bare failure.

### Community routes - listing separate from data

Routes reuse the whole repository spine (registry → manifest → bundle → explicit add → static local copy), with one structural difference: **a route listing does not contain the route**. A workout bundle *is* the workout; a GPX is three orders of magnitude bigger, so browsing fifty routes would cost tens of megabytes for a browse the rider might abandon. Metadata and geometry are therefore separate files, and a GPX is downloaded exactly once, for the one route a rider adds.

**Compatibility, and why the manifest version did not move.** `RepositoryManifest` stays at `schemaVersion` **2** and gained an optional `routeBundles: List<RouteBundleEntry>`. Bumping to v3 would trip the "update the app" guard in `parseRepositoryManifest` on every already-shipped build, so a repo that added a single route would lose its *workouts* on older installs. Instead old builds skip the field through `ignoreUnknownKeys` and never notice. Routes version independently through `RouteEntry.schemaVersion` and `RouteBundleEntry.schemaVersion`, the same skip-if-too-new pattern workout bundles already use - so one future-format route costs the rider that route and nothing else. The practical payoff: **a repository already in the registry can start publishing routes with no pull request at all**; only the optional `RegistryEntry.content` hint (which makes the Routes button appear on its card) is worth a one-line registry PR.

**On-disk schemas** (defined in [`shared/.../repo/RouteManifests.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/repo/RouteManifests.kt)):

- `RouteBundleEntry` - `id`, `name?`, `schemaVersion`, `version`, `url`. Identical in shape to `WorkoutBundleEntry`; the version lives on the pointer so an unsupported bundle is skipped *without being downloaded*.
- `RouteEntry` (schemaVersion **1**) - identity (`id`, `name`, `description?`, `tags`, `place?`, `version`), the pointer (`gpxUrl`, `gpxBytes?`, `gpxSha256?`), the numbers a picker shows (`distanceMeters`, `ascentMeters`, `descentMeters`, min/max elevation, `maxGradientPercent`, `loop`), `gradientMeters` (see below), drawing aids (`profile` 64 samples, `shape` as an encoded polyline, `bounds`), and `attribution`.
- `RouteAttribution` - `source?`, `url?`, `licence?`, `modified`. Route geometry is usually derived from a licensed database (OSM and EuroVelo tracks are ODbL), so credit is part of the format rather than something a maintainer must remember to put in a description. `RouteSource.creditLine` renders it on the card for as long as the route is installed.

**`gradientMeters` is the interesting field.** The picker puts an estimated ride time on every card *before any GPX exists on the device*, computed from the rider's own FTP and weight. That cannot come from distance and total ascent, because time on a road is non-linear in gradient: 500 m of climbing as one steady 5% drag and the same 500 m as twenty 12% ramps are different rides, and the minutes lost climbing are never repaid on the descent. So the entry carries a 14-bucket histogram of metres-per-steepness (fixed edges in `RouteGradientBuckets`, part of the schema so a histogram is 14 bare numbers), and [`RouteEstimate`](../shared/src/commonMain/kotlin/app/indoorbike/shared/route/RouteEstimate.kt) sums `distance / steadySpeed(P, gradient, mass)` per bucket at `ESTIMATE_FTP_FRACTION` (0.65) of threshold. `steadySpeedMps` is the fixed point of `RideDynamics` and shares `VirtualSpeed`'s constants, so a 0% bucket is ridden at exactly the speed the workout HUD shows for those watts - estimate and ride cannot disagree about the same road.

**Generation parity.** [`RouteStats`](../shared/src/commonMain/kotlin/app/indoorbike/shared/route/RouteStats.kt) is the reference implementation: it turns a parsed `RouteTrack` into a `RouteEntry`, measuring everything on the *smoothed* profile rather than raw points. The website's [`assets/routelib.js`](../website/assets/routelib.js) is a JavaScript port of it plus `RouteTrack`, `RouteEstimate` and `RoutePolyline`, used by both the community browser and the publishing tool. **These have to stay in step**: a maintainer publishes numbers generated in JS and a rider sees the route those numbers described. When the Kotlin changes, change the JS with it and bump `ROUTE_SCHEMA_VERSION` on both sides. `RoutePolyline` is the standard precision-5 encoding, byte-exact against the reference vector in `RouteListingTest` for exactly this reason.

**Android side** - [`RouteImporter`](../mobile_clients/android/src/main/kotlin/app/indoorbike/route/RouteImporter.kt) persists to `filesDir/user_routes/<id>.gpx` plus a `<id>.meta.json` sidecar (provenance and attribution only - *not* a cached copy of the listing, which would be a second staler answer to questions the track already answers). Download order matters: staging file → hash check → parse → only then rename into place, so a corrupt transfer cannot destroy a route the rider already had. `RouteCatalog` merges bundled + imported (imported wins an id clash) and exposes `sourceOf(id)` for attribution. `RepositoryManager.fetchRepoRoutes(manifestUrl)` returns listings with each `gpxUrl` resolved against the bundle it came from.

**UI** - [`RemoteRouteBrowserScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/RemoteRouteBrowserScreen.kt) browses one repo's routes (reached from the Routes pill on a registry card); [`RouteLibraryScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/RouteLibraryScreen.kt) is the installed-route selector, reached from the "All N routes" link on the rides list once more than one is installed. Both draw cards from listing data via [`RoutePreview`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/components/RoutePreview.kt) (`RouteProfileSpark`, `RouteShapeThumb`) rather than the `RouteTrack`-based composables in `ui/screens/simulation/`, since during a browse the parsed track is precisely what the app is trying not to download.

**Website** - the community browser gained route cards and a `#repo=<id>&route=<id>` detail view; [`website/routetool/`](../website/routetool/index.html) is the maintainer tool: drop a GPX, get the entry JSON plus your manifest with the pointer merged in. It is entirely client-side (the site is static on CodeFloe Pages), so no route file is uploaded anywhere. The single exception is the opt-in elevation backfill, which posts coordinates to Open-Meteo's Copernicus DEM endpoint - off by default, because most open route sources (OSM extracts, EuroVelo tracks) carry no `<ele>` and would otherwise simulate dead flat.

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

[`QrScannerScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/QrScannerScreen.kt) uses **CameraX** (`camera-camera2` / `camera-lifecycle` / `camera-view`, all 1.4.0, Apache 2.0, no Play Services) for the preview and **ZXing core** 3.5.3 (`com.google.zxing:core` - Apache 2.0, *not* a Google product despite the package; pure Java, no Play Services) for decoding. The analyzer feeds `PlanarYUVLuminanceSource` into `MultiFormatReader.decode()` per frame (back-pressure: latest only). On a hit it hops to main, decodes-or-fetches the URL, and routes the result through the same `WorkoutImporter` pipeline.

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

**Permissions** - `CAMERA` declared in manifest with `<uses-feature android:required="false">` so devices without a camera (rare but possible) aren't filtered out by any store index that reads the manifest. Runtime prompt is requested on screen entry; "Not now" leaves the screen with a polite no-permission state.

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

- [`FitWriter.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/fit/FitWriter.kt) - low-level: 14-byte header (with header CRC), little-endian integer helpers, definition-message builder, data-message header byte, the FIT-spec nibble-table CRC-16, and `finalizeAndCrc()` that patches data-size + header-CRC + appends file-CRC. `FitBaseType`, `FitMessage` (global message numbers), `FitInvalid` (the spec's per-type "no value" sentinels), `FIT_EPOCH_OFFSET_SECONDS = 631065600` (UTC 1989-12-31). `writeDefinition` optionally takes `developerFields`, which sets bit 5 of the definition header and appends the developer-field descriptors after the standard ones.
- [`RideFitBuilder.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/fit/RideFitBuilder.kt) - high-level `build(session, telemetry, workout, ftp): ByteArray`. Emits file_id (manufacturer = 255 *development*), file_creator, the developer-field declarations, timer-start event, one record per 1Hz telemetry sample (timestamp + power + HR + cadence), one lap per workout segment (interleaved with the records covering that segment), timer-stop event, session message (sport=cycling, sub_sport=indoor_cycling, plus avg/max power/HR/cadence, NP, IF, TSS×10, kJ, FTP), and an activity wrapper. Falls back to a single whole-ride lap when there's no structured workout.
- [`FitWriterTest.kt`](../shared/src/commonTest/kotlin/app/indoorbike/shared/fit/FitWriterTest.kt) - verifies header magic, header-CRC, file-CRC, data-size patching, round-trips a 30-min synthetic ride, and decodes the emitted file with a minimal reader to assert the per-lap target values. That reader matters: the CRC assertions pass on any self-consistent bytes, so only decoding catches a malformed developer-field section. Run via `.\gradlew.bat :shared:allTests`.

**Segment targets travel as developer fields on each lap.** A lap is one workout segment, and the FIT activity profile has no native field for what a segment *asked* for, so a structured session used to export as an anonymous power trace: `emitLap` even accepted a `plannedPct` argument that nothing ever wrote. The builder now declares a `developer_data_id` namespace plus four `field_description` messages, and every lap carries `target_power` (watts), `target_power_pct` (%FTP), `target_cadence_low` and `target_cadence_high` (rpm). Cadence bounds are normalised through [`CadenceTarget.of`](../shared/src/commonMain/kotlin/app/indoorbike/shared/workout/CadenceTarget.kt), so a single-value target exports as equal bounds and an out-of-range one as absent. Segments with no ask (and unstructured rides) write the spec's invalid sentinel rather than `0`, which readers show as "no target" instead of "target: zero watts". `APPLICATION_ID` is the namespace key and must never change: readers key custom fields on it, so a new value orphans every file already exported.

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

**Settings UI.** New `CLOUD SYNC` section between `DEVICES` and `ABOUT` in [`SettingsTab.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/SettingsTab.kt) hosting an `IntervalsIcuCard`: athlete-id input, API-key input (`PasswordVisualTransformation` with a show/hide eye toggle), and an auto-upload `Switch` that's disabled until both credential fields are filled. Copy explicitly states the posture ("only contacts intervals.icu when an upload is triggered").

**Upload triggers.** [`WorkoutOverviewScreen`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/WorkoutOverviewScreen.kt):

- **Manual** - `IntervalsUploadCard` at the bottom of the scroll content, visible only when credentials are configured. "Upload" button posts a Toast with the result.
- **Auto** - a `LaunchedEffect(activeSession.id, fromRide, autoUpload, intervalsConfigured)` fires once when the screen arrives from a finished ride. A `uploadAttemptedForSession` flag prevents config-change recompositions from re-firing the upload.

**Posture.** Intervals.icu is a non-free service, and the integration exists only if the rider configures it. Manifest declares `INTERNET` (already present for the registry feature). No third-party libs added - FIT writing is pure Kotlin in shared, multipart POST is `HttpURLConnection`. Auto-upload is off by default and disabled in the UI until both credentials are provided. Can be unilaterally disabled from Settings without losing locally-stored ride history.

### Backup & sync safety (anti-wipe guard)

The folder-based "Backup & sync" feature ([`SyncEngine`](../mobile_clients/android/src/main/kotlin/app/indoorbike/sync/SyncEngine.kt)) writes one slice per device (`indoorbike-device-<deviceId>.json`) and **infers deletes by diffing** local state against the device's previous own-slice: anything in the slice but absent locally is tombstoned ([`BackupSliceBuilder.computeSessionDeletes`](../shared/src/commonMain/kotlin/app/indoorbike/shared/sync/BackupSliceBuilder.kt)). That inference is correct for a deliberate in-app delete but dangerous when local data vanished for another reason (reinstall, cleared data, a failed DB migration): the diff reads as "the rider deleted everything" and the tombstones propagate the loss into the backup and every peer. Auto-sync runs on every launch, so the wipe could happen with no interaction.

Three layers now guard against that:

- **Suspicious-reset detection.** [`BackupSafety.isSuspiciousReset`](../shared/src/commonMain/kotlin/app/indoorbike/shared/sync/BackupSafety.kt) (pure, unit-tested) flags a round that would delete a large share of the backup - local emptied while the backup holds data, or a single round removing >= 5 items and >= half the backup. Normal one-off deletes don't trip it. When tripped, `SyncEngine` sets a persisted `UserSettings.syncPaused` flag and returns `SyncStatus.NeedsReview(local…, backup…)` **without writing anything** (local and folder both untouched).
- **Paused = no automated writes.** `syncNow()` is the single chokepoint for both the launch auto-sync and the manual button; while `syncPaused` it only does a read-only `assess()` and surfaces the review state. The rider resolves via `resolveRestoreFromBackup()` (pull the backup back onto this device, infer no deletes) or `resolveKeepLocal()` (confirm the deletes), both of which clear the flag. The Settings "Backup & sync" card renders the warning + both buttons, plus an always-available **Restore from backup** action.
- **One-deep slice backup.** [`SafSyncStorage.writeOwnSlice`](../mobile_clients/android/src/main/kotlin/app/indoorbike/sync/SafSyncStorage.kt) renames the existing slice to `…json.prev` (skipped by `readAllSlices`) before promoting the new one, so any overwrite is recoverable.

Note `deviceId` is a random UUID in DataStore, which Auto Backup **excludes** ([`data_extraction_rules.xml`](../mobile_clients/android/src/main/res/xml/data_extraction_rules.xml)); a true data wipe regenerates it, so the device writes a fresh slice rather than clobbering the old one. The guard above covers the residual case where the id survives while the DB is emptied.

### Google-free checklist

Everything here exists to keep the app runnable on a device with no Google services. See the rule in [Rules & Constraints](#rules--constraints); it outlived the F-Droid ambition that first prompted it.

- **Fonts**: bundled locally as OFL TTFs in `mobile_clients/android/src/main/res/font/` - no Google Fonts CDN, no runtime download. Sources are the OFL distribution at rest in `github.com/google/fonts`:
    - `bebas_neue_regular.ttf` (60 KB, static) - display headlines (`displayXl/L/M`, `h1`, `h2`).
    - `inter_variable.ttf` (856 KB, variable wght axis 100–900) - body text (`h3`, `body`, `bodyStrong`, `small`, `micro`, `eyebrow`).
    - `jetbrains_mono_variable.ttf` (183 KB, variable wght axis 100–800) - monospace ticks (`tick`, `mono`).
  Variable fonts are wired via `Font(R.font.…, weight = FontWeight.X, variationSettings = FontVariation.Settings(FontVariation.weight(N)))` in [`ui/theme/Type.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/theme/Type.kt). Requires `@OptIn(ExperimentalTextApi::class)` on Compose 1.7.5; the API graduates to stable in newer Compose versions.
- **Icons**: design specifies Lucide; current implementation uses `androidx.compose.material:material-icons-extended` (Apache 2.0) as a visual stand-in.
- **Mock trainer toggle**: in-ride mock toggle is gated behind `BuildConfig.DEBUG` so release builds don't expose dev affordances.
- **Maps**: OpenStreetMap raster tiles via `org.osmdroid:osmdroid-android` (Apache 2.0, on Maven Central, no Play Services and no API key). Google Maps is ruled out by the rule above, and would not work on a device without Play Services anyway. Pointed at app-private cache / preferences so it needs no storage permission, and sends a real user agent as OSM's tile usage policy requires. Only the map imagery needs the network; a simulated ride works offline without it.
- **Activity export**: FIT and TCX are written by hand-rolled pure-Kotlin encoders in `shared/` (no third-party dep); the rider exports a past activity to a file via the OS share sheet, or uploads to Intervals.icu. See "Activity export (FIT / TCX) + Intervals.icu cloud sync" above.

### Diagnostics & bug reports

There is no remote logger or crash reporter, by rule. `Log` ([`shared/.../util/Logger.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/util/Logger.kt)) writes to Logcat in debug builds only, and mirrors every line into [`DiagnosticsLog`](../shared/src/androidMain/kotlin/app/indoorbike/shared/util/DiagnosticsLog.kt) - a ring buffer of the last 300 lines, kept in **all** build types so release users have something to attach to a report.

**The buffer is also mirrored to disk, and this is the point.** A RAM-only buffer dies with the process, so a report drafted after a crash could only ever contain the launch that *followed* it - users sent reports whose "Recent logs" held nothing but `App start` and the auto-reconnect teardown three lines later. `DiagnosticsLog.attachStorage(filesDir/diagnostics)` runs first thing in `IndoorBikeApp.onCreate`, before the first log line. Lines batch to `diagnostics.log` every 5 s (so a hard kill costs at most 5 s of them); on the next launch that file is rotated to `diagnostics-previous.log` and offered to the report as the crashed run's log. Each file is capped at 128 KB and rotated rather than truncated. Both live in app-private storage, readable by no other app, and still leave the device only through a report the user composes.

Two companions close the rest of the gap:

- [`CrashHandler`](../shared/src/androidMain/kotlin/app/indoorbike/shared/util/CrashHandler.kt) - a default uncaught-exception handler that records the trace into the same log and forces a synchronous flush, then hands back to the platform handler so the process dies exactly as it otherwise would. Not a crash *reporter*: nothing is uploaded and the system dialog is unchanged.
- [`ProcessExitReasons`](../shared/src/androidMain/kotlin/app/indoorbike/shared/util/ProcessExitReasons.kt) - reads `ActivityManager.getHistoricalProcessExitReasons` (API 30+) for the last 3 deaths. This is the only source that can describe a crash from *outside* the process, so it distinguishes an unhandled exception from a low-memory kill from an ANR from the user swiping the app away, none of which our own logs can tell apart. Reports the reason, exit status, process importance at death (a ride killed at `foreground service` importance means something very different from one killed while `cached`), pss/rss, and the system-captured trace where one exists (capped at 120 lines).

"Report a problem" (Settings → FEEDBACK card, and a **Report** link on each in-app error banner) routes through [`BugReport`](../mobile_clients/android/src/main/kotlin/app/indoorbike/feedback/BugReport.kt), which assembles device info (model, Android version, app version), the recent process exits, the previous run's log, and this run's log, then opens an `ACTION_SEND` email draft to the developer (recipient/subject/body pre-filled, everything also attached as a `.txt` via the existing `FileProvider`). Nothing is sent automatically - the user reviews and sends. A [`ReportProblemDialog`](../mobile_clients/android/src/main/kotlin/app/indoorbike/feedback/ReportProblemDialog.kt) discloses what's collected before the draft opens.

**No personal data is ever logged** - this is a hard invariant, since these lines can leave the device. The app logs only technical state (BLE connection events, FTMS protocol bytes, parse failures, counts); body metrics / credentials / names are never logged, and BLE device identifiers are masked via [`redactDeviceId`](../shared/src/commonMain/kotlin/app/indoorbike/shared/util/Redaction.kt). Keep it that way when adding log calls.
