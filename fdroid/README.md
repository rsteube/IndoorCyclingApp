# F-Droid submission

This directory holds the source of truth for Indoor Bike's F-Droid metadata.
The actual canonical copy lives in the F-Droid `fdroiddata` repository at
`metadata/app.indoorbike.yml` — this folder exists so the metadata is
versioned alongside the code, reviewed in the same PR cycle, and easy to
update when the app changes.

## Files

- `metadata/app.indoorbike.yml` — the [Build Metadata Reference][meta-ref]
  YAML for `app.indoorbike`. Categories, anti-features, build entries,
  source URL, etc.

## Submitting (or updating) the F-Droid entry

1. **Tag the release** in this repo (e.g. `git tag v1.0 && git push origin v1.0`).
   Update the `commit:` field in `metadata/app.indoorbike.yml` to point at
   the tag.

2. **Fork** [https://gitlab.com/fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata).

3. **Copy** `metadata/app.indoorbike.yml` from this repo into your fork at
   `metadata/app.indoorbike.yml`.

4. **Test the build locally** if you can — F-Droid's `fdroidserver` tooling
   (`fdroid build app.indoorbike`) will reproduce the build environment in
   a VM. The [Build Metadata Reference][meta-ref] and the
   [Inclusion Policy][inclusion] document the requirements.

5. **Open a merge request** against `fdroiddata` referencing the
   release tag. F-Droid maintainers will run lint, build the APK in their
   reproducible-build environment, sign it with the F-Droid key, and add
   the build to the repo.

6. For subsequent updates, just bump `versionCode` / `versionName` /
   `commit:` / `CurrentVersion` / `CurrentVersionCode` in this repo's copy
   and repeat steps 2–5.

## What's already declared

- **License:** `GPL-3.0-only` (matches `LICENSE`).
- **Categories:** `Sports & Health`.
- **Anti-features:**
  - `NonFreeNet` — for the optional Intervals.icu integration. Disabled by
    default; the app makes zero outbound calls until the rider enters
    credentials. Description in the YAML explains this in the language
    F-Droid app stores will surface to users.

## Things to verify before each submission

- `versionCode` and `versionName` in `mobile_clients/android/build.gradle.kts`
  match the values in this metadata.
- The release commit/tag exists and is reachable from the public Codeberg
  remote.
- `NOTICE.md` is up to date with any new dependencies added since the last
  release.
- Privacy policy at `website/privacy.html` reflects current data flows
  (re-read it any time `UserSettings` or any persistence layer changes).

[meta-ref]: https://f-droid.org/docs/Build_Metadata_Reference/
[inclusion]: https://f-droid.org/docs/Inclusion_Policy/
