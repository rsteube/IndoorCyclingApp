Indoor Bike 2.9

Rides from a companion game can carry the road they were ridden on.

Added
- **A ride ridden in a companion game can carry its track.** A game that says where you are on its course (Companion API 0.11) has that recorded every second beside your power, and the ride's FIT and TCX files carry it as position and altitude, so Intervals.icu, Strava and the rest draw a map of the road you rode, the way they do for Zwift. Each second's distance is the game's own count too, rather than the ride's total shared out over it. Nothing is worked out on the phone: a second the game said nothing about has no position, never a guess. Games that send no position, and every other kind of ride, export exactly as before.

Notes
- The track arrives as soon as your game sends it. Col de Berg is adding it; until a game does, its rides export as in 2.8.2.
- Companion games: the new `lat`, `lon` and `altM` fields of the `world` frame are described in the Companion API documentation. They are optional, and an older app ignores them with a warning.
- Under the hood, much of the app's logic moved into its shared core so an iPhone and iPad version can use it. Nothing about how the Android app works is meant to have changed.
- Nothing to redo. Your workouts, history, paired devices, connections and settings carry over.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 28`.
