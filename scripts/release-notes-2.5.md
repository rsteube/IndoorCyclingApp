Indoor Bike 2.5

The app now asks who you are before it scales anything to you, the ride screen reads at arm's length, and a tablet stops pretending to be a phone.

Added
- **It asks for your FTP and your weight.** Every target, zone, TSS, W/kg and XP figure in the app is arithmetic on those two numbers, and until now nothing ever asked for them: a new install simply rode at 200 W and 75 kg and said nothing about it. A welcome card on the ride screen now offers to set them, in about thirty seconds, and explains what Bluetooth and notifications are for while it is there. If you have been riding on the defaults, Settings has always had both, and this changes nothing you already set.

- **A tablet shows the list and the thing beside each other.** Workouts and History now use the width they have: the library on the left, the workout you are looking at on the right, and the same for your ride history and a ride's summary. Comparing three sessions no longer costs a trip in and back out for each one. The navigation rail also stays where it is when you open something, instead of vanishing. Phones are unchanged.

Changed
- **A companion ride shows the power, and the shift paddles, and nothing else.** The speed, distance, gradient, gear and climb were being drawn twice: once by the game in front of you, again on the phone on your bars. The phone now shows the one reading the game cannot, as large as the screen will hold it, with the W/kg under it and the paddles where they were. Companion mode's own screen has **Show every reading while riding** if you want the full set back. Off by default, remembered between rides.

- **The wattage is the brightest thing on the ride screen.** The zone colours the number is drawn in were too dim against the background to read while sweating: four of the seven sat below the contrast floor for large text, and the worst of them was the zone you are in when you are working hardest. Every colour has been lifted along its own hue, so red is still red. Cadence and heart rate step back one shade, because they flank the number you are chasing rather than compete with it.

- **Companion mode leads with the address and the code.** The two paragraphs explaining what companion mode is were sitting above the connection details you came back for, pushing them off the bottom of the screen. They now follow.

Fixed
- **Backing out of a ride asks first.** Pressing back while riding stopped the workout and left. The ride did survive, on the unfinished-ride banner, but nothing said so at the moment it mattered. Back now asks with the same wording End & save uses: the ride so far is saved, and it cannot be resumed.

- **The bottom row of a free ride's numbers is no longer cropped.** On a tablet, elapsed, average power and target were laid out flush against the bottom edge and cut off.

- **Leaving a companion ride left you in companion mode.** Backing out of the ride, or closing the panel after the session ended, returned to the pairing screen, which starts listening again the moment it appears. A permanently paired game reconnected by itself and drove you straight back into a ride. Both now return to the ride picker, with the socket closed behind them.

Notes
- Nothing to redo. Your workouts, history, paired devices and settings carry over.
- The rider-facing half of protocol 0.10, untracked riding, shipped in 2.4 and is unchanged here.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 21`.
