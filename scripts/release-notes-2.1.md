Indoor Bike 2.1

The app fits the glass it is on.

Changed
- **A tablet now gets a tablet's layout, not a phone's stretched across the glass.** Until now only the two ride HUDs adapted to a big screen. Everything else drew its phone layout at full width: route cards nearly two thousand points wide with the chevron marooned from its title, paragraphs running the whole width of the display, and dialogs sized at 85% of a tablet, which is a banner rather than a dialog. Eleven screens now hold their content to a readable column, left aligned so the text and the title above it share an edge, and six dialogs are capped.

- **Side by side now means the screen is actually wider than it is tall.** A tablet held upright gets the stacked layout, which is what all that height is for, instead of a map squeezed in beside a column of empty space. The simulation HUD spreads its slack across the screen rather than pouring all of it into the power number.

- **The space a narrower column leaves over is the app's own horizon.** Three slate planes, a crest line and a single contour: the same motif as the ride screen's backdrop and considerably quieter, because that one sits behind a single number and this sits next to things you are reading. It is not animated, not coloured, and it is the same hill every time, so it never becomes the thing you look at. Nothing paints at all until the margin is wide enough to hold it, so phones and upright tablets are unaffected.

Fixed
- **A continued route ride reported the whole road rather than the part you rode.** Pick a route up at 42 km, ride 6 more, and the summary claimed 48 km and drew the entire road behind you as the day's work. The stored figure is a position along the route, not a distance covered, and the post-ride screen was reading it as the latter.

Notes
- **Indoor Bike is on Google Play from this release.** Nothing changes here: CodeFloe remains the home of the code and ObtainX remains the way to get it without a Google account. Play is simply a second door for people who want one.
- Nothing to redo. Your workouts, history, paired devices and settings carry over.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 17`.
