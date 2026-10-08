Indoor Bike 2.10

Ride freely through a companion game's world and choose every turn from your phone, and give a workout's efforts a range instead of a single number.

Added
- **Free rides in a companion game, with the turns chosen on the phone.** A game can start a free ride with no course and no length known in advance. As each junction comes up, the phone shows a card with a button for every way on: an arrow pointing where it goes, the road's name, where it leads, and the climb and surface ahead. Tap a road to take it. The card always shows what the game says the ride will do, and when the game sends the shape of the junction, the card draws it the way the game does, with your road in bold and a countdown to the moment the choice becomes final.
- **Choose a destination.** On a free ride, one tap on **Choose a destination** lists the places nearby, nearest first, and four buttons narrow the list to squares, monuments, food and drink, or nature. Pick one and the game routes you there, with the distance, the time and the next turn on the phone, and a note when you arrive.
- **A segment can ask for a power range.** A workout can say "90-105% FTP" rather than one number. The trainer starts at the segment's power, and the ± buttons move the target anywhere inside the range but no further: for efforts you should set yourself within limits, such as a long threshold block or a test. The website editor writes ranges (and links take `20m@90-105`), and the workout page and the ride screen show them.

Fixed
- **FitTrackee's setup text said ride figures go in the notes.** They go in the description since 2.9.1.
- **The website editor dropped cadence targets** from a workout opened from a file or a community repo.

Notes
- Free rides need a game that offers them. Col de Berg is the first. The iPhone and Sailfish OS apps speak the new protocol but leave the steering to the game for now.
- Companion games: this is Companion API 0.13. The new `junction`, `navigation` and `places` frames, the `turnChosen`, `placesQuery` and destination events, and the `routeChoice`, `navigation` and `placeCategories` capabilities are described in the Companion API documentation. All of it is optional, and a game that sends none of it rides exactly as before.
- Apps from before power ranges ride a ranged segment at its starting power.
- Nothing to redo. Your workouts, history, paired devices, connections and settings carry over.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 30`.
