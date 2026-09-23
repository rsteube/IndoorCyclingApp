Indoor Bike 2.8

Every ride mode now works on smart bikes and trainers that only speak FTMS, shifting feels like shifting a real bike, and a long list of fixes from a full review of the app.

Added
- **Every ride mode works on every machine that can be told anything.** The app asks a trainer which commands it accepts and fills each gap from what it has. A trainer with ERG but no road simulation rides routes and companion worlds as the wattage the road asks for at your cadence, so hills still bite. A bike that only takes a resistance level is held on a workout's wattage, and on a road's, by measuring your power and moving the level until they agree. A machine that accepts none of them is ridden by hand, and says so once. The trainer's settings sheet says which of these your machine gets.

- **A share card credits the game the ride was ridden with.** A companion that sends a name and an icon has both kept with the ride, and the card's footer carries them beside the app's own wordmark.

- **Take control, on the companion HUD.** Next to the game's name and the address it connected from, one tap lets go of everything the game was holding: the hill, a power target, an untracked stretch. The ride keeps recording on a flat road.

Changed
- **Shifting feels like a bicycle.** An upshift at speed changes the force on the pedals by the ratio between the two gears, on the flat and on a climb, instead of hitting a wall; shifts land at once and in order; and a downshift eases off for a moment while the legs spin up, as a real bike's momentum does. The gravel and mountain bike cassettes are now SRAM's XPLR 10-44 and Eagle 10-52, sprocket for sprocket.

- **A workout link can no longer replace a workout silently.** Importing one that shares an id with a workout you have asks whether to replace it or keep both, and a starter workout is never replaced.

- **Connection credentials are encrypted on the phone,** with a key held in Android's keystore. Existing connections are sealed on the first start.

- **Companion mode is harder to misuse from the network,** with limits on handshakes, connections and wrong pairing codes. A game that behaves notices none of them; they are written up in the Companion API documentation.

- **The ride screen does less work,** redrawing only the readouts that change, and Android 12 and later no longer see a location permission.

Fixed
- **Smart bikes and spin bikes recorded one wattage for the whole ride.** On a machine that reports through FTMS alone, power and cadence froze on the first reading. Every reading is taken now.

- **Continuing a ride after backing out of it lost everything before the back-out.** The whole ride is kept.

- **Rides ran long, and a clock change could pause one.** The ride clock is now a stopwatch: a forty-interval workout no longer runs twenty seconds past its plan, and a network time correction no longer moves it.

- **Changes made on this phone were undone by the next backup sync.** Each setting now keeps the time it changed, and the newest change wins field by field. An unreadable backup file no longer brings back deleted rides, and a mass delete from another device pauses sync and asks.

- **Companion rides were never uploaded automatically,** and leaving the summary during an upload could upload a ride twice. Uploads now start the moment a ride is saved.

- **Losing and regaining a trainer went wrong in several ways,** from stale readings left on screen to a trainer that came back mid-interval staying out of ERG. The heart-rate strap and handlebar controller had the same faults and have the same fixes.

- **Rides longer than four hours could lose their hold on the phone** with the screen off.

- Also: a ride could be saved or opened twice; the ride screen could come back empty after the app was closed in the background; exports said a missing heart rate was 0 bpm; settings could lose typed characters; two GPX files with the same name kept only one; a recorded Zwift Click press could be replayed; FitTrackee called an unreachable server an expired sign-in; reopening from Recents could import a shared file again; the Bluetooth permission request could be dropped on a first ride; and saving a share card froze the sheet.

Notes
- Nothing to redo. Your workouts, history, paired devices, connections and settings carry over.
- Companion games: see the new limits in the Companion API documentation before you ship against 2.8.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.

Signed with the project's release key; `versionCode 25`.
