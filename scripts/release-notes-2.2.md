Indoor Bike 2.2

Your gears bite, your bike has mass, and another app can drive the road.

Added
- **Companion mode: hand your trainer to a game or app on another device.** It runs on your own network, decides the road, and this app turns that road into resistance and records the ride. No account, no server in the middle, and nothing to sign up for. It is labelled **Alpha**: it needs a second program running somewhere else, the protocol under it is still moving, and a companion built against it today may need updating. The [Companion API](https://indoorbike.app/companion/) documents the whole thing.

- **Pair a companion once and it connects on its own after that.** The pairing code is per ride: new code, new port, every time. That is the right ceremony for a stranger and the wrong one for the machine in the same room you have already trusted twenty times. Agree on both devices once and the two recognise each other from then on, with no code and nothing to tap, even though the address and port change every ride. The companion screen lists everything you have paired, when it last connected, and forgets one the moment you ask.

- **Your phone as a remote for a screen it cannot reach.** When the game puts a menu up, the app draws a pad of up, down, left, right, enter and back on the handlebars. Controls the game does not want are drawn visibly dead rather than hidden, so you never press something that looks live and get nothing.

- **The game can put you on its own bike.** A mountain stage that hands out a 32-tooth granny ring, a track session on a fixed gear. Optional: without it you ride the drivetrain you chose in settings, and your own setting is waiting untouched underneath.

Changed
- **Gear shifts you can feel, on every road.** Speed now follows cadence through the gear you are in, the way a chain does, rather than being solved for separately. Downshifting used to do almost nothing; now it changes what the pedals ask of you immediately, and the difference between gears grows with speed exactly as it does outdoors. This affects Route mode as well as Companion mode.

- **The bike has mass now.** Accelerating costs you something and easing off carries you, instead of speed snapping to whatever the hill implies. That is most of what made the old feel wrong on rolling ground.

- **Pulling away no longer grinds.** From a standstill almost none of the resistance is air, so what was left was gravity, rolling and the mass being moved: heavy pedals and nothing moving to show for it. The app now gives you a hand off the line and fades to the full physics over ten seconds once you are past 5 km/h. Hill starts get the same help, since that is the worst case of the same problem.

- **In Companion mode the game decides when the ride starts, and there is no countdown.** The road and your numbers arrive the moment the game connects, but the clock does not run until the game says the ride has begun. You can spin up in a menu or on a start line and none of it lands in your ride record.

- **A finished companion ride hands you back to the companion screen.** Your game is still running on the other device and you are still on the bike, so the app goes back to waiting for it rather than dropping you on the home screen.

Fixed
- **A companion ride could not be followed by another one.** Reopening the screen after a ride showed a pairing card with no address on it, and the second ride of an evening simply never started.

- **Pairing keys were reaching Android's automatic backup.** A companion's key lives in app-private storage but was not excluded from Auto Backup or phone-to-phone transfer, so it could have been swept to a Google account by an app whose whole point is not using Google services. Excluded from both now, and from Backup & sync. A restored phone pairs again, which is one tap each.

- **A ride resumed from the unfinished-ride banner lost its road.** It saved without the map and elevation chart it had been recording.

Notes
- **Indoor Bike is now owned by Snapp Technology Ltd.** The copyright notices say so. The licence is unchanged, the code is in the same place, and the app is signed with the same key, so this is an ordinary update.
- The privacy policy now covers Companion mode: what is sent, to whom, that it stays on your network, and that the connection is not encrypted, so a pairing decides who can drive your trainer rather than who else on the network can see the traffic.
- Nothing to redo. Your workouts, history, paired devices and settings carry over.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 18`.
