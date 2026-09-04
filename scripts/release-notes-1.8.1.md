Indoor Bike 1.8.1

Two fixes for problems found in 1.8, both reported by @ghose.

Fixed
- **Typing a new FTP, weight or maximum heart rate now replaces the old value.** Tapping one of those fields put the cursor inside the number instead of selecting it, so typing 250 into a field showing 265 produced 2250. That is over the allowed limit, so it was discarded and the old value came straight back, with nothing on screen to explain why. It looked exactly like the setting refusing to save, and whether you hit it was luck: clear the field first and it worked, type straight over the top and it did not. That is why weight would save while FTP and heart rate seemed not to. The value is now selected when you tap the field, so typing replaces it.

- **FitTrackee no longer needs a sport list it usually cannot read.** Reading that list needs a permission FitTrackee's own app-creation screen does not reliably offer, so a correctly connected server could still report that rides could not be filed. Rides are now filed as **Cycling (Virtual)**, which is what an indoor ride is; those sport numbers come from FitTrackee's own migrations and are the same on every server. If your server does share its list, you can still pick something else.

Notes
- If you set an FTP, weight or maximum heart rate in 1.8 or earlier and it did not stick, it is worth checking those three values after updating.
- Already connected to FitTrackee? Nothing to redo. If the app told you it could not read your sport list, that message is gone and uploads will work.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.
- **Development has moved to [CodeFloe](https://codefloe.com/SnappTechnology/IndoorCyclingApp).** Codeberg remains a release mirror for now, so nothing breaks today. When you get a chance, install ObtainX and point it at the CodeFloe URL: Obtainium cannot track CodeFloe, so this is a change of app as well as a change of address.

Signed with the project's release key; `versionCode 12`.
