Indoor Bike 1.5

Cadence targets for intervals, and two fixes for rides that could be lost before they reached your history.

Added
- **Cadence targets on intervals**: an interval can now ask for a cadence as well as a power, either a single value or a range. The trainer still holds the power in ERG mode exactly as before; the target just tells you how to spin while it does, which is how you write a climbing block at 70-80 rpm or leg speed at 105. Entirely optional, per interval.
- **Cadence coaching while you ride**: the CADENCE card shows the target with an arrow for which way to move - up to spin faster, down to ease off, a tick when you are there. The number turns green on target and amber off it, and a small bar underneath shows how far off you are, because being 2 rpm under and 30 rpm under call for different responses. If your trainer does not report cadence, the target is shown without a verdict rather than nagging about a miss it cannot see.
- **Set targets in the editor**: tick CADENCE on a selected block in the web editor, then give it a minimum and, if you want a range, a maximum. Third-party links can carry targets too, via a new `r` suffix in the editor URL API (`45m@78r70-80`).

Fixed
- **Resuming after a crash no longer throws away everything you rode before it.** The app remembered where you were in the workout but not what you had done to get there, so a session interrupted an hour in and resumed was saved with the full duration against a power curve covering only the minutes after the resume - and the exported .fit was that short too. Your ride is now written to disk as you go and replayed when you tap Continue, so an interruption costs a few seconds instead of the whole session. Thanks to the rider who reported this with a .fit file attached; that is what made it findable.
- **A finished ride can no longer be lost before it reaches your history.** Between the trophy screen appearing and you tapping View Summary, the ride existed only in memory, so anything that killed the app in that window took it with it - as did simply backing out of the completion screen. The ride is now held on disk until the save actually lands, and if it never did you get a "Ride not saved" banner offering to put it in your history.

Notes
- Workouts that carry a cadence target are saved as format v2. Workouts without one stay v1, so they still open in older versions of the app.

Install
- Add this repo to [Obtainium](https://github.com/ImranR98/Obtainium) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 8`.
