Indoor Bike 2.7

The app no longer closes on itself mid-ride, companion mode opens when you ask for it, and a ride's own pictures can be the card you share.

Added
- **Share the ride with a picture from it.** If a companion took screenshots during a ride, sharing it now offers them: a row under the preview holds the ride card as it was and a tile for each picture, and the one you pick fills the panel where the chart or the road would be, with the ride's numbers still around it. More than three pictures and the row keeps the first few and opens the rest as a grid.

- **The gear is on the companion ride screen.** The paddles either side change it, so the screen they are on now says what they changed it to: the gear number and the cassette ladder, above the power, in both orientations.

Fixed
- **The app could close in the middle of a ride.** Starting a ride asks Android to keep the app running with a notification, and Android kills the whole app when that notification does not appear in time. Two things were making that likely: one ride ending as the next began, which is every lap of a companion session, and the app asking to start again on every change of state, auto-pause included. Neither happens now, and a ride that hands over to the next one does so without stopping in between.

- **Companion mode sent you home to pair a trainer.** Tapping Companion without a trainer opened the pairing screen, and that screen closes itself the moment the trainer answers, so you ended up back on the ride picker having to tap Companion again. Companion mode now opens whatever is paired. It asks for a trainer itself, with a button, and pairing comes back to it with the address and the pairing code already waiting.

- **Back could go one step too far.** On a screen reached an unusual way, back could leave the app with nothing to show instead of returning where it came from.

- **The share preview cut the card's edges.** The preview lost the card's left margin and the first letter of its title. What it shows is the card you get again.

Notes
- Nothing to redo. Your workouts, history, paired devices and settings carry over.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 23`.
