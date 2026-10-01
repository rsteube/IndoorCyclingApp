Indoor Bike 2.8.2

Rides you share now say where they came from.

Fixed
- **Rides showed up elsewhere as recorded with "1".** A ride's FIT file named its maker only as manufacturer 255, product 1, so Intervals.icu listed the device as "1", and so did every service reading rides from it. Files now name Indoor Bike and its version where readers look for them, and uploads to Intervals.icu set the device to Indoor Bike as well. This applies to uploads to Intervals.icu and FitTrackee and to files you export and upload by hand.

Added
- **A ride ridden in a companion game says which one.** The game's name and the course it named go into the ride's FIT file, so any service can credit the game, and the ride is marked as a virtual ride. On Intervals.icu it is named like "Col de Berg: Stage 3" and described as ridden in Col de Berg on Indoor Bike.

Notes
- Rides already on Intervals.icu keep the device they were given. Intervals.icu can rename the device on many activities at once if you want older rides to say Indoor Bike too.
- Companion games: your `brand` name now credits the rides you drive, so keep it the same from one version to the next. See the Companion API documentation.
- Nothing to redo. Your workouts, history, paired devices, connections and settings carry over.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 27`.
