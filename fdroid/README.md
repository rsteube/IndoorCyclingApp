# F-Droid submission

This directory holds the source of truth for Indoor Bike's F-Droid metadata.
The actual canonical copy lives in the F-Droid `fdroiddata` GitLab repo at
`metadata/app.indoorbike.yml` — this folder exists so the metadata is
versioned alongside the code, reviewed in the same PR cycle, and easy to
update when the app changes.

## Files — copy this whole tree into fdroiddata

```
fdroid/
├── .gitattributes                                 ← pins LF endings (see below)
├── README.md                                      ← this file
└── metadata/
    ├── app.indoorbike.yml                         ← the recipe
    └── app.indoorbike/
        └── en-US/
            ├── summary.txt                        ← the Summary, per-locale
            └── description.txt                    ← the Description, per-locale
```

- `metadata/app.indoorbike.yml` — the [Build Metadata Reference][meta-ref]
  YAML. Categories, anti-features, build entries, source URL, etc. **Format
  is canonical**: AntiFeatures alphabetically-sorted to the top, no comments,
  any rich-text descriptions stored as folded `"..."` scalars (single
  paragraph). `fdroid rewritemeta` enforces this in CI; if you add comments
  or change the field order, the rewritemeta lint pipeline fails.
- `metadata/app.indoorbike/en-US/summary.txt` — the Summary in per-locale
  form. **Filename matters**: F-Droid's `check-localized-metadata.py`
  rejects the Fastlane name `short_description.txt`. F-Droid's tooling
  also auto-moves any `Summary:` left in the YAML into this file and
  fails on `git diff --exit-code`, so we keep the YAML clean of it.
- `metadata/app.indoorbike/en-US/description.txt` — the Description, same
  filename rationale (`full_description.txt` is rejected).

To submit / update, copy `metadata/app.indoorbike.yml` and the
`metadata/app.indoorbike/` directory verbatim into your fdroiddata fork.

### LF line endings

`fdroid/.gitattributes` pins LF for everything in this directory. Without
it, Windows clones with `core.autocrlf=true` end up with CRLF in the
working tree, which leaks into the fdroiddata fork when copying and
breaks `fdroid rewritemeta` (the diff shows `^M` markers everywhere).

If you ever see CRLF complaints from the F-Droid pipeline, sanity-check
with:
```
file fdroid/metadata/app.indoorbike.yml
# should say "ASCII text" (or "Unicode text, UTF-8 text") — never "with CRLF line terminators"
```

### What NOT to add to fdroiddata's `metadata/app.indoorbike/` directory

- `.gitkeep` files — `fdroid lint` flags them as "Unused file at …" and
  fails the lint job. The directory needs at least one real file (e.g.
  `en-US/summary.txt`) to exist; with that present, drop the `.gitkeep`
  if you accidentally create one.
- Comments or extra fields in the YAML that `fdroid rewritemeta` would
  rewrite away.

### Two parallel metadata trees

The same human-facing copy lives in **two** locations on purpose:

- **fdroiddata convention** — `metadata/app.indoorbike/en-US/{summary,description}.txt`
  in the fdroiddata fork. Used by F-Droid's lint + listing pipeline. Mirrored
  here under `fdroid/metadata/`.
- **Fastlane convention** — `mobile_clients/android/fastlane/metadata/android/en-US/{title,short_description,full_description}.txt`
  inside this repo. F-Droid's buildserver auto-discovers the Fastlane tree
  during the build and uses the screenshots / title from there. Renames
  differ from fdroiddata's:

```
mobile_clients/android/fastlane/metadata/android/en-US/
├── title.txt
├── short_description.txt        ← becomes summary.txt in fdroiddata
├── full_description.txt         ← becomes description.txt in fdroiddata
└── images/
    └── phoneScreenshots/
        ├── 1_rides.png
        ├── 2_ride-hud.png
        └── 3_ride-overview.png
```

When the description copy changes, **update both trees** to keep them
aligned (text content is the same; only the filename differs).

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
