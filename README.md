# Indoor Bike

A local-first, open-source indoor cycling app for Android. Connects to your smart trainer over standard BLE FTMS, runs structured power-zone workouts in ERG mode, and saves rides on your phone as files. No accounts, no subscriptions, no cloud required.

The companion website lives at [indoorbike.app](https://indoorbike.app), with an in-browser workout editor.

![Indoor Bike — workout list](docs/screenshots/rides.png)

## Features

- **BLE smart-trainer control** — Wahoo Kickr Core is the first-class target; any FTMS-compatible trainer should work. Optionally pair a Bluetooth heart-rate monitor and a Zwift Click v1 controller.
- **ERG mode** — the app drives the trainer to each interval's target wattage automatically. You hold cadence, it holds watts.
- **Plain-text JSON workouts** — readable, hand-editable, and shareable. Use the bundled library, drop in your own, fetch a community pack from a URL or QR code, or build them visually in the [browser editor](https://indoorbike.app/editor/).
- **Local progression** — streaks, rider levels, badges, week-grouped history, and time-in-zone — all computed on-device from saved rides.
- **FIT export and optional Intervals.icu sync** — strict opt-in. Manual upload from the post-ride screen or auto-upload after a ride; nothing leaves the device unless you configure credentials.
- **F-Droid posture** — no Google Play Services, no analytics, no crash reporters, no proprietary blobs. Auth0 / CleverCloud sync are entirely optional and lazily loaded.
- **Side-by-side debug + release installs** — different application IDs and a grayscale launcher icon for debug, so you can ride against production while developing.

## Status

Pre-release. Currently in active development; v1.0 is targeted at F-Droid. iOS is on the roadmap but not started — the shared module is already KMM, so the lift should be reasonable.

## Building from source

Windows host with PowerShell:

```powershell
.\gradlew.bat :mobile_clients:android:installDebug
adb shell am start -n app.indoorbike.debug/app.indoorbike.MainActivity
```

macOS / Linux: substitute `./gradlew` for `.\gradlew.bat`.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full build / test / variant workflow, and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the modules fit together.

## Community workouts

The Browse community section in the app fetches a registry of workout repositories from `BuildConfig.REGISTRY_URL` (default `https://indoorbike.app/registry.json`). Subscribed repositories sync incrementally — only changed bundles are re-downloaded — and the registry is graceful about being unreachable: if it can't be fetched, Browse just shows an empty state and users can still subscribe to a specific repo by URL or QR code.

To self-host a registry or repository, override `REGISTRY_URL` at build time, or simply add a repo by URL from inside the app. The on-disk schema is documented in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#workout-library--sources-import-repositories).

## Privacy

By default the app makes zero outbound network calls. Optional features that *do* talk to a server, all opt-in:

- **Community workout subscriptions** — only the URLs you've explicitly added.
- **Intervals.icu sync** — only when you've configured credentials and triggered an upload.
- **Auth0 / CleverCloud account sync** — only when you sign in.

Full privacy policy: [website/privacy.html](website/privacy.html).

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).

Third-party code and font attributions: [NOTICE.md](NOTICE.md).

## Repository

Primary location: [codeberg.org/juhanilehtimaki/IndoorCyclingApp](https://codeberg.org/juhanilehtimaki/IndoorCyclingApp).
