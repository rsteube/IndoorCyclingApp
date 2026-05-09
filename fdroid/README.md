# F-Droid submission

This directory holds the source of truth for Indoor Bike's F-Droid metadata.
The actual canonical copy lives in the F-Droid `fdroiddata` GitLab repo at
`metadata/app.indoorbike.yml` — this folder exists so the metadata is
versioned alongside the code, reviewed in the same PR cycle, and easy to
update when the app changes.

## Files — copy this whole tree into fdroiddata

- `metadata/app.indoorbike.yml` — the [Build Metadata Reference][meta-ref]
  YAML. Categories, anti-features, build entries, source URL, etc.
- `metadata/app.indoorbike/en-US/short_description.txt` — the Summary, in
  per-locale form. F-Droid's `make-summary-translatable.py` tooling
  auto-migrates `Summary:` from the YAML into this layout and fails the
  MR pipeline if the move produces uncommitted changes — we pre-do the
  migration so the YAML never carries `Summary:` or `Description:`.
- `metadata/app.indoorbike/en-US/full_description.txt` — the Description,
  same rationale.

The directory tree mirrors fdroiddata's expected layout exactly. To
submit / update, copy `metadata/app.indoorbike.yml` and `metadata/app.indoorbike/`
verbatim into your fdroiddata fork.

The user-facing store listing (title, short / long description,
screenshots) is **not** here — it lives at the standard Fastlane path
inside the Android module:

```
mobile_clients/android/fastlane/metadata/android/en-US/
├── title.txt
├── short_description.txt
├── full_description.txt
└── images/
    └── phoneScreenshots/
        ├── 1_rides.png
        ├── 2_ride-hud.png
        └── 3_ride-overview.png
```

F-Droid auto-discovers Fastlane structure during the build — nothing else
to wire up. Refresh the screenshots from `docs/screenshots/` whenever the
UI changes substantially.

## Submitting (or updating) the F-Droid entry

### Before opening the MR

1. **Tag the release** in this repo:
   ```
   git tag v1.0
   git push origin v1.0
   ```
2. **Get the full commit SHA** of the tagged commit and paste it into the
   `commit:` field of `metadata/app.indoorbike.yml`:
   ```
   git rev-parse v1.0
   ```
   F-Droid maintainers reject branch names ("main") or tag names ("v1.0")
   here — the recipe must pin a specific 40-char SHA on the public Codeberg
   remote.
3. **Refresh `CurrentVersion` / `CurrentVersionCode`** in the YAML so they
   match `versionName` / `versionCode` in
   `mobile_clients/android/build.gradle.kts`.
4. **Verify line endings are LF.** Windows-style CRLF in the YAML or the
   Fastlane `*.txt` files breaks F-Droid's parsers — they look fine but
   contain invisible characters that the build server chokes on.
   ```
   git config --get core.autocrlf  # should NOT be 'true' for this repo
   ```

### Opening the MR against fdroiddata

5. **Fork** [https://gitlab.com/fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata).
6. **Create a branch** in your fork (e.g. `add-app-indoorbike`). **Never
   open the MR from your `master` branch** — F-Droid maintainers need
   fast-forward merges and may need to rebase your branch themselves; a
   protected `master` blocks them.
7. **Copy** `metadata/app.indoorbike.yml` from this repo into your fork at
   `metadata/app.indoorbike.yml`.
8. **Open the MR** against `fdroiddata`'s `master`. If there's an existing
   "Request For Packaging" issue, link it in the MR description.
9. **Don't rebase your branch** after opening the MR unless there's an
   actual merge conflict — maintainers prefer to rebase themselves.

### Local verification (optional but encouraged)

Before pushing, run F-Droid's reproducible-build environment locally if
you have it set up:

```
fdroid build app.indoorbike
```

This catches build-recipe issues without consuming F-Droid's CI minutes
on a round-trip.

### Pipeline weirdness to expect

- **The "weblate" job often fails** with internal merge conflicts on
  community-translation files (e.g. `Auto-merging config/ru/categories.yml`).
  This is unrelated to your app. If the job that actually builds
  `app.indoorbike` passed, leave a comment noting the weblate failure
  isn't yours and proceed.
- **GitLab may ask for a credit card or phone number** to verify your
  account before running CI on your fork. **Do not provide it.** Comment
  on the MR and the F-Droid maintainers will trigger the pipeline on
  F-Droid's internal runners instead.

### Subsequent updates

Just bump:
- `versionName` / `versionCode` / `commit:` in `metadata/app.indoorbike.yml`
- `CurrentVersion` / `CurrentVersionCode` to match
- Screenshots / Fastlane text if anything visible changed

…then repeat steps 5–9. The branch can be `update-1.1` etc.

## Reproducible builds

F-Droid pushes hard for reproducible builds — bit-for-bit verification
that their build of your APK matches yours. If a maintainer asks you to
publish your signed release APK as a tag asset, do this:

1. Build the release APK locally:
   ```
   .\gradlew.bat :mobile_clients:android:assembleRelease
   ```
   This uses the keystore at `keystore.properties` — the same key you'd
   use for any direct distribution.
2. Upload the APK as an asset on the matching Codeberg release tag.
3. Comment on the F-Droid MR pointing at the asset URL.

If the F-Droid build matches your APK byte-for-byte, F-Droid's tooling
will lift your signature into their distributed copy. Riders can then
switch between your direct-download APK and the F-Droid one without
losing local data — same package signature, no uninstall needed.

## What's already declared in the YAML

- **License:** `GPL-3.0-only` (matches `LICENSE`).
- **Categories:** `Sports & Health`.
- **Anti-features:**
  - `NonFreeNet` — for the optional Intervals.icu integration. Disabled by
    default; the app makes zero outbound calls until the rider enters
    credentials. Description in the YAML explains this in the language
    F-Droid app stores will surface to users.

## Reference

- [F-Droid Build Metadata Reference][meta-ref]
- [F-Droid Inclusion Policy][inclusion]
- [fdroiddata repo on GitLab](https://gitlab.com/fdroid/fdroiddata)

[meta-ref]: https://f-droid.org/docs/Build_Metadata_Reference/
[inclusion]: https://f-droid.org/docs/Inclusion_Policy/
