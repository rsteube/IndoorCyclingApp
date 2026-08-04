# Indoor Bike - test plan

This document covers v1.0 of the app. Two layers of testing:

1. **Automated smoke test** ([scripts/smoke_test.ps1](scripts/smoke_test.ps1)) - installs the debug APK, walks through onboarding + mock-trainer pair + starter ride, captures a screenshot at each step and a logcat tail. Catches "did the app crash on the happy path" but does not assert UI correctness.
2. **Manual checklist** (below) - exhaustive feature verification, organised by area. A human or LLM with screenshot access can tick through it.

The smoke test always runs against the `app.indoorbike.debug` install so a production build on the same device is left alone.

---

## Pre-flight

Before either layer:

- A device or emulator is connected. `adb devices` lists exactly one (or you'll pass `-DeviceId` to the script).
- For BLE flows: a Wahoo Kickr or other FTMS/CPS-compatible trainer; or rely on the **Mock Trainer** (built into the pairing screen) for everything that doesn't require real BLE.
- For HRM flows: a Bluetooth chest strap (Polar H10, Wahoo TICKR, Garmin HRM-Pro/Dual, etc.). Optional.

The repo's `gradlew.bat` and `adb` must be on PATH (PowerShell session). On Windows the build uses `gradlew.bat`, not `./gradlew`.

---

## Automated smoke test

```powershell
.\scripts\smoke_test.ps1                    # use the only connected device
.\scripts\smoke_test.ps1 -DeviceId 82bc875c # pick a specific device
.\scripts\smoke_test.ps1 -SkipBuild         # iterate without rebuilding
```

What it does:

1. `:mobile_clients:android:installDebug` - fresh APK on the device.
2. `pm clear app.indoorbike.debug` - resets prefs/database so the next launch is a true first-run.
3. `am start` the activity, wait for first frame.
4. Walks the welcome → onboarding sheet → "Don't know your FTP" → Save flow, screenshotting each state.
5. Settings → Devices → Trainer → Pair → **Use Mock Trainer**, screenshotting each state.
6. Rides → First Spin starter card → Start workout, screenshotting each state.
7. Dumps `logcat -d` to a file and greps for `FATAL` / `AndroidRuntime: FATAL`. Any hit fails the run.

Output lives at `build/test-artifacts/<timestamp>/`:

- `00-launch-welcome.png` ... `NN-ride-screen.png` - visual evidence of each state
- `logcat.txt` - full log dump for forensics
- `summary.txt` - per-step pass/fail line-log
- `build.log` - gradle output (only when `-SkipBuild` not passed)

**Limitations.** The script asserts only "no fatal exception" and "the expected button was tappable at this point". It does not check label text, layout correctness, or BLE behaviour. Use the manual checklist below for those.

---

## Manual test plan

Each numbered scenario is independent - start each from a clean state by either uninstalling and reinstalling, or `adb shell pm clear app.indoorbike.debug`.

### 1. First launch & onboarding

| Step | Action | Expected |
|------|--------|----------|
| 1.1 | Install debug APK (`./gradlew.bat :mobile_clients:android:installDebug`), launch | Lands on **Pick a workout** with a brand-tinted **Welcome to Indoor Bike** banner above the workout list |
| 1.2 | Tap **Get Started** | Bottom sheet slides up: `Set your FTP and weight` with FTP field (200 W default) and Weight field (75 kg default) |
| 1.3 | Tap **Don't know your FTP? Start at 150W** | FTP field updates to 150 |
| 1.4 | Type a too-low FTP (e.g. 30) | **Save & Continue** disabled (faded) |
| 1.5 | Restore valid values; tap **Save & Continue** | Sheet dismisses; welcome banner replaced by **Pair a trainer** nudge; an orange-bordered **START HERE** card appears as the first list item showing **First Spin** (15 min) |
| 1.6 | Re-launch the app | Welcome banner does **not** reappear (`hasCompletedOnboarding` persisted) |
| 1.7 | Re-clear data, hit Get Started, swipe-down to dismiss the sheet without tapping Save | Welcome banner is gone next launch (any sheet exposure marks the flow complete) |

### 2. Empty states

| Step | Action | Expected |
|------|--------|----------|
| 2.1 | After completing onboarding (no rides yet), tap **History** tab | "No rides yet" empty state with a **Pick a workout** button that returns to Rides |
| 2.2 | Tap **Progress** tab | "Ready to level up?" empty state with the same **Pick a workout** button |
| 2.3 | After at least one completed ride, return to History | Totals card + grouped session list visible; empty state gone |

### 3. Trainer pairing

| Step | Action | Expected |
|------|--------|----------|
| 3.1 | Settings → tap **Trainer** row | Bottom sheet shows the trainer's current state and a **Pair Trainer** primary button + helper copy |
| 3.2 | Tap **Pair Trainer** | Trainer pairing screen opens; permission prompt for `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` if not granted; then "Scan for Devices" button |
| 3.3 | Tap **Scan for Devices** with a real trainer powered on and pedalled | Trainer appears in the list within ~5 s; tap it; auto-back to Settings on connection |
| 3.4 | (Or) Tap **Use Mock Trainer** | Returns to Settings; status row shows **Mock Trainer · Mock connected** with a green dot |
| 3.5 | Tap Trainer row again → **Disconnect** | Status flips to Disconnecting then Not paired; sheet stays open |
| 3.6 | Tap **Forget Trainer** | Sheet closes; row shows "Trainer · Not paired" |

### 4. HRM pairing

| Step | Action | Expected |
|------|--------|----------|
| 4.1 | Settings → **Heart Rate Monitor** row → **Pair Heart Rate Monitor** | HRM pairing screen with permission prompt then "Scan for HRMs" |
| 4.2 | Wake the strap (snap on / tap sensor); tap **Scan for HRMs** | Strap appears as `HRM-Pro:NNNNNN` (Garmin) or by name (Polar / TICKR / etc). |
| 4.3 | Tap the strap | "Connecting…" then auto-back to Settings; HRM row shows live BPM after first sample |
| 4.4 | Disconnect / Forget from the HRM sheet | Same as trainer (state collapses, sheet behaves correctly) |
| 4.5 | Connect the strap, then take it off and let the contacts dry so it stops sending | Row keeps the last BPM, then the strap's own idle timeout drops the link and the reconnect loop takes over. This is the *healthy* path: it must not be reported as a fault |
| 4.6 | Silent-strap fault (see below for how to provoke it) | Row cycles Connected → Scanning → Connected up to three times over roughly a minute, then settles on **"Connected but sending nothing · restart Bluetooth"** with a red dot; the heart icon on the Rides tab goes dim rather than solid red |
| 4.7 | With 4.6 showing, toggle Bluetooth off and on | Strap reconnects and live BPM returns; the warning clears on its own without reopening the app |
| 4.7b | With any sensor paired, turn Bluetooth off, wait a minute, turn it back on | While off: one `Waiting for Bluetooth (Off)` line and nothing further, no repeated scan failures. On return: scanning resumes within a fraction of a second, not after a backoff. Measured on a Fairphone 4 at 95 ms |
| 4.8 | Draft a bug report while 4.6 is showing (Settings → Report a problem) | "Recent logs" contains `HeartRateMonitor` lines about the silent connects, plus `Kable/` lines covering connect and observation setup. No line contains a full device address, and none contains a heart rate value |

**Provoking the silent-strap fault (4.6).** It reproduces on its own on a phone whose Bluetooth stack has wedged, which is not something you can arrange on demand. To test the handling deliberately, either comment out the `_lastSampleEpochMs.value = ...` assignment in `HeartRateMonitor.startHeartRateObserve` (the app then behaves exactly as if notifications were never delivered), or point `FIRST_SAMPLE_TIMEOUT_MS` at a value shorter than your strap's notification interval. The fault this simulates is a refused notification-enable inside Android's Bluetooth service; killing and reopening the app does not clear it, which is why the message tells the rider to restart Bluetooth instead.

### 5. Start gating (no-trainer guard)

| Step | Action | Expected |
|------|--------|----------|
| 5.1 | After onboarding, with no trainer connected, tap any workout | Workout detail loads; bottom CTA reads **Pair a trainer to ride** with a small subtitle and a Bluetooth icon |
| 5.2 | Tap **Pair a trainer to ride** | Routes to the trainer pairing screen |
| 5.3 | Connect the mock trainer; back-out to the workout detail | CTA flips to **Start workout** automatically |

### 6. Active ride (mock trainer)

| Step | Action | Expected |
|------|--------|----------|
| 6.1 | Pair the mock trainer; pick **First Spin**; tap **Start workout** | Goes fullscreen landscape; countdown then segment 1 begins; target W displayed; FAB / pedaling toggle available |
| 6.2 | Toggle pedaling on (mock simulates ~target W ± jitter) | Power gauge updates; cadence shows; segment progress fills |
| 6.3 | Tap **+** / **−** bias buttons | Target % shifts; trainer target W updates |
| 6.4 | Tap **Skip Next** | Jumps to next segment; elapsed in segment resets |
| 6.5 | Background the app (home / lock screen) for ~30 s, return | Ride state continues uninterrupted; foreground-service notification was visible while backgrounded |
| 6.6 | Reach the end of all segments | Auto-navigates to the workout overview; new entry appears in History |

### 7. Workout overview & history persistence

| Step | Action | Expected |
|------|--------|----------|
| 7.1 | Complete a ride | Overview screen: averages (power / cadence / HR), distance / kJ, zone breakdown |
| 7.2 | Back out, open History | Newest session at top; date stamp + name + duration / avg W / kJ |
| 7.3 | Tap a session | Re-opens the same overview screen |

### 8. Progress

| Step | Action | Expected |
|------|--------|----------|
| 8.1 | After at least one ride | Weekly streak card and Rider Level card visible; XP value > 0 |
| 8.2 | Edit weight in Settings → re-open Progress | XP recalculates (W·min/kg formula uses current weight) |

### 9. Settings persistence

| Step | Action | Expected |
|------|--------|----------|
| 9.1 | Settings → change FTP to 250 W | Workouts on Rides tab recalculate target wattages with FTP=250 |
| 9.2 | Settings → change Weight | History totals + Progress XP recalculate |
| 9.3 | Force-stop the process; relaunch | All values persisted (DataStore-backed) |

### 10. Background / interruption

| Step | Action | Expected |
|------|--------|----------|
| 10.1 | Start a ride; force-stop the process via system settings | Process killed; foreground-service notification gone |
| 10.2 | Re-launch the app | Top of any tab shows an **Unfinished ride** banner with **Continue** / **Abandon** buttons |
| 10.3 | **Continue** | Lands back on the ride screen at the persisted segment / elapsed |
| 10.4 | **Abandon** | Banner disappears; no ride entry in History |

### 11. Permissions edge cases

| Step | Action | Expected |
|------|--------|----------|
| 11.1 | After fresh install, attempt to start a ride before any pairing (should require `BLUETOOTH_*`) | Permission prompt shown; granting starts the foreground service; denying still allows the ride to run while the app is foregrounded (no crash) |
| 11.2 | Revoke Notification permission and start a ride | Ride still runs; the workout chip just isn't visible in the system tray |

### 12. Side-by-side install

| Step | Action | Expected |
|------|--------|----------|
| 12.1 | Build + install both: `./gradlew.bat :mobile_clients:android:installDebug` and a release variant install | Two app icons on the launcher: **Indoor Bike** (orange) and **Indoor Bike Debug** (grayscale) |
| 12.2 | `adb shell pm list packages indoorbike` | Lists `app.indoorbike` and `app.indoorbike.debug` |
| 12.3 | Open both apps | Each has its own DataStore; settings + history are not shared |

---

## Shared-module unit tests

For pure logic (workout parsing, summary math, progression):

```powershell
.\gradlew.bat :shared:allTests
```

Running before any release is recommended.

---

## When the smoke test fails

Common causes:

- **Device not on PATH / no USB debugging** - `adb devices` should show your device.
- **Welcome banner missing** - onboarding flag was already set. Run `adb shell pm clear app.indoorbike.debug` (the script does this, but if the script was killed mid-run the state may be inconsistent).
- **Tap missed** - open the most recent screenshot and the matching logcat lines around that timestamp. The `Tap-Text` helper finds nodes by `contains(@text, ...)`; if a screen renames a button, the script needs an update.
- **Fatal exception** - `logcat.txt` will have the full stack at the line `FATAL EXCEPTION`.
