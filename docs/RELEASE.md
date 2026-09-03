# Releasing a new app version

This is the exact, repeatable process for cutting an **Indoor Bike** Android
release. It is written for an agent (or a human) so the flow does not need to be
re-investigated each time. Distribution is via **CodeFloe releases + ObtainX**
(no Google Play, no F-Droid): the APK attached to a release is the only
copy, and it is signed with the project's own key.

**CodeFloe is the only home for the code.** Codeberg is kept alive purely as a
release channel for riders who have not repointed their ObtainX source yet, and
nothing else: its `main` is frozen, its README says so, and it receives no
further development. Do not push branches to it, do not merge anything from it.
It gets the release tag and the APK, which is all a release asset needs to hang
on, and nothing more. The local `codeberg` remote is configured to push tags
only, so a bare `git push codeberg` cannot move a branch by accident.

> Project convention: **no em-dash characters** anywhere (commit messages,
> changelog, notes). Use a spaced hyphen, colon, or comma instead.

## Prerequisites (verify before starting)

- `keystore.properties` exists at repo root. Without it, release builds silently
  fall back to the **debug** key and the APK is not a valid update (ObtainX
  will reject it as a different signer). Always verify the signer after building
  (step 4).
- `CODEFLOE_TOKEN` is set in the environment (scope `write:repository`), or pass
  `-Token` to the publish script. `CODEBERG_TOKEN` too, for the mirror publish.
  Never print or commit either token.
- Working tree is clean and all the changes you intend to ship are committed to
  `main`. `git status` snapshots in the harness prompt can be stale, so confirm
  with a fresh `git status --short`.
- Remote `origin` is the CodeFloe repo over SSH. `codeberg` exists only to
  receive release tags and assets.

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
# Codeberg gets the tag only. Pushing the tag carries the objects it points at,
# which is enough for a release to attach to; its `main` stays frozen.
git push codeberg vX.Y
```

### 8. Publish the release (this is what ObtainX tracks)

A pushed tag alone is not enough. The release object + attached APK asset are
what ObtainX polls. Publish to CodeFloe, then to Codeberg for the riders who
have not moved yet.

```powershell
.\scripts\publish_release.ps1 -Tag vX.Y
.\scripts\publish_release.ps1 -Tag vX.Y -Forge codeberg
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
2. Write `scripts/release-notes-X.Y.md` + prepend `CHANGELOG.md`.
3. `assembleRelease`.
4. Verify signer is `CN=Juhani Lehtimaki`.
5. Copy build output into `mobile_clients/android/release/` as `indoorbike-X.Y.apk`.
6. Commit `Release X.Y: ...`, annotated tag `vX.Y`.
7. `git push origin main` and `git push origin vX.Y`, then `git push codeberg vX.Y`
   (tag only).
8. `publish_release.ps1 -Tag vX.Y`, then again with `-Forge codeberg`.
9. Confirm the release page and attached asset.
