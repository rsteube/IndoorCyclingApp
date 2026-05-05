App definition:

This is an indoor cycling app that will connect to my smart trainier (Wahoo Kickr Core).

The app will offer different structured workouts that I can ride. The workouts should be based on power. The trainer will be controlled in ERG mode. The workout structures will be defined in JSON files. 

We'll use common structure for workouts based on what users have gotten used to on other platforms (Zwift, TrainerRoad, etc.). Each powerzone will have a colour, duration and power. The powerlevels will be defined in percentages of FTP.

The UI will let user select which workout to ride. During the ride UI will show the workout progession in zones and automatically control the trainer in ERG mode. Once workout is completed, the app will save the workout to a file. 

The app core will be built using Kotlin Multiplatform Mobile (KMM). The UI will be built using Jetpack Compose for Android and SwiftUI for iOS. The app will use standard BLE APIs to communicate with the Wahoo Kickr Core.

For now, we'll build only the Android version of the app. Once the Android version is working, we'll build the iOS version.

The app will be releasd in F-Droid app store. So do not use any google services and pay attention to privacy rules. Make the app as open-source as possible. Flag any required packages that might casue F-Droid anti-features.

UI needs to beclean and modern. Use dark theme by default. We want to have an exciting and indicing experience for riders to ride.

We want to build in a rider progreession system that will reward users for riding. This will be handled locally but we will later on create a way to sync this data to a remote backend using CleverCLoud but this is optional and the app must fully work without any user accounts. The user login will be handled with Auth0 but this is optional and the app must fully work without any user accounts.

In a later version I want to add an editor for workouts that will let users create and edit their own workouts in a user friendly way. This should be build in a way that it is easy to add new features to it.  

Consider the possibility of connecting Zwift Click (v1) controller to adjust difficulty during the wokrout. Same controls (+/-) should be avalable in the app UI if controller is not connected. these controls lower and raise the power targets during the workout.



Techincal definition:
Maintain TECH_SPEC_FOR_LLMS.md file for all techincal architecture and documentation. LLMs can use this file to quickly get up to speed with the project.


Design system:
Use the design system defined in cadence-design-system/README.md


Running the app on a connected Android device (Windows host, PowerShell):
- The repo has only `gradlew.bat` — invoke it via PowerShell, not bash.
- Build + install the debug variant:
  `.\gradlew.bat :mobile_clients:android:installDebug`
- Launch (note the `.debug` suffix on the application id — the manifest activity name stays `app.indoorbike.MainActivity`):
  `adb -s <device-serial> shell am start -n app.indoorbike.debug/app.indoorbike.MainActivity`
- For the release variant: `am start -n app.indoorbike/app.indoorbike.MainActivity`.
- List devices first with `adb devices` if you don't already know the serial.
- The `android` CLI's `android run` requires `--apks` paths and is more awkward than the gradle install task above; prefer `installDebug` + `am start`.
- The `:shared` module's compile task is `:shared:compileDebugKotlinAndroid` (not `compileKotlinAndroid` — that name is ambiguous and fails).

Build variants and side-by-side install (since v1.0):
- Debug: `applicationId = app.indoorbike.debug`, `versionName = 1.0-debug`, label "Indoor Bike Debug", grayscale launcher icon. Lives at `src/debug/res/`.
- Release: `applicationId = app.indoorbike`, `versionName = 1.0`, label "Indoor Bike", brand-orange icon.
- Both can be installed on the same device simultaneously and have completely separate storage (DataStore + SQLDelight DB).
- After a fresh `pm clear app.indoorbike.debug` the next launch is a true first-run (no FTP / weight / paired devices, onboarding banner shows).

Testing:
- Smoke test (build + install + walk through onboarding + mock-trainer ride + logcat scan):
  `.\scripts\smoke_test.ps1`
- Full manual checklist + smoke-test docs: see [TESTING.md](TESTING.md).
- Shared-module unit tests: `.\gradlew.bat :shared:allTests`.

Versioning: bump `versionCode` and `versionName` in [mobile_clients/android/build.gradle.kts](mobile_clients/android/build.gradle.kts). The debug build automatically picks up `-debug` suffix on the version name.


Website:
The app has a website that is just for app rpromotion purposes. The site is fully static website that can be deployed to any hosting provided. The domain is indoorbike.app
