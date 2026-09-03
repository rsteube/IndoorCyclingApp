Indoor Bike 1.8

Settings that stay set, a share card that isn't squashed, and rides that can go to your own FitTrackee server.

Added
- **Upload rides to your own FitTrackee server.** FitTrackee is self-hosted, so unlike Intervals.icu there is nothing to sign up to: you point the app at a server you run. Upload by hand from the ride summary or automatically when a ride is saved, and the summary shows which services a ride has already gone to. **You approve it in your browser and your password never reaches the app** - sign-in is OAuth2 with PKCE against your own server, so what the app holds is a token that can only upload workouts and read your profile, renews itself in the background, and can be revoked from FitTrackee without changing anything else. FitTrackee won't let an app register itself, so create one under Profile → Apps and give the app its client id and secret; the Connections page shows you the exact redirect URI to paste, with a copy button.

  One limitation, stated up front because it is FitTrackee's and not something the app can fix: FitTrackee is built for outdoor activities and only accepts workout files containing GPS coordinates, which an indoor ride has none of. Rides therefore arrive as an entry with the date, duration and estimated distance, with your average and normalised power, cadence, heart rate, TSS and kJ written into the notes. There is no power graph. The app offers the file every time anyway, so if FitTrackee ever accepts a file without GPS, rides start arriving in full with no update needed. Export a FIT file if you need the full trace elsewhere.

- **A Connections page in Settings.** Intervals.icu and FitTrackee now live together on their own page rather than stretching Settings out, and Settings shows a single row saying what is connected.

Fixed
- **Settings now save whatever way you leave the screen.** FTP, weight, maximum heart rate and the device name were only written when the field lost focus or you pressed the keyboard's done key. Leaving Settings any other way - switching tabs with the keyboard still up, or the system reclaiming the app mid-edit - threw the change away without a word, and the old value came back on the next launch. Each field now saves as soon as what you have typed is usable, so there is no longer a wrong way to leave. Thanks to @ghose for finding this.
- **The Intervals.icu auto-upload switch can always be turned off.** It was greyed out unless both an athlete id and an API key were present, so anyone who turned auto-upload on and later cleared their credentials was left with a switch that said off, refused to move, and could not be turned off.
- **The share card is no longer squashed into a corner.** The card is drawn at 1080x1080 but was being laid out inside the share sheet's width instead, so its labels wrapped mid-word and the preview showed it shrunk into the top-left. The image it produced was never the size the rest of the sharing code assumed either, which may also be why some apps received nothing when you shared it.

Notes
- If you use Intervals.icu, nothing changes except where the settings live: Settings → Connections.
- The settings bug affected every Android version, not just older ones. If you ever set an FTP or weight that did not stick, it is worth checking those values after updating.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.
- **Development has moved to [CodeFloe](https://codefloe.com/SnappTechnology/IndoorCyclingApp).** Codeberg remains a release mirror for now, so nothing breaks today. When you get a chance, install ObtainX and point it at the CodeFloe URL: Obtainium cannot track CodeFloe, so this is a change of app as well as a change of address.

Signed with the project's release key; `versionCode 11`.
