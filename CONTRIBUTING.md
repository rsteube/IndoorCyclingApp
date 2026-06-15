# Contributing

Thanks for your interest. This is a spare-time project, so issues and small PRs may not get an immediate response - but they are very welcome.

## Project layout

- `shared/` - Kotlin Multiplatform module with all core logic: workout parsing, BLE managers (trainer / HRM / controller), session timing, persistence schema, FIT writer, progression math. Pure Kotlin, unit-tested.
- `mobile_clients/android/` - Android client. Jetpack Compose UI, Material 3, the in-house Cadence design system. Composables read singletons through `LocalAppContainer` (see [`IndoorBikeApp.kt`](mobile_clients/android/src/main/kotlin/app/indoorbike/IndoorBikeApp.kt)).
- `cadence-design-system/` - design tokens and component documentation.
- `website/` - static marketing site for indoorbike.app and the in-browser workout editor.
- `docs/ARCHITECTURE.md` - architecture overview, kept in step with the code.
- `scripts/smoke_test.ps1` - end-to-end smoke test (Windows / PowerShell).

## Building and running on Android

The repo only ships `gradlew.bat`, so the canonical build host is Windows / PowerShell. macOS / Linux work too - substitute `./gradlew` for `.\gradlew.bat`.

Build + install the **debug** variant:

```powershell
.\gradlew.bat :mobile_clients:android:installDebug
```

Launch (note the `.debug` application id; the activity name stays `MainActivity`):

```powershell
adb -s <device-serial> shell am start -n app.indoorbike.debug/app.indoorbike.MainActivity
```

For the **release** variant, use `am start -n app.indoorbike/app.indoorbike.MainActivity`. Run `adb devices` first if you don't know the serial.

The `:shared` module's compile task is `:shared:compileDebugKotlinAndroid` - the unqualified `compileKotlinAndroid` is ambiguous and fails.

### Build variants

- **Debug** - `applicationId = app.indoorbike.debug`, `versionName = 1.0-debug`, label "Indoor Bike Debug", grayscale launcher icon (resource overlay in `src/debug/res/`).
- **Release** - `applicationId = app.indoorbike`, `versionName = 1.0`, label "Indoor Bike", brand-orange icon.

Both can sit side-by-side on the same device. They have separate DataStore + SQLDelight databases, so a `pm clear app.indoorbike.debug` gives you a true first-run on the next launch.

## Testing

- **Smoke test** - `.\scripts\smoke_test.ps1` (build + install + onboarding + mock-trainer ride + logcat scan).
- **Shared-module unit tests** - `.\gradlew.bat :shared:allTests`.
- **Manual checklist** - see [TESTING.md](TESTING.md) for everything the smoke test cannot assert (real BLE, label correctness, layout).

## Versioning

Bump `versionCode` and `versionName` in [mobile_clients/android/build.gradle.kts](mobile_clients/android/build.gradle.kts). The debug build inherits the `-debug` suffix on its version name automatically.

## Architecture changes

If you change something architectural - a new module, a different threading model, a different BLE library, a change to the on-disk storage layout - please update [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) in the same PR. The doc is the source of truth for "how does this hang together"; keeping it current means the next reader (or future you) doesn't have to re-derive it from grep.

## F-Droid posture

The app must remain free of proprietary blobs, Google Play Services, analytics, and crash reporters. New dependencies are only worth adding if equivalents don't already exist in the codebase or in F-Droid-clean libraries. If you're unsure whether a candidate library qualifies, raise an issue first.

## Code style

- Kotlin: follow the official style. The project doesn't run ktlint / detekt yet - keep formatting consistent with what you see.
- Compose: prefer state hoisting, single source of truth, and the existing Cadence design tokens (`ui/theme/`).
- KMM: pure logic in `shared/`, Android-specific bindings in `mobile_clients/android/`.

## Documentation & writing style

- **No em-dashes.** Don't use the em-dash character (U+2014, the long dash) anywhere in docs, the website, code comments, or UI copy. Use a spaced hyphen (` - `), a colon, a comma, or parentheses instead - whichever reads best for the sentence. This applies to anything Markdown, HTML, JS, CSS, JSON, and Kotlin strings.

## Submitting changes

1. Fork the repo on Codeberg, branch off `main`.
2. Keep PRs focused - one topic per PR.
3. Run `:shared:allTests` and the smoke test before opening a PR.
4. In the PR description, mention what you tested manually (which trainer hardware, which Android version, etc).

For sensitive issues (security, privacy), please email `juhani.lehtimaki@iki.fi` instead of opening a public issue.
