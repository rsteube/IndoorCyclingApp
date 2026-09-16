Indoor Bike 2.3

Companion mode grows up: a session is many rides, every one of them is kept, and the game can look like itself on your phone.

Added
- **Screenshots from the game, kept with your ride.** When the companion supports it, a camera button appears on the ride header. Tap it and the game sends a picture of its screen, which is kept with the ride you are on. They show on the ride's summary and in History as thumbnails that open full screen, and under each ride on the session panel. They stay on your phone and are deleted with the ride.

- **The companion can bring its own look.** Its icon, name and colours on the ride header, and its accent on the shift paddles while you press them. Zone colours, warnings and the app's own controls stay the app's, so nothing you have learnt to read changes meaning.

- **Gravel feels like gravel.** A companion can say what the road is made of, and the trainer pushes back harder on gravel, dirt and grass than on tarmac, instead of the game only showing a different texture under the wheels.

Changed
- **A companion session is many rides.** When the game ends a ride, the app saves it on the spot and stays connected for the next one. There is no summary screen in between, because you are still on the bike; the header says the ride was saved.

- **Shifting is the biggest thing on the companion screen.** Full-height Easier and Harder paddles on the two edges in landscape, across the bottom in portrait. They shift the moment your thumb lands, so a tap on a bouncing bike still changes gear.

- **If the game goes away mid-ride, the ride pauses and waits.** The trainer goes off, nothing is recorded, and a panel says so plainly. When the game comes back the ride carries on by itself. If it is not coming back, End ride saves what you rode, and the panel lists every ride of the session.

- **The ride header shares its space.** The status line no longer squeezes the ride's name down to one letter, and an upright tablet gets the two-row header meant for it. This affects every ride mode.

Fixed
- **Companion rides were not being saved.** A ride was written when you tapped through to a summary, which in Companion mode nobody does. It is now written the moment the game ends it.

- **Two rides could be saved as one.** A game that wrote its elevations as `921.0` had the end of its ride refused, so the ride never ended and the next one was saved on top of it, under the second name with both rides' time. Numbers are now read for their value, and the end of a ride always ends it.

- **The next ride could rename the last one and wipe its map.** A game that names each ride before starting it had that name applied to the ride it had just saved. The two are now told apart.

- **Skip broke a companion ride.** It jumped past the whole ride. It is gone from Companion mode.

- **A companion ride interrupted by the app closing could not be recovered.** The unfinished-ride banner now offers it as a ride to save.

- **A companion ride's summary showed an empty "Power adherence" card.** A ride the game drove had no plan to adhere to.

Notes
- Companion protocol 0.9. Companions built for earlier versions keep working; the [Companion API](https://indoorbike.app/companion/) describes what is new.
- The privacy policy now covers screenshots: stored only on your phone, never exported, uploaded or synced.
- Nothing to redo. Your workouts, history, paired devices and settings carry over.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 19`.
