# Notices and attributions

Indoor Bike is licensed under the GNU General Public License v3.0 (see [LICENSE](LICENSE)). This product includes the following third-party software and assets, distributed under their respective licenses.

## Libraries

| Component | Purpose | License | Source |
| --- | --- | --- | --- |
| Kotlin / kotlinx-coroutines / kotlinx-serialization | Core language and runtime | Apache 2.0 | https://github.com/JetBrains/kotlin · https://github.com/Kotlin/kotlinx.coroutines · https://github.com/Kotlin/kotlinx.serialization |
| Jetpack Compose & AndroidX (core, activity, lifecycle, datastore, documentfile, navigation3) | UI toolkit and Android extensions | Apache 2.0 | https://developer.android.com/jetpack |
| Compose Material 3 & material-icons-extended | Material 3 components and icon set | Apache 2.0 | https://developer.android.com/jetpack/androidx/releases/compose-material3 |
| Juul Kable | Multiplatform Bluetooth Low Energy | Apache 2.0 | https://github.com/JuulLabs/kable |
| SQLDelight | Type-safe SQL database access | Apache 2.0 | https://github.com/cashapp/sqldelight |
| CameraX (camera-camera2, camera-lifecycle, camera-view) | Camera preview for QR scanning | Apache 2.0 | https://developer.android.com/jetpack/androidx/releases/camera |
| ZXing core | QR / barcode decoding (pure Java; not affiliated with Google) | Apache 2.0 | https://github.com/zxing/zxing |
| Android Gradle Plugin · Kotlin Gradle Plugin | Build tooling | Apache 2.0 | https://developer.android.com/build · https://kotlinlang.org/ |

The full text of the Apache 2.0 license is available at https://www.apache.org/licenses/LICENSE-2.0.

## Fonts

All fonts ship as static / variable TTF files, in [mobile_clients/android/src/main/res/font/](mobile_clients/android/src/main/res/font/) for the app and [website/fonts/](website/fonts/) for the site. Nothing is downloaded at runtime; neither the app nor the website requests a webfont from a CDN.

| Font | License | Source |
| --- | --- | --- |
| Manrope (variable) | SIL Open Font License 1.1 | https://github.com/sharanda/manrope |

The SIL Open Font License is available at https://openfontlicense.org/.

## Icons

| Asset | Purpose | License | Source |
| --- | --- | --- | --- |
| Material Symbols Outlined (weight 300) | Icon glyphs, inlined as an SVG sprite in each website page | Apache 2.0 | https://github.com/google/material-design-icons |
| Simple Icons: the Sailfish OS mark | The Sailfish OS card on the website's install section | CC0 1.0 (the trademark stays Jolla's) | https://simpleicons.org |
| The Android robot | The Android card on the website's install section, from Material Symbols' `android` glyph | CC BY 3.0, attributed in the site footer | https://developer.android.com/distribute/marketing-tools/brand-guidelines |

The website inlines only the glyphs it uses; nothing is fetched from a font CDN at runtime.

## Trademarks

"Wahoo" and "Kickr" are trademarks of Wahoo Fitness LLC. "Zwift", "Zwift Click", "Zwift Play", and "Zwift Ride" are trademarks of Zwift Inc. "Garmin" is a trademark of Garmin Ltd. "Android" is a trademark of Google LLC, "Sailfish OS" of Jolla Mobile Ltd, and "iPhone" and "App Store" of Apple Inc. These names are used here only to describe device and platform compatibility; the trademark holders neither sponsor nor endorse this project.
