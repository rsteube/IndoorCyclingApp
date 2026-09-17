Indoor Bike 2.4

Connect Intervals.icu in a browser instead of pasting a key, and a paused ride now exports as the ride you actually did.

Added
- **Connect Intervals.icu with one tap.** Settings, then Connections, then Connect. You approve Indoor Bike on Intervals.icu's own page in your browser, so your password and API key never reach the app, and it is allowed to upload your rides and nothing else. Disconnect takes that permission back on Intervals.icu as well as forgetting it here. If you already had an API key saved, connecting replaces it.

- **A companion can let you ride without recording it.** Pushing the bike up a climb the game will not let you ride is real work that does not belong in the activity. A game can now say so: the road and your numbers carry on, the trainer keeps working, and nothing goes into the ride. It can also hold you at a wattage while you are in that state, with the brake coming off by itself if you stop pedalling or the game goes quiet. Rides are unaffected until the game you use supports it.

Fixed
- **A paused ride exported short.** Every second you spent paused pushed a second of real riding off the end of the FIT or TCX file, and a ride resumed after the app was killed lost everything recorded after the interruption. Files now carry each sample at the moment it was taken, and mark the pauses: FIT writes a timer stop and start around each one, the way a head unit does, and reports rolling time and total time separately. Laps are cut on riding time.

- **A ride ended early claimed laps it did not contain.** The lap count in an exported FIT is now the number of laps actually in the file.

Notes
- The Intervals.icu connection uses their OAuth, which they ask apps used by more than one person to use. A build made from the public source has no OAuth client of its own and offers the API key instead, exactly as before.
- Nothing to redo. Your workouts, history, paired devices and settings carry over.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 20`.
