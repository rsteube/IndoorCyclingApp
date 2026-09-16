# Releasing a new app version

This is the exact, repeatable process for cutting an **Indoor Bike** Android
release. It is written for an agent (or a human) so the flow does not need to be
re-investigated each time.

There are two distribution channels, cut from the same tag and signed with the
same key:

- **CodeFloe releases + ObtainX** (steps 1 to 9 below). An APK attached to a
  release object, which is the thing ObtainX polls. This is the primary
  channel and the only one that works without a Google account.
- **Google Play** (see [Publishing to Google Play](#publishing-to-google-play)).
  An App Bundle, uploaded by fastlane. Optional per release: skipping it leaves
  Play riders on the previous version and breaks nothing.

F-Droid is not a channel and never became one.

**CodeFloe is the only home for the code and the only place it is released.**
Codeberg was a release mirror through 2.0.1 and is retired: no remote, no tag,
no release. Riders still pointed at the Codeberg URL stop receiving updates
there, and the last thing they can see is 2.0.1.

> Project convention: **no em-dash characters** anywhere (commit messages,
> changelog, notes). Use a spaced hyphen, colon, or comma instead.

## Prerequisites (verify before starting)

- `keystore.properties` exists at repo root. Without it, release builds silently
  fall back to the **debug** key and the APK is not a valid update (ObtainX
  will reject it as a different signer). Always verify the signer after building
  (step 4).
- `forge.properties` exists at the repo root, gitignored beside
  `keystore.properties`, holding a token with the **`write:repository`** scope:

  ```properties
  codefloeToken=<token>
  ```

  Put it in the file rather than in an environment variable. A variable set
  with `setx` or the System Properties dialog does not reach a process that is
  already running, so any shell, editor or agent started before the change goes
  on reading the old value - which looks exactly like a bad token from the
  outside, and cost this project two releases. The file has no such problem,
  which is why the signing key has never had one.

  The publish script checks `-Token`, then `forge.properties`, then
  `CODEFLOE_TOKEN` in the User scope, then the process environment, and it
  confirms write access before it builds anything, naming the source it used.
  Never print or commit the token.
- `intervals.properties` exists at the repo root, gitignored beside the other
  two, holding the Intervals.icu OAuth client registered for Indoor Bike:

  ```properties
  intervalsClientId=<client id>
  intervalsClientSecret=<client secret>
  ```

  Without it the build still succeeds, but riders cannot connect Intervals.icu
  and are asked for their personal API key instead, which Intervals.icu asks
  multi-user apps not to do. The build says so: a release whose output contains
  `intervals.properties not found` must not be published. Both values come from
  `https://intervals.icu/settings/apps`. Never print or commit the secret.
- Working tree is clean and all the changes you intend to ship are committed to
  `main`. `git status` snapshots in the harness prompt can be stale, so confirm
  with a fresh `git status --short`.
- Remote `origin` is the CodeFloe repo over SSH, and is the only remote.

## Decide the version

- `versionName` is the human string (e.g. `1.4`); `versionCode` is a strictly
  increasing integer (currently the two are one apart: name `1.4` = code `7`).
  **Always bump `versionCode` by exactly 1**, or ObtainX/Android will not treat
  the build as an update.
- Version bump size: patch (`1.3.1`) for fixes/polish, minor (`1.4`) for
  user-facing feature or substantial UI work. When unsure, ask.

## Steps

Replace `X.Y` with the new version and `N` with the new versionCode throughout.

### 1. Bump the version

Edit [mobile_clients/android/build.gradle.kts](../mobile_clients/android/build.gradle.kts),
`defaultConfig`:

```kotlin
versionCode = N
versionName = "X.Y"
```

### 2. Write the release notes and changelog

- Create `scripts/release-notes-X.Y.md`. This is the **release body**
  (Markdown). Follow the style of the previous `scripts/release-notes-*.md`:
  a one-line intro, then `Added` / `Changed` / `Fixed` sections with
  bold lead-ins, an `Install` block pointing at ObtainX, and a final
  `Signed with the project's release key; versionCode N.` line.
- Prepend a matching section to [CHANGELOG.md](../CHANGELOG.md) using the
  Keep-a-Changelog format already in the file:
  `## [X.Y] (N) - YYYY-MM-DD` with `### Added/Changed/Fixed/Notes`.
- To gather material, diff against the previous release commit:
  `git log <prev-release-commit>..HEAD --stat` and read the actual diffs. Release
  commits are titled `Release <ver>: ...`.

### 3. Build the signed release APK

```bash
./gradlew.bat :mobile_clients:android:assembleRelease
```

Output lands in `mobile_clients/android/build/outputs/apk/release/`
(`android-release.apk`, `output-metadata.json`, `baselineProfiles/`).
Confirm `output-metadata.json` shows the new `versionCode`/`versionName`.

### 4. Verify the signer (do not skip)

```bash
"$LOCALAPPDATA/Android/Sdk/build-tools"/*/apksigner.bat verify --print-certs \
  mobile_clients/android/build/outputs/apk/release/android-release.apk
```

The certificate DN must be `CN=Juhani Lehtimaki` (SHA-256
`ca92afa896d1a60466e79550bc59c90c1d366ccde5e307a611583a4085097ce9`). If it says
`CN=Android Debug`, the release key was not loaded: fix `keystore.properties`
and rebuild. Publishing a debug-signed APK breaks updates for every installed
user.

### 5. Stage the archived APK (git-tracked)

`mobile_clients/android/release/` is a **committed** copy of the shipped APK,
renamed to `indoorbike-X.Y.apk`, plus its metadata and baseline profiles. This
is the file the publish script uploads. Replace the previous version's copy:

```bash
SRC=mobile_clients/android/build/outputs/apk/release
DST=mobile_clients/android/release
git rm --cached $DST/indoorbike-<prev>.apk; rm -f $DST/indoorbike-<prev>.apk
cp $SRC/android-release.apk $DST/indoorbike-X.Y.apk
cp $SRC/output-metadata.json $DST/output-metadata.json
rm -rf $DST/baselineProfiles && cp -r $SRC/baselineProfiles $DST/baselineProfiles
```

### 6. Commit and tag

```bash
git add -A
git commit -F <message-file>      # subject: "Release X.Y: <summary>"
git tag -a vX.Y -m "Indoor Bike X.Y"
```

Notes:
- Use `git commit -F <file>` (or a real Bash heredoc) for multi-line messages.
  Do **not** use PowerShell here-string syntax (`@'...'@`) inside the Bash tool:
  it leaves a literal `@` as the commit subject.
- Tags are **annotated**. `git rev-parse vX.Y` returns the tag object SHA, not
  the commit. To compare against HEAD use `git rev-parse vX.Y^{commit}`.
- End commit messages with the required `Co-Authored-By:` trailer.

### 7. Push

```bash
git push origin main
git push origin vX.Y
```

### 8. Publish the release (this is what ObtainX tracks)

A pushed tag alone is not enough. The release object + attached APK asset are
what ObtainX polls.

```powershell
.\scripts\publish_release.ps1 -Tag vX.Y
```

The script (see [scripts/publish_release.ps1](../scripts/publish_release.ps1))
creates the release for the already-pushed tag and uploads the APK as an asset
named `indoorbike-X.Y.apk`. `-Apk` and `-NotesFile` default to the conventional
paths for the tag, so the usual case needs nothing but `-Tag`. It prints the
release URL and the asset download URL on success, and reads the token from
`-Token` or the forge's own environment variable.

It is written for **Windows PowerShell 5.1** and already works around two traps:
it writes the JSON payload as UTF-8 **without a BOM** (Forgejo rejects a BOM),
and reads the notes file as a plain .NET string (so `ConvertTo-Json` does not
wrap it in an object). Run it with the PowerShell tool, not Bash.

### 9. Verify

- Open the printed release URL and confirm the notes render and the
  `indoorbike-X.Y.apk` asset is attached.
- Optionally install the APK on a device (or run
  [scripts/smoke_test.ps1](../scripts/smoke_test.ps1) for the debug variant) to
  confirm it launches.

## Quick checklist

1. Bump `versionCode`/`versionName` in `build.gradle.kts`.
2. Write `scripts/release-notes-X.Y.md` + prepend `CHANGELOG.md` + add
   `fastlane/metadata/android/en-US/changelogs/N.txt` (500 chars, for Play).
3. `assembleRelease`.
4. Verify signer is `CN=Juhani Lehtimaki`.
5. Copy build output into `mobile_clients/android/release/` as `indoorbike-X.Y.apk`.
6. Commit `Release X.Y: ...`, annotated tag `vX.Y`.
7. `git push origin main` and `git push origin vX.Y`.
8. `publish_release.ps1 -Tag vX.Y`.
9. Confirm the release page and attached asset.
10. Optional, for Play: `cd mobile_clients/android; bundle exec fastlane internal`,
    then `bundle exec fastlane promote from:internal to:production rollout:0.1`.

## Publishing to Google Play

Play is the second channel. It takes an **App Bundle**, not the APK the
CodeFloe release carries, and it is uploaded by [fastlane
supply](https://docs.fastlane.tools/actions/supply/) from
[mobile_clients/android/fastlane/](../mobile_clients/android/fastlane/).

Everything below runs from `mobile_clients/android`, not the repo root.

### One-time setup

**1. Ruby and the gems.** fastlane is a Ruby tool. Ruby 3.3 with the MSYS2
devkit is installed (`winget install RubyInstallerTeam.RubyWithDevKit.3.3`);
if a shell cannot see it, its `PATH` predates the install and
`C:\Ruby33-x64\bin` has to go in front. Then:

```powershell
cd mobile_clients\android
bundle install
```

fastlane warns on every run that the locale is not UTF-8. On Windows that
warning is normal, but the listing text is not pure ASCII, so set the locale
for the session before any lane that uploads metadata:

```powershell
$env:LANG = "en_US.UTF-8"; $env:LC_ALL = "en_US.UTF-8"
```

**2. The signing key decision, which is effectively permanent.** Play App
Signing holds the key that signs what users actually install. You get one
choice, at the first upload:

- **Upload the existing `CN=Juhani Lehtimaki` key.** Play-installed and
  ObtainX-installed copies then share a signing identity, and a rider can
  move between the two channels in place.
- **Let Google generate a key.** The two channels are then signed by
  different certificates, and a rider who has the ObtainX build cannot
  install or update to the Play build without uninstalling first, which
  destroys their rides, library and settings.

Take the first. It is the whole reason both channels can coexist. The key
that Play accepts on the **first** upload becomes the permanent upload key,
so a debug-signed bundle uploaded by accident is not a mistake you can
quietly undo: the `build_bundle` lane refuses to continue if it sees
`CN=Android Debug`.

**3. The service account.** In the Play Console, invite a service account
under Users and permissions (or create one in Google Cloud, then grant it
access), give it release permissions on the app, and save its JSON key as
`play-service-account.json` **at the repo root**, beside
`keystore.properties` and `forge.properties`. All three are gitignored. See
[fastlane/Appfile](../mobile_clients/android/fastlane/Appfile).

**4. The Console-only declarations.** Nothing in this repo can fill these in
and the first release is blocked until they are done: content rating
questionnaire, Data Safety form, target audience, ads declaration, app access
instructions, and the privacy policy URL
(`https://indoorbike.app/privacy.html`). Two permission declarations will be
asked for specifically:

- `ACCESS_FINE_LOCATION`, which the app needs only because `minSdk` is 26 and
  pre-Android-12 devices cannot BLE-scan without it. `BLUETOOTH_SCAN` already
  carries `neverForLocation`.
- The `connectedDevice` foreground service type used by `RideSessionService`
  to hold a ride open while the screen is off.

### Store listing

The listing lives in
[fastlane/metadata/android/en-US/](../mobile_clients/android/fastlane/metadata/android/en-US/),
in the layout `supply` reads directly:

| File | Play limit |
|---|---|
| `title.txt` | 30 characters |
| `short_description.txt` | 80 characters |
| `full_description.txt` | 4000 characters |
| `changelogs/<versionCode>.txt` | 500 characters, one file per versionCode |
| `images/icon.png` | 512x512, 32-bit PNG |
| `images/featureGraphic.png` | 1024x500, 24-bit PNG, no alpha |
| `images/phoneScreenshots/` | 2 to 8, 16:9 or 9:16, 320px to 3840px per side, no alpha |
| `images/sevenInchScreenshots/` | same, 7-inch tablet slot |
| `images/tenInchScreenshots/` | same, but each side 1080px to 7680px |

The changelog is **not** the same text as `scripts/release-notes-X.Y.md`. Play
caps it at 500 characters and it is named by versionCode, not versionName, so
step 2 of a release gains a third artefact: `changelogs/N.txt`, a condensed
version of the release notes with the ObtainX install instructions dropped.

The icon and feature graphic are generated, not hand-drawn. Regenerate them
after any change to the logo in `slate-design-system/logo/`:

```powershell
.\scripts\render_store_graphics.ps1
```

It renders [scripts/store-graphics/](../scripts/store-graphics/) with headless
Chrome or Edge and writes straight into the metadata tree.

### Screenshots

Play requires 16:9 or 9:16, which no modern phone produces: the Fairphone 4
this project develops against is 2.167:1 and would be rejected outright. So
the store screenshots come from emulators built to exact geometry, never from
a handset. Three AVDs, created once:

| AVD | Resolution | Density | Fills |
|---|---|---|---|
| `ib_phone` | 1080x1920 | 420 | phone (sw411dp) |
| `ib_tab7` | 1080x1920 | 288 | 7-inch (sw600dp) |
| `ib_tab10` | 1440x2560 | 288 | 10-inch (sw800dp, both sides over 1080px) |

Create them with `avdmanager create avd -n <name> -k
"system-images;android-35;default;x86_64" -d pixel_5`, then set `hw.lcd.width`,
`hw.lcd.height` and `hw.lcd.density` in `~/.android/avd/<name>.avd/config.ini`
and point `skin.path` at `_no_skin` so the geometry actually takes.

Boot one, then:

```powershell
.\scripts\capture_store_screenshots.ps1 -Serial emulator-5554 -Kind phone
```

`-Kind` is `phone`, `sevenInch` or `tenInch` and selects the output directory.

**The capture build is a patched build, and the patch must never be
committed.** An emulator has no Bluetooth, so the only way to drive a ride is
the debug mock trainer, and the debug build says so on screen: a "Mock" pill
in the ride controls and "Mock Trainer" as the device name. No rider ever sees
either. Before capturing, patch them out locally:

- `TrainerManager.kt`: name the mock `"Wahoo KICKR CORE"`, and set
  `isMockPedaling = true` so the mock produces power on connect. Without the
  second change the ride never leaves "Standby - pedal to start", the HUD
  reads 0 W, and Skip and End never appear because the ride is not active.
- `DeviceStatus.kt`: drop the mock branches from the title and status line.
- `RideScreen.kt`: remove the `HudPill(text = "Mock", ...)`.

Revert with `git checkout --` on those files the moment the captures are done.

The script handles the rest: it seeds ten weeks of history from
[seed-history.sql](../scripts/store-screenshots/seed-history.sql) so
progression, badges and streaks show real numbers, grants every runtime
permission up front (the foreground service's notification dialog otherwise
lands on top of the ride HUD), pre-confirms Android's "Viewing full screen"
overlay, and re-encodes each capture as 24-bit RGB.

One label differs by form factor and the script tries both: the phone's ride
controls share a row with the back button and abbreviate the end control to
"End", while a tablet spells out "End & save".

### Per release

After the CodeFloe steps above are done and the tag is pushed:

```powershell
cd mobile_clients\android
bundle exec fastlane internal
```

That builds `:mobile_clients:android:bundleRelease`, verifies the signer, and
uploads to the internal testing track along with the listing and the changelog
for that versionCode. Install it from the internal track on a real device
before going further.

Then promote it:

```powershell
bundle exec fastlane promote from:internal to:production rollout:0.1
```

`rollout:0.1` is a staged 10% rollout, which the Console can widen to 1.0
once nothing has caught fire.

### The lanes

| Lane | What it does |
|---|---|
| `build_bundle` | `bundleRelease` plus a signer check that fails on the debug key |
| `listing` | Listing, graphics and changelogs only, no binary |
| `draft` | First upload for an app Play has never published, as a draft release |
| `internal` | Build and upload to internal testing |
| `validate` | Full dry run against Play, commits nothing |
| `promote` | Move an existing build between tracks, `from:` `to:` `rollout:` |
| `bootstrap` | Pull the live listing down from Play, overwriting the local copy |

Use `validate` first whenever something about the upload is uncertain. It
runs every check Play would run and then throws the edit away.

### Things that bite

- **A versionCode is consumed forever.** Play will not accept the same
  `versionCode` twice, even for a deleted draft. The existing rule of bumping
  by exactly 1 per release already satisfies this, but a botched upload burns
  a number.
- **`dependenciesInfo` is asymmetric on purpose.** The AAB carries the
  dependency metadata block so the Console can flag vulnerable libraries; the
  APK does not, because on the CodeFloe side it is an unrecognised extra
  signing block that scanners have rejected. See
  [build.gradle.kts](../mobile_clients/android/build.gradle.kts).
- **The "no debug symbols" warning on an App Bundle cannot be fixed here, so
  do not spend an afternoon on it.** Play notices the bundle carries native
  code and asks for a symbol file. The app has no native code of its own: the
  twelve `.so` files come from AndroidX, being graphics-path via Compose, the
  DataStore shared counter, and CameraX's image processing. Setting
  `ndk.debugSymbolLevel` on the release build type looks like the fix and does
  nothing, because AGP extracts symbols from unstripped libraries and those
  arrive from their AARs already stripped. A rebuild with it set produces no
  `BUNDLE-METADATA/.../nativesymbols` entry at all. The warning is advisory,
  it does not block review, and the crashes it concerns would be inside
  Google's own libraries.
- **Play is optional per release.** Nothing in the CodeFloe flow depends on
  it. If a release skips Play, riders there stay on the previous version and
  the next upload catches them up.
