# Changelog

All notable changes to **Indoor Bike** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); versions map to the Android
`versionName` (with `versionCode` in parentheses).

## [Unreleased]

### Added

- **Run practice from the bike (Companion API 0.14).** A companion game can
  make a ride a run: a trail runner on its course, driven by pedalling the
  trainer, for learning a race course and cross-training before it. Specified
  with Col de Berg, whose K110 Innsbruck course is the first. During a run the
  ride screen shows pace in min/km instead of speed and the course's real
  grade, with no shift paddles or gear card: the game gives the runner one
  gear, and every shifter, Zwift Click included, is ignored. The trainer feels
  the load the game sets and nothing of a bicycle's air or momentum. The ride
  is saved and exported as a bike ride, so an invented pace never reaches
  running records; the ride summary shows its distance and average pace
  instead of a distance estimated from power. A bike ride after a run is back
  on the rider's own bike. Android only for now; iOS and Sailfish keep riding
  such a game as a bike.

## [2.11.1] (32) - 2026-10-12

### Changed

- **A route ride started part way along is counted from where it started.**
  The ride screen's distance, climbing and the shading of the profile now
  begin at the start the rider chose, or where the last sitting stopped,
  instead of at the trailhead: joining a 1,200 km route at its 600 km mark
  reads as 0 km ridden, not 600 km. The strip under the profile gained a
  **Ridden** figure beside speed, distance to go and climbing, and the
  climbing figure counts the stretch being ridden rather than the whole road.

### Fixed

- **A ride that began part way along drew the road as ridden from the
  trailhead** on the ride summary, in past rides and on the share image. The
  elevation profile now shades only the stretch ridden, and both it and the
  route shape mark where the ride began as well as where it ended. The
  summary's caption no longer says the part before the start was ridden last
  time, since it may have been skipped on purpose.

## [2.11] (31) - 2026-10-10

### Added

- **Start a long route anywhere along it.** A route of 20 km or more now opens
  on its whole map and elevation profile before the ride. Tap the map, or tap
  or drag along the profile, to put the start where you want it: the foot of
  the climb, the second half, the place you stopped last time (offered as a
  button). The part before it is dimmed, the distance and climbing left are
  shown, and the ride starts there like any other. Shorter routes start at the
  beginning as before.

### Fixed

- **A route map that opened showing the whole route could hang the app.**
  The map tried to fit the route before it had a size, which osmdroid never
  finishes. It now waits until it is laid out.

## [2.10] (30) - 2026-10-08

### Added

- **A segment can ask for a power range.** A workout can say "90-105% FTP"
  rather than one number (`powerMinPercentFTP` / `powerMaxPercentFTP`). The
  trainer starts at the segment's power and the ± buttons move the target
  anywhere inside the range but no further, for efforts the rider should set
  within limits, such as a long threshold block or a test. The website editor
  writes ranges (and links take `20m@90-105`), the workout page and the ride
  screen show them, and apps from before ranges ride the segment's power as
  before.
- **Free rides in a companion game, with the turns chosen on the phone**
  (companion protocol 0.12 and 0.13, Android). A game can start a free ride with no
  length known in advance. As each junction comes up the phone shows a card
  with a button for every way on: an arrow at its bearing, the road's name,
  where it leads, and the climb and surface ahead. A tap takes that road, and
  the card always shows what the game says the ride will do, and draws the
  junction as the game does when the game sends its shape. **Choose a
  destination** lists the places nearby, nearest first, filtered by squares,
  monuments, food and drink, or nature, and the game then routes there, with the
  distance, time and next turn on the phone. Specified with Col de Berg. iOS
  and Sailfish speak the protocol and leave the steering to the game for now.

### Fixed

- **FitTrackee's setup text said ride figures go in the notes.** They go in the
  description since 2.9.1.
- **The website editor dropped cadence targets** from a workout opened from a
  file or a community repo.

## [2.9.1] (29) - 2026-10-06

### Changed

- **A ride's figures on FitTrackee go in its description, not its private
  notes.** The line saying the ride was recorded by Indoor Bike, with its
  power, cadence, heart rate, TSS and kJ, now follows the workout's visibility
  like the rest of it, and the notes are left for the rider. Suggested by a
  FitTrackee user.

## [2.9] (28) - 2026-10-02

### Added

- **A ride ridden in a companion game can carry its track.** A game that says
  where the rider is on its course (Companion API 0.11) has that recorded every
  second beside power, and the ride's FIT and TCX files carry it as position
  and altitude, so Intervals.icu, Strava and the rest draw the road the ride
  was on. Each second's distance is the game's own count too. Nothing is worked
  out on the phone: a second the game said nothing about has no position. Games
  that send no position, and every other kind of ride, export exactly as before.

## [2.8.2] (27) - 2026-10-01

### Fixed

- **Rides showed up elsewhere as recorded with "1".** A ride's FIT file named
  its maker only as manufacturer 255, product 1, so Intervals.icu listed the
  device as "1" and so did every service reading rides from it. Files now name
  Indoor Bike and its version where readers look (the file's product name and
  its creator device), and uploads to Intervals.icu set the device to Indoor
  Bike as well.

### Added

- **A ride ridden in a companion game says which one.** The game's name and the
  course it named go into the ride's FIT file, so any service can credit the
  game, and the ride is marked as a virtual ride. On Intervals.icu it is named
  like "Col de Berg: Stage 3" and described as ridden in Col de Berg on Indoor
  Bike.

## [2.8.1] (26) - 2026-09-27

### Fixed

- **A flatter road at speed could turn the pedals into a wall.** Down a fast
  descent in the hardest gear, a road easing from steep to gentle made the
  pedals go heavy at once: at 70 km/h on the gravel 1x the trainer was asked
  for the equivalent of a 20 % climb. On a bike the legs are not connected to
  a wheel they cannot keep up with, and a flatter road only slows the bike
  down. Now, while the legs are turning slower than the wheel, a road getting
  harder reaches them through the bike's momentum rather than at the pedals:
  the bike slows, and the resistance comes back as the legs catch up with it.
  Legs turning with the wheel meet a climb exactly as before. How a rider's
  cadence maps onto the road depends on the gear their own bike sits in on the
  trainer, so it is measured from the first stretch of steady riding in each
  ride, and nothing is held back until it has been.

## [2.8] (25) - 2026-09-23

### Fixed

- **An upshift at speed was a wall.** One sprocket asked the legs for about
  three times what a bicycle does: on a 1x gravel bike at 30 km/h, going from
  the 15 to the 13 wanted 45 % more force where a real bike wants 15 %. The road
  was being evaluated at the speed the new gear would turn out at the old
  cadence, which is the speed the bike would reach after accelerating into the
  gear, handed over instantly and for free; the air term squared it and the
  gear multiplied it again. The road is now evaluated at the bike's own speed,
  with its mass behind it, so a shift changes what the legs feel by the ratio
  between the two gears, on the flat and on a climb alike, exactly as outdoors.
  Companion mode needed a second half of the same fix: the speed the app sends a
  game was the same jumping number, and a game riding its world at it sent every
  upshift straight back. It is now the bike's modelled speed too, taken from the
  legs while pulling away so it still appears on the first turn of the crank.
  Games need no change to pick this up.

- **Downshifts arrived late.** Two things held a shift back. A shift that landed
  just after a routine road update waited for the loop's next tick, up to a
  quarter of a second, on about one shift in four on a descent; and the
  trainer's writes could reach it out of order, which occasionally left the
  pre-shift road in force for seconds. Shifts now go at once and in order. And a
  downshift now lets go of a little more than its ratio for about half a second
  while the legs spin up, which is what a real bike's momentum does for them and
  a trainer cannot.

- **The gravel and mountain bike cassettes were not quite the real ones.** The
  gravel 1x is now SRAM's XPLR 10-44 and the mountain 1x SRAM's Eagle 10-52,
  sprocket for sprocket.

- **Smart bikes and spin bikes recorded one wattage for the whole ride.** On a
  machine that reports through FTMS alone, with no separate power service, the
  first real reading was also the last: power and cadence froze on it until the
  bike disconnected, in history, in exports and on screen, and the ride never
  auto-paused. Every reading is now taken, and heart rate from the grips or a
  strap paired to the bike is used when nothing better is connected.

- **Continuing a ride after backing out of it lost everything before the
  back-out.** Pausing, pressing back and tapping Continue on the unfinished-ride
  banner replaced the ride's samples with an empty list, so the saved ride, its
  averages, its chart and its uploads covered only the part after continuing.
  The whole ride is kept now.

- **A ride could be saved twice, or opened twice.** A double tap on Save in the
  unfinished-ride banner wrote two rides, both of which synced; a double tap on
  Start opened two ride screens, and backing out of one left a blank one behind.
  Each now happens once.

- **After the app was closed in the background mid-ride, the ride screen came
  back empty,** with the system bars hidden and no way out but the back
  gesture. It now goes back to where the unfinished-ride banner is.

- **Rides ran long, and a clock change could pause one.** Every interval change
  and every pause dropped the fraction of a second in progress, so a forty-
  interval workout ran about twenty seconds past its plan and was saved shorter
  than it was ridden. And the ride clock was the phone's wall clock: a network
  time correction could run it backwards, or auto-pause a rider who was
  pedalling. The clock is now a stopwatch that carries milliseconds through.

- **An unfinished ride could be lost to a crash at the wrong moment.** The copy
  kept for recovery, the route progress and the paired companions are now each
  written to a new file that replaces the old one whole, so a crash mid-write
  leaves the previous version rather than half of the new one.

- **Exported rides said a missing heart rate was 0 bpm,** which dragged averages
  down in whatever read them. A second without a reading is now marked as
  having none, and the ride's FTP and interval boundaries are kept with it so an
  export made later matches the ride.

- **Losing and regaining a trainer went wrong in several ways.** Its last
  readings stayed on screen and in the ride after it went; a trainer that came
  back mid-interval stayed out of ERG until the next interval; one that failed
  with a Bluetooth error was retried every few seconds for ever; tapping a
  trainer while another was still connecting left the app connected to both;
  and backing out of the pairing screen left automatic reconnection off until
  the app restarted. The heart-rate strap and the handlebar controller had the
  same connection faults and have the same fixes.

- **A recorded Zwift Click press could be played back.** Every press is now
  accepted once.

- **Rides longer than four hours could lose their hold on the phone.** The wake
  lock that keeps the timer and the trainer running with the screen off expired
  after four hours; it is now renewed while the ride goes on. And a stop meant
  for one ride can no longer end the next one's service, which on a companion
  session could close the app.

- **Changes made on this phone were undone by the next sync.** A setting changed
  since the last sync, or an imported workout renamed, went back to the old
  value at the next launch. Each setting now keeps the time it was changed, and
  the newest change wins field by field, so two devices changing two different
  settings both keep theirs.

- **Settings could lose what was typed into them.** Two quick changes could be
  saved in either order, and a text field saving as you typed could drop
  characters. Changes are now saved one at a time, in order.

- **A backup file that could not be read once was taken for no backup at all,**
  which brought back rides deleted on this phone and passed them on to the other
  devices. The sync now stops and tries again, or uses the copy kept beside it.
  Deletes arriving from another device are checked like deletes made here: when
  one would remove most of the library, backup pauses and asks.

- **Companion rides were never uploaded automatically, and leaving the summary
  could upload a ride twice.** Automatic upload ran on the summary screen, which
  a companion ride never passes through; and tapping Done during an upload
  cancelled it on the phone after the server had it, so the ride still offered
  Upload. Uploads now run in the background from the moment a ride is saved, and
  leaving the screen does not stop them.

- **FitTrackee said a sign-in had expired when the server was only unreachable.**
  It now says it could not reach the server. And a stray `indoorbike://oauth`
  link can no longer cancel a sign-in in progress.

- **Adding two GPX files with the same name kept only the second,** and two
  repositories using the same route id overwrote each other's route and credit.
  Both are now kept.

- **A repository's new workouts could go unannounced.** When a bundle failed to
  download during a check it was still marked as checked, and its new workouts
  were not counted until the maintainer changed it again. It is now tried again
  at the next check.

- **Leaving Settings could interrupt a sync or a restore part way,** and report
  it as failed; pressing back while Intervals.icu or FitTrackee was finishing a
  sign-in could lose the sign-in. These now finish wherever the rider goes.

- **Reopening the app from Recents could import a shared file again,** undoing a
  rename since, or replay an old sign-in and report it as failed.

- **On a first ride, the Bluetooth permission request could be dropped** when
  the notification one was asked at the same moment. They are now asked in
  turn.

- **Saving or sharing a share card froze the sheet** while the image was encoded.
  It is encoded in the background now, and so is an exported ride file.

### Added

- **Every ride mode works on every machine that can be told anything.** The app
  now asks a trainer which commands it accepts, and fills each gap from what it
  has. A trainer with ERG but no road simulation rides routes and companion
  worlds as the wattage the road asks for at your cadence, so hills still bite
  and spinning faster still costs more. A bike that only takes a resistance
  level is held on a workout's wattage, and on a road's, by measuring your power
  and moving the level until they agree; it takes a few seconds to settle after
  each change. A machine that accepts none of them is ridden by hand in every
  mode, and says so once. The trainer's settings sheet says which of these your
  machine gets.

- **A share card credits the game the ride was ridden with.** A companion that
  sends a name and an icon now has both kept with the ride, and the card's
  footer carries them beside the app's own wordmark: "with", the game's icon
  drawn exactly as it sent it, and its name. Rides with no companion, and rides
  from before this, look exactly as they did. The name and icon are kept on the
  phone with the ride, are thrown away when the ride is, and go nowhere unless
  the rider makes a card and shares it.

### Changed

- **Companion mode is harder to misuse from the network.** A connection must
  finish its handshake within 10 seconds and keep it small, repeated wrong codes
  cost a growing wait and eventually a new code, and the number of connections
  is capped. A game that stops reading its socket no longer holds up the ride,
  one that freezes is noticed within seconds, and `setState` is limited to two a
  second. A connection naming a paired game can no longer take the ride before
  it has proved it is that game. None of this affects a game that behaves; the
  limits are in the Companion API documentation.

- **Take control is on the companion HUD,** next to the game's name and the
  address it connected from, and it lets go of everything the game was holding
  at once: the hill, a power target, an untracked stretch.

- **A workout link can no longer replace a workout silently.** Importing one that
  shares an id with a workout you have now asks whether to replace it or keep
  both, and a starter workout is never replaced. Importing one you already have,
  unchanged, does nothing. Link payloads and shared files are also held to a
  sensible size, and read in the background.

- **Connection credentials are encrypted on the phone.** The Intervals.icu token
  and API key and the FitTrackee secret and tokens are sealed with a key held in
  Android's keystore. Nothing to do: existing connections are sealed on the first
  start.

- **Android 12 and later no longer see a location permission.** It was only ever
  needed for Bluetooth scanning on Android 11 and earlier, and is now declared
  only there.

- **The ride screen does less work.** It redrew itself for most of every second
  to animate cadence and heart rate, and for a second at every interval change;
  now only the readouts that change redraw. It also stops listening for updates
  while the app is in the background.

- **The download is about 400 KB smaller.** R8's optimized resource shrinking is
  on, which removes the parts of AppCompat and Material this app never draws,
  and the two libraries CameraX brings with it are held to current versions
  instead of the 2019 ones it asks for. No behaviour changes.

## [2.7.1] (24) - 2026-09-22

### Fixed

- **The share sheet could put its own buttons off the screen.** The card
  preview is square, so on a phone it is as tall as the sheet is wide, and with
  the picture chooser under it the sheet wanted more height than the window had.
  A bottom sheet does not scroll what it is given, it clips it, so Save and
  Share went off the bottom on the one screen whose whole purpose is those two
  buttons. Everything above them scrolls now, they stay put, the preview is
  capped at about half the window, and a sheet tall enough to reach the top of
  the screen no longer starts behind the clock. The export sheet, which is the
  same kind of sheet, scrolls for the same reason.

## [2.7] (23) - 2026-09-22

### Added

- **A ride's own pictures can be the share card.** Sharing a ride that a
  companion took screenshots of now offers them: the row under the preview has
  the ride card as it was and a tile per picture, and the chosen one fills the
  panel where the chart or the road would be, with the ride's numbers still
  around it. More than three and the row keeps the first few and opens the rest
  as a grid. Rides without pictures, which is most of them, get exactly the
  sheet they had.

- **The gear is on the companion ride screen.** The paddles change it, so the
  screen they are on says what they changed it to: the gear number and the
  cassette ladder, above the power, in both orientations.

### Fixed

- **The app could close while a ride was being set up.** Android takes a
  foreground service started for a ride as a promise to show its notification
  within a few seconds, and kills the whole process when that promise is not
  kept. Two ways it was not: a ride ending as the next one began (every lap of a
  companion session) could drop the newer start, and the service was being asked
  to start again on every change of state, auto-pause included, each one a fresh
  promise and a fresh chance to miss one. The notification is now shown before
  the service reads what it was asked to do, the ride hands over from one lap to
  the next without stopping in between, and starting is asked for once, when
  riding begins.

- **Companion mode sent you home to pair a trainer.** Tapping Companion without
  a trainer opened the pairing screen, which closes itself the moment the
  trainer answers, leaving the rider back on the ride picker with nothing to
  show for the tap. Companion mode now opens whatever is paired: the screen asks
  for a trainer itself, with a button, and pairing returns to it with the
  address and the code already waiting.

- **Back could reach past the bottom of a tab.** The navigator decided it was at
  the root by looking at which screen was showing rather than how deep the stack
  was, which are the same question only while every stack still starts with its
  own tab. One that did not could be popped empty, and an empty stack has no
  screen to draw.

- **The share preview cut the card's edges.** The preview laid the card out at
  full size inside a sheet-width box, which centres what will not fit, so the
  card's margins and the first letter of its title sat off the left edge. The
  preview is the card again.

## [2.6] (22) - 2026-09-18

### Added

- **Share a picture from a ride.** The screenshot viewer has a share button, so
  a frame a companion sent during a ride can go to a chat, a photo roll or
  anywhere else the system share sheet reaches. One picture at a time, the one
  on screen, and only when the rider asks: nothing about a screenshot leaves the
  phone on its own, which is still true of exports, uploads and sync.

## [2.5] (21) - 2026-09-18

### Added

- **Onboarding runs.** The welcome card and the two-step sheet that asks for FTP
  and body weight existed, fully built, and nothing ever called them: a fresh
  install rode at the default 200 W and 75 kg, and since FTP is the denominator
  of every zone, target, TSS, IF, W/kg and XP figure, a new rider's numbers were
  all arithmetic on a value nobody had asked them for. The card now sits on the
  ride screen until the question is answered, and the sheet explains what
  Bluetooth and notifications are for. Dismissing counts as answering.

- **Two panes on a tablet.** Workouts and History put the list beside the thing
  it lists from 840 dp up: the library and the workout, the ride history and the
  ride's summary. The pane is the same screen a phone pushes, so Start, rename,
  delete and share are one implementation. The navigation rail also stays put
  when a detail screen opens on a wide window; the ride screen and the QR
  scanner still take the whole window.

### Changed

- **A companion ride shows the power, and the shift paddles, and nothing else.**
  The speed, the distance, the gradient, the gear and the climb were being drawn
  twice: once by the game on the screen in front of the rider, and again on the
  phone clamped to their bars. The phone now shows the one reading the game
  cannot, as large as the screen will hold it, with the W/kg under it and the
  paddles where they were. Nothing changes on the wire, and nothing changes in
  what is recorded or exported.

- **Everything is still there for whoever is building the companion.** Companion
  mode's own screen has **Show every reading while riding**, which puts the full
  set back on the ride screen. Off by default, remembered between rides.

- **The wattage is the brightest thing on the ride screen.** Four of the seven
  zone colours the number is drawn in measured between 2.7:1 and 3.0:1 against
  the canvas, under the 3:1 floor large text gets, and the worst was the zone a
  rider is in when they are suffering hardest. Each is lifted along its own hue
  to about 4:1. Cadence and heart rate step down one ink level, so size and
  colour now say the same thing about what matters.

- **Companion mode leads with the address and the code**, with the explanation
  of what companion mode is following them rather than pushing them below the
  fold.

### Fixed

- **Backing out of a running ride asks first.** Back stopped the workout and
  left; the ride survived only as a row on the unfinished-ride banner, which a
  rider who thought they had quit has no reason to look for. Back now shows the
  same dialog End & save uses. A ride with no time on it still leaves silently.

- **A free ride's bottom row was cropped on a tablet.** The stats strip is the
  last row in the column with no timeline under it to hold it off the edge, so
  its tiles were laid out flush and the screen cut their corners.

- **Leaving a companion ride left you in companion mode.** Backing out of the
  ride, or closing the panel after the session ended, returned to the pairing
  screen, which starts listening again the moment it appears. A permanently
  paired game reconnected by itself and drove the rider straight back into a
  ride, with no way off the screen from the phone's end. Both now return to the
  ride picker, with the socket closed behind them.

## [2.4] (20) - 2026-09-17

### Added

- **Connect Intervals.icu instead of pasting an API key.** Settings →
  Connections now has a Connect button that opens Intervals.icu in the browser,
  where the rider approves Indoor Bike to upload activities and nothing else.
  The app keeps the token Intervals.icu issues, uploads with it, and Disconnect
  revokes it on Intervals.icu as well as forgetting it. Intervals.icu asks apps
  used by more than one person to work this way. Its OAuth needs a client
  secret, and the source is public, so the secret is compiled into release
  builds from a gitignored file rather than kept in the repository; a build
  without it offers the API key as before. The approval comes back through a
  verified link to `indoorbike.app`, so no other app on the phone can be handed
  it. Connecting replaces an API key already saved. A build carrying the OAuth
  client does not offer the API key at all, since Intervals.icu asks multi-user
  apps to use OAuth; the fields stay for a rider who already saved one.

- **A companion can drive a stretch that is not recorded (protocol 0.10).** A
  new `untracked` ride state is active with the record switched off: the engine
  drives the trainer and telemetry streams, while the clock, the samples and the
  snapshot all stop, so pushing a bike up a climb the game will not let the
  rider ride is work that stays out of the activity. Distance reported while
  untracked is not banked, and cannot raise the kept maximum afterwards.
  `setPower` is honoured only in that state, held inside an envelope that decays
  to nothing if the game goes quiet and releases the brake below 20 rpm, so a
  rider who gives up is never met with more resistance. `stateChanged` and
  `telemetry.state` are now per connection: a 0.10 companion hears `untracked`
  and an older one hears `paused`.

### Fixed

- **A paused ride exported short, and one interrupted by a crash lost its
  tail.** Both the FIT and the TCX writer measured every sample against the
  ride's start plus its duration, so each paused second pushed a second of real
  riding off the end of the file, and neither file said a pause had happened.
  Samples now carry the moment they were taken on both clocks: FIT writes a
  timer stop and start around each pause as a head unit does and reports
  elapsed and rolling time separately, TCX leaves the pause as the gap between
  two trackpoints, and laps are cut on ride time.
- **An exported FIT claimed laps it did not contain.** A ride ended before its
  last segments wrote a lap count for the whole plan; it is now the laps
  actually in the file.

## [2.3] (19) - 2026-09-16

### Added

- **A companion can say what the road is made of, and the trainer pushes back
  with it.** The `world` frame takes a `crr`, the rolling resistance under the
  tyres, and the phone puts it into the force it commands: gravel, dirt and
  grass are now harder work than tarmac instead of only looking different.
  Absent means tarmac, as it did for every companion written before this; the
  value is clamped to a rideable range and warned about when it is clamped, and
  it ramps back to tarmac with the deadman so a companion that vanishes
  mid-sector leaves nobody grinding through virtual gravel. `crr` moves from
  the reserved names into `welcome.honours` and its bounds into
  `welcome.limits`; drag and wind stay the companion's own arithmetic, because
  a companion that owns speed can already deliver those and cannot deliver
  this one.

- **Screenshots from the companion, kept with the ride (protocol 0.9).** A
  companion that says it can take them gets a camera button on the ride
  header. A tap asks the game for a picture of its screen, and the picture it
  sends back is kept on the phone with the ride that was underway, even if that
  ride has ended by the time it arrives. The ride's summary and its page in
  History show them as a row of thumbnails that open full screen, and the
  session panel shows them under each ride. The header says when one is saved,
  when the game could not take one, and when none came. Pictures are checked
  from their header before anything decodes them, stay on the phone, and are
  deleted with their ride.
- **A companion can bring its own look to the phone (protocol 0.8).** A new
  `brand` frame carries a name, a square PNG icon and three colours, and the
  ride header takes them on: the companion's header colour behind the whole
  header, reaching the top edge, its icon or a monogram, its name with the
  course and world underneath, and a line of its accent along the bottom. The
  shift paddles and the remote pad flash in the accent while pressed, and the
  disconnected panel keeps the icon. Zone colours, warnings, the app's own
  navigation and the pairing dialog are never styled. Colours the app cannot
  draw legibly on its dark screen are adjusted rather than refused, and an
  `adjusted` warning names the colour actually used. Fields merge, `null` clears
  one, and nothing is stored.

### Fixed

- **A companion's next ride renamed the last one, and wiped its map.** A game
  that names each ride before starting it sent the new ride's `course` between
  rides, and the app took it for a late course for the ride just saved: that
  ride was renamed after the next one and its map was replaced with none. A
  course that names a different ride is now the next ride's, and a course with
  no shape in it never clears a stored map.
- **A companion ride's summary showed an empty "Power adherence" card.** A ride
  a game drove had no plan to adhere to, so the card is gone for companion rides,
  as it already was for free rides and route rides.
- **Two companion rides were saved as one.** A game that loads its routes from
  JSON sent the ride's elevations as `921.0` rather than `921`, and the app
  refused that `finish` as unreadable: it closed the connection, the ride never
  ended, and the game reconnected and started the next ride on top of it. One
  evening's Mittenwald loop and Weilheim loop were saved as a single ride under
  the second name with both rides' time. Whole numbers are now read however they
  are written, elevations with a fraction are rounded, and a `finish` always
  ends the ride: a summary it cannot read is left out with a warning naming the
  field. A `course` with an unreadable field keeps the rest in the same way.
- **A companion the app disconnected left no trace.** When the app closed a
  game's connection over a frame it could not read, nothing was logged and the
  ride carried on as if the game were still there. It is now logged with the
  reason, and a ride in progress pauses and waits for the game, exactly as when
  the game drops. Refused fields are logged too.
- **A companion ride was lost when the game went away before finishing.** The
  game closed mid-ride, the rider pressed back, and five minutes of riding were
  discarded with nothing on screen to say so. The ride now pauses and waits,
  and ending it keeps it.
- **An interrupted companion ride could never be recovered.** The unfinished-ride
  banner could not resolve a companion ride and so never appeared for one, and
  a companion ride continued from it would not have saved when ended. The banner
  now offers it as a ride to save, never to resume, since the game that drove it
  is gone.
- **Skip broke a companion ride.** A companion ride is one synthetic segment the
  game drives, so skipping it rolled straight to the end and completed the ride
  from under the companion. The button is gone from Companion mode and the
  session refuses the skip as well. The clock also stopped claiming a
  "/ 6:00:00" total nobody set.
- **Companion rides were not being saved.** A ride was written to history when
  the rider tapped through to the summary, which is how every other mode works
  and is a tap a companion rider never makes: the game ends the ride, they are
  on the bike, and the next lap starts. An hour of riding could exist only as a
  trophy nobody pressed. A companion ride is now written the moment the game
  says it is over, by the host rather than by the screen.

### Changed

- **A companion that drops mid-ride pauses the ride and waits for it.** A game
  that disconnects without sending `finish` has stopped driving the ride, not
  ended it, so the ride pauses at once (trainer off, nothing recorded, and not
  the kind of pause pedalling undoes) and a panel over the HUD says "Ride
  paused" with the rides this session. There is no timeout. When the same game
  reconnects the ride carries on by itself; a ride the game had paused itself
  stays paused. If it is not coming back, the rider taps End ride and it is
  saved as an ordinary completed ride, and the panel becomes the session's
  summary. This replaces the separate end-of-session screen, so the ending is
  shown where the rider is already looking.
- **Shifting is the biggest thing on the companion HUD.** In Companion mode the
  game owns the road and the rider owns the gears, and a rider without a
  handlebar controller had two 46 dp circles in the middle of a card to do it
  with. Landscape now puts full-height Easier and Harder paddles on the two
  outer edges, where a thumb resting on the bars finds one without aiming;
  portrait splits the bottom of the screen between them. They shift on touch
  rather than on release, because a thumb that lands and skids on a bouncing bike
  cancels an ordinary click, and they dim rather than vanish at the end of the
  range.
- **A landscape phone keeps its power number.** Between the two paddles there was
  no height left for it, and it collapsed to nothing. That shape now gets its own
  two-row arrangement and drops speed, distance and climbing, which the game is
  already showing on the big screen.
- **The ride header shares its space instead of fighting over it.** The status
  line could push the ride's name down to its first letter, an upright tablet
  missed the two-row header meant for portrait, and the controls stopped short
  of the right edge. All three affected every ride mode, not only Companion.
- **A companion session is many rides (protocol 0.7).** `finish` ends a ride and
  leaves the socket open, so the game can start another whenever it likes. The
  app no longer shows a summary between rides, because the game is still running
  and the rider is still on the bike; the strip at the top of the HUD names the
  ride that was saved and keeps a count. `4005` now means the rider left
  Companion mode, which is the only thing that ends a session.
- **Each ride in a session measures its own distance.** `distanceM` is
  cumulative and a game may count straight through an evening, so the app takes
  the first report after each start as that ride's origin. A counter that runs
  all session and one that restarts per ride both record correctly.
- **A course arriving after the `finish` completes the ride already written.** A
  free-roaming game only knows its polyline once the rider has stopped drawing
  it, and the map should not be lost to a frame that was late by design.

### Notes

- Companion protocol 0.9, accepting everything back to 0.1. The privacy policy
  now covers screenshots, which stay on the phone.

## [2.2] (18) - 2026-09-11

### Added

- **Companion mode.** A game or app on another device on the same network
  drives the road; this app turns it into resistance and records the ride. No
  account and no server in the middle. Labelled **Alpha**: it needs a second
  program running elsewhere, and the protocol under it is still moving. The
  protocol, the close codes and a worked example are in
  [docs/COMPANION_API.md](docs/COMPANION_API.md) and on the website.
- **Permanent pairing.** The pairing code is per ride, which is the right
  ceremony for a stranger and the wrong one for a machine in the same room.
  Agreeing once on both devices leaves a shared key, and a mutual
  challenge-response over it means a paired companion connects with no code
  even though the address and port change every ride. The companion screen
  lists what is paired and forgets one immediately on request. Authentication
  only: the socket is still plaintext, and that limit is written down rather
  than left to be inferred.
- **Remote controls.** While a game shows a menu the app draws a pad of up,
  down, left, right, enter and back. Controls the game does not list are drawn
  visibly dead rather than hidden, so a rider never presses something that
  looks live and gets nothing.
- **A companion can set the rider's drivetrain**, for a game with an opinion
  about the bike. Optional, not persisted, and refused whole rather than
  repaired when the teeth described do not exist.

### Changed

- **Speed follows cadence through the gear.** The kinematic coupling a chain
  provides was missing, so downshifting barely registered. Shifts now change
  what the pedals ask for immediately, and the gap between gears grows with
  speed as it does outdoors. Shared by Route mode and Companion mode.
- **Acceleration is part of the road load**, so the bike has mass: accelerating
  costs something and easing off carries the rider.
- **Launch assist.** Off the line almost none of the resistance is air, leaving
  gravity, rolling and inertia: heavy pedals and nothing moving. The app
  commands half the real force and fades to full physics over ten seconds once
  past 5 km/h, smoothstepped so neither end of the hand-off is a corner. It
  scales the whole force, so a hill start is helped too, and re-arms below
  1 km/h so a rider climbing at 4 km/h is never handed it back.
- **The companion owns the start, and there is no countdown.** Telemetry and
  the road flow from the moment it connects; the clock waits for
  `setState: "active"`. Two countdowns fighting each other is worse than
  either, and the app cannot see the one that matters.
- **A finished companion ride returns to the companion screen** rather than the
  home tab, since the game on the other device is still running.

### Fixed

- **A second companion ride could not be started.** The session sits on
  `Completed` until the next ride is prepared, so the ride watch started by the
  pairing screen saw the previous ride's ending and closed the socket a moment
  after opening it. An ending is now a transition into `Completed`, pinned by
  `RideEndWatch` and its tests.
- **Pairing keys were not excluded from Android Auto Backup** or phone-to-phone
  transfer, so a key could have left the device it was agreed on. Excluded from
  both, and never written to a Backup & sync slice.
- **A ride resumed from the unfinished-ride banner saved without its road**, so
  the map and elevation chart it had been recording were lost.

### Notes

- Copyright transferred to **Snapp Technology Ltd** across every notice. The
  licence is unchanged and the signing key is unchanged, so updates are
  ordinary. `AUTHORS.md` records who wrote it.
- The privacy policy now covers Companion mode, including that the connection
  is not encrypted.
- `scripts/companion_probe.py` is a companion in one file, standard library
  only, for testing the protocol against a real phone.


## [2.1] (17) - 2026-09-09

### Changed

- **A tablet gets a tablet's layout.** Until now only the two ride HUDs
  adapted to a large screen; every other screen drew its phone layout at full
  width, with route cards nearly two thousand points wide, paragraphs running
  the width of the glass, and dialogs sized at 85% of a tablet. Eleven screens
  now hold their content to a readable column, left aligned with the title
  above it, and six dialogs are capped.
- **Side by side requires the screen to be wider than it is tall.** A tablet
  held upright gets the stacked layout instead of a map squeezed beside a
  column of empty space, and the simulation HUD spreads its slack rather than
  pouring all of it into the power number.
- The margin a narrower column leaves over carries the app's own horizon:
  three slate planes, a crest line and one contour, the same motif as the ride
  backdrop and much quieter. Not animated, not coloured, the same hill every
  time, and not painted at all until the margin is wide enough to hold it.

### Fixed

- **A continued route ride reported the whole road rather than the part
  ridden.** Resuming at 42 km and riding 6 more produced a summary claiming
  48 km, because the stored figure is a position along the route and the
  post-ride screen read it as a distance covered.

### Notes

- First release published to Google Play. CodeFloe remains the home of the
  code and ObtainX the way to install without a Google account.


## [2.0.2] (16) - 2026-09-07

### Changed

- **The app answers touch physically.** A press, a chip, a card, a pairing row:
  all of them now move on a spring rather than a fixed-duration curve, because
  a finger expects a physical answer and a tween has none. Headline numbers
  count up to what they are instead of appearing at it, lists reveal in
  sequence rather than all at once, and the actions that confirm something
  give a haptic tick. Reduced motion is honoured throughout: turn it on and
  everything simply arrives.
- The website moves the same way, on the same curve, and stops if you have
  asked your browser to reduce motion.

### Fixed

- **The routes list on a repository's community page laid out on top of
  itself.** Every route drew its shape at the full width of the card and
  pushed the name, place and figures into a column a few pixels wide, which
  then spilled outside the card. A repository publishing eighteen routes made
  a page of it.

## [2.0.1] (15) - 2026-09-07

### Changed

- **A new mark.** The same ridge and frame, recoloured to Slate and drawn at one
  stroke weight, with the rear wheel replaced by the flywheel of a direct-drive
  trainer - which is what the app actually talks to. It replaces the orange mark
  everywhere: the launcher icon and its themed and debug variants, the
  notification icon, the site's header, favicon and link badges, and the
  wordmark on a shared ride card.
- **The whole app was redrawn in a new design system, Slate.** Five slate-blue
  surfaces, three cool whites, one typeface, and no accent colour at all: the
  power zones are now the only saturated colour anywhere, so a colour on screen
  always means an intensity. The ember orange is gone, and with it the tinted
  icon wells, the gradient fills and the outlines around cards - a card is a
  surface one step lighter than the canvas and nothing else. Every label is
  sentence case. Bebas Neue, Inter and JetBrains Mono are replaced by Manrope,
  bundled as before, with tabular figures so a ticking number does not shuffle
  the digits beside it.
- The tab bar is a floating pill above the canvas, and Progress is a trend line
  rather than a trophy.
- In a ride, the power number is now the biggest thing on the screen by a long
  way and carries the zone colour, so intensity reads from across a room. The
  timeline under it is one bar per step: ridden steps at full strength, what is
  still to come at half. The horizon backdrop stayed, in slate, with only its
  crest line tinted by zone.
- The three pairing screens share one status card, put their actions at the
  bottom edge and scroll their content, so nothing falls off a landscape screen.
  A scan you started can now be stopped from the screen that started it.
- **Screen titles collapse as you scroll.** A 28 sp title over a section label
  is the right thing to land on and the wrong thing to keep: on the workout and
  route lists it was costing a row and a half of list the whole time you were
  reading one. It now folds into the row the back button already occupies, and
  comes back when you scroll to the top.
- **The tab bar sits at the bottom edge again, and the list runs underneath it.**
  It was adding the design's 20 dp gap on top of the system's own 24 dp one and
  floating 47 dp up, and it was boxed off above the content, which left a strip
  of dead canvas below every screen. It now floats over the content the way a
  pill is meant to: the list scrolls under it and fades into the canvas as it
  goes, and the two bottom gaps are treated as the one gap they are.
- **Screens slide rather than cross-fade.** Going deeper pushes the new screen
  in from the trailing edge and coming back sends it out the way it came, so the
  animation says which direction you went; a back gesture drags the screen off
  the edge your finger came from. Switching tabs still cross-fades, because that
  is a move sideways and not a move deeper.
- Fixed the keyboard covering text fields. Drawing edge to edge means the window
  no longer shrinks when the keyboard opens, so Settings, Connections, the
  onboarding sheet, the add-by-URL sheet and the rename dialog now make room for
  it themselves.
- Fixed the last row of the workout, route and repository lists sitting under the
  navigation bar, and the system's translucent scrim showing as a band across the
  bottom on three-button navigation.
- **The home screen asks what you are riding, instead of listing workouts.**
  Workout, Route, or Just ride; you pick the kind first and the specific one on
  the screen after. A list of workouts was the right home screen when a workout
  was the only thing a ride could be, and stopped being right the moment a ride
  could also be a road. Searching and filtering the library did not go away, it
  moved to where choosing a workout actually happens.
- Scanning a QR code from the web editor moved from the home screen to the
  workout picker. It carries a workout out of the browser, so it belongs where
  workouts are chosen rather than sitting above all three kinds of ride.
- **Up next is gone**, along with the quick-pick shelf. Both existed to make a
  long list usable as a landing screen, and there is no longer a list to land on.
- The home screen now shows the trainer, heart rate monitor and controller
  together, because the moment before a ride is exactly when it matters whether
  a strap is connected but sending nothing.
- The first-ride suggestion and the unfinished-ride banner can both be waved
  away. Dismissing an unfinished ride hides the card and keeps the ride:
  abandoning it is still a separate, deliberate button.
- **The gradient reading is drawn on the road around you.** A fixed 50 m behind
  and 500 m ahead, whatever the route's length. The whole-route profile cannot
  answer how much further this climb goes or where the next one starts, because
  on a 169 km stage a two kilometre climb is a pixel wide.
- **A gear shift bites harder.** Nothing was broken - a shift reaches the
  trainer inside 250 ms - but the model was exactly faithful to a bike, where
  one sprocket is a 5 to 8 % change in force. On the road that is plainly felt
  because your cadence drops with it; indoors, where virtual speed comes from
  power alone, a rider absorbs it by pushing 5 % harder at the same cadence and
  never notices. Each shift now reaches further, while the gear matching your
  real one still carries the road's own force unchanged.
- The route list is marked Beta on the home screen. Routes are the newest part
  of the app and the part with the most still to settle.
- The progress bar under the elevation profile is gone. It said exactly what
  the profile above it says, without terrain to read it against.
- **A repository's workouts and its routes are one stop now.** A repository can
  carry both, and reaching the other half meant going back to the library and
  coming in through a different button. There is a Workouts / Routes switch at
  the top of the browser whenever the repository has the other kind, and back
  still leaves the repository however many times you crossed. It reads the
  repository's own manifest rather than the registry, so it works for a
  repository you opened by URL or QR - which is also the only way out of a
  routes-only repository that used to greet you with "no workouts" and nothing
  else.

### Added

- **Follow a community repository and the app tells you when it publishes
  something new.** Following is about being told, not being given: nothing
  reaches your library until you open the repository and add it. Checks happen
  when you open the home screen, at most every few hours, and never while the
  app is closed. A check reads the manifest and only downloads a bundle whose
  version has actually moved, so asking a 400-workout repository whether
  anything changed usually costs one small file.
- A Repositories screen to follow, unfollow and check them, taking either a
  repository from the community registry or any manifest URL.

### Fixed

- **Cadence and heart rate were cut off on a landscape phone.** The simulation
  HUD stacked fixed type sizes and ran past the bottom of the screen, and the
  two readings that fell off were the ones a rider cannot reconstruct from
  anything else on it.
- **A long route would download and then never finish.** The spinner stayed up
  for good on anything much past a few hundred kilobytes of GPX: a 42 KB route
  parsed in milliseconds and a 531 KB one took minutes, because the parser
  looked for a namespace prefix by scanning to the end of the document and only
  bounded the result afterwards. A GPX with no namespace prefixes - which is
  most of them - has no colon to stop that search, so the cost grew with the
  square of the file. The search is bounded to the tag it is reading now.
- The community registry is refetched when you open the repositories screen.
  It used to be fetched only when no cached copy existed at all, so a
  repository added to the registry after your first look never appeared, and
  nothing on screen suggested anything was missing.


- **Community routes.** Repositories can now publish roads as well as intervals.
  A route listing carries everything a picker needs - distance, climbing, a
  gradient histogram, a small elevation profile and the shape of the road - and
  points at its GPX rather than containing it, so browsing fifty routes costs
  one small file and the geometry is downloaded once, for the one route you add.
  Added routes join the bundled ones in a new route selector reached from the
  rides list, and each keeps showing the credit its data licence asks for.
- **Estimated ride time per route, for you.** Every route card says how long it
  will take at *your* FTP and weight, worked out from the gradient histogram
  rather than from distance, because 500 m of climbing taken as one steady drag
  and the same 500 m taken as twenty ramps are not the same evening. The
  physics is the physics the ride itself uses, so the estimate and the road
  agree.
- **A route publishing tool on the website.** Drop a GPX at
  [indoorbike.app/routetool](https://indoorbike.app/routetool/) and it hands
  back the JSON to paste into a repository, plus your manifest with the pointer
  already merged in. Nothing is uploaded; the file is read in the browser. It
  warns about the thing that quietly ruins a route - a GPX with no elevation,
  which would otherwise simulate dead flat - and can fill that in from open
  elevation data if you ask it to.
- A repository already listed in the registry can start publishing routes by
  editing its own files. The manifest format did not change version to make
  this safe: adding routes cannot break the repository for anyone still on an
  older build of the app.
- **Ride a long road across several sittings.** A published route is as long as
  the road it came from, and a Tour stage is eight hours. Stop part-way and the
  app remembers where you got to; the next time you pick that route it asks
  whether to carry on from there or start again, and the card in your route list
  says how far along you are. Climbing carries over too, so a route ridden in
  pieces still totals the whole climb. Finishing a road clears the mark, because
  picking it again then means you want to ride it.
- **Long press a route to remove it from your device.** Downloaded routes are
  files, and eighteen stages of a Grand Tour is a lot of files. Removing one
  takes its saved position with it and leaves your rides in your history; you
  can add it again from the repository it came from. Bundled routes say why they
  cannot be removed rather than ignoring the gesture.
- **The route tool builds the whole file, not one entry.** Give
  [the tool](https://indoorbike.app/routetool/) the `routes.json` you already
  publish and it adds to that, so you can put up a route at a time and download
  a file with the earlier ones still in it - the step where a route used to get
  lost. It can also start an empty one, and it will show you the listing as the
  app draws it, cards and all, before you publish anything. Routes the app would
  quietly skip are named there rather than going missing in front of riders.

## [1.8.2] (13) - 2026-09-05

### Fixed

- **A Zwift Click's buttons now work.** Zwift's controllers come in two protocol
  families and the app only ever spoke the older one. A Click from the encrypted
  generation pairs, reports itself connected, accepts every byte written to it
  and answers none of them, so every button press went nowhere. The app now
  performs the key exchange those units expect: an ephemeral P-256 handshake, a
  session key derived with HKDF-SHA256, and AES-CCM on every frame after it.
  Controllers speaking the older plaintext protocol are unaffected, and which
  one is in your hand is settled by what it answers rather than guessed from its
  name.

  This is the fix that 1.7 announced and did not make. That release corrected
  the order of the handshake and the subscriptions, which was a genuine bug, but
  the reason a Click stayed silent was that it never understood the handshake at
  all. The claim in 1.7's notes was wrong; this entry supersedes it.

- **A controller that cannot work no longer says it is working.** Settings read
  "Connected · in-ride ± control" for anything that paired, whether or not its
  buttons could ever do anything. It now reads "Connected but not answering"
  once the handshake has run out of attempts, in the same red the heart rate
  monitor uses when it connects and then sends nothing. Not being able to tell
  those two states apart is why this went unnoticed through two releases.

- **Waking a controller no longer changes your target.** A sleeping Click is
  woken by pressing a button, and the first frame of the new session reports
  that button as still held. That was read as a press, so reconnecting nudged
  your wattage in whichever direction you happened to reach for. The frame that
  opens a session is now taken as a starting position rather than a change.

- **No more phantom presses the moment a controller connects.** The controller
  starts streaming on its data channel a few milliseconds before its handshake
  reply arrives, and those first frames were reaching the keypad parser before
  there was a session to decrypt them with. Random ciphertext reads as a random
  button state about one frame in 256, which showed up as a + and a - landing in
  the same millisecond. Nothing is parsed now until the app knows which protocol
  the controller speaks.

## [1.8.1] (12) - 2026-09-05

### Fixed

- **Typing a new FTP, weight or maximum heart rate now replaces the old one.**
  Tapping one of those fields put the cursor inside the number rather than
  selecting it, so typing 250 into a field showing 265 produced 2250. That is
  over the allowed limit, so it was thrown away and the old value came back,
  with nothing on screen to say why. It looked exactly like the setting refusing
  to save, and it was luck whether you hit it: clear the field first and it
  worked, type straight over the top and it did not. The value is now selected
  when you tap the field, so typing replaces it. Reported by @ghose, who spotted
  that weight saved while FTP and heart rate did not.

- **FitTrackee no longer needs a sport list it usually cannot read.** Reading the
  list requires a permission FitTrackee's own app-creation screen does not
  reliably offer, so connecting could leave the app reporting that rides could
  not be filed. Rides are now filed as **Cycling (Virtual)**, which is what an
  indoor ride is; those sport numbers come from FitTrackee's own migrations and
  are the same on every server. If your server does share its list you can still
  pick something else. Also reported by @ghose, who worked out the cause and the
  right sport.

## [1.8] (11) - 2026-09-03

### Added

- **Upload rides to your own FitTrackee server.** FitTrackee is self-hosted, so
  unlike Intervals.icu there is no service to sign up to: you point the app at
  the address of a server you run. Pick which sport rides are filed under from
  the list your own instance offers, since those ids differ between servers.
  Upload by hand from the ride summary or automatically when a ride is saved,
  exactly like Intervals.icu, and the summary shows which services a ride has
  already gone to.

  Worth knowing before you set it up: FitTrackee is built for outdoor
  activities and its file import only accepts files containing GPS
  coordinates, which an indoor ride has none of. Rides therefore arrive as an
  entry carrying the date, duration and estimated distance, with the power,
  cadence, heart rate, TSS and kJ written into the notes. There is no power
  graph, and the app says so rather than implying otherwise. The app still
  offers the file first every time, so the day FitTrackee accepts a GPS-less
  file, rides start arriving in full without an update.

  **You approve it in your browser and your password never reaches the app.**
  Sign-in uses OAuth2 with PKCE against your own server, so what the app ends up
  holding is a token that can only upload workouts and read your profile, and
  that you can revoke from FitTrackee without changing anything else. It renews
  itself in the background, so there is no session to keep re-establishing.
  FitTrackee won't let an app register itself, so you create one in Profile →
  Apps and give the app its client id and secret; the page shows you the exact
  redirect URI to paste, with a copy button.

- **A Connections page in Settings.** Intervals.icu and FitTrackee now live
  together on their own page instead of stretching Settings out. Settings shows a
  single row saying what is connected.

### Fixed

- **Settings now save whatever way you leave the screen.** FTP, weight, max
  heart rate and the device name were only written when the field lost focus or
  you pressed the keyboard's done key. Leaving Settings any other way - switching
  tabs with the keyboard still up, or the system reclaiming the app mid-edit -
  discarded the change without a word, and the old value came back on the next
  launch. Each field now saves as soon as what you have typed is a usable value,
  so there is no longer a right way to exit. Verified by typing a new FTP and
  killing the app outright without touching anything else: the value was still
  there on restart.

- **The Intervals.icu auto-upload switch can always be turned off.** It was
  greyed out unless both an athlete id and an API key were present, so anyone
  who turned auto-upload on and later cleared their credentials was left looking
  at a switch that said off, refused to move, and could not be turned off. It is
  now always operable and shows the setting's real state.

- **The share card is no longer squeezed into a corner.** The card is designed
  at 1080x1080, but it was being laid out inside the share sheet's width instead,
  so its labels wrapped mid-word and the preview showed the card shrunk into the
  top-left of its frame. The image the card produced was never the size the rest
  of the sharing code assumed either.

### Changed

- **A ride you have already sent to Intervals.icu now says so.** The upload card
  showed the same "Upload" button whether or not the ride had been sent, so the
  only way to tell was to read the line above it, and even that only described
  whether auto-upload was switched on. A ride that has been accepted now carries
  a green tick and the date it went, and the button reads "Re-upload" so the
  state is legible without reading anything. The fact lives on the ride itself
  rather than in the screen, so it survives navigating away and restarting the
  app, and auto-upload will no longer re-send a ride the record already says
  went up.

## [1.7] (10) - 2026-09-02

### Added

- **Speed and distance, estimated from power.** A trainer has no road under it,
  so there is nothing to measure - what a head unit shows instead is the speed
  the rider's watts would carry them at outdoors, and that is what the app now
  shows too. Given power and the mass being moved, the flat-road power equation
  has exactly one answer for speed; the ride HUD shows it live alongside the
  running distance, the summary adds distance and average speed, and both are
  written into exported FIT and TCX files, so a ride uploaded to Strava,
  Garmin Connect or intervals.icu arrives with a distance instead of a zero.
  Distance integrates the speed second by second rather than deriving one
  number from average power, because speed goes as roughly the cube root of
  watts: an hour of surges covers less ground than an hour held steady at the
  same average, and this reports the difference. The road is a modelled one
  (flat, still air, road tyres on tarmac, a rider on the hoods) so the only
  thing the rider supplies is their weight from Settings; without it there is
  no mass to move, and the readouts stay away rather than showing zeroes.

### Fixed

- **A Zwift Click that connects and then does nothing now works.**
  *Correction: it did not. The timing bug described below was real and was
  fixed, but it was not what kept the Click silent, and this release did not
  make one work. See Unreleased.* The
  handshake that opens the controller's pipe and the subscriptions that listen
  to its buttons were started as independent coroutines that raced each other.
  Losing that race left the Click talking into a channel the phone had not
  switched on yet: it paired, reported itself connected, and every button press
  went nowhere, with no error on either side because from each one's point of
  view nothing had failed. The subscriptions are now established first and the
  handshake waits for them before writing, retrying a few rounds for a
  controller that isn't ready at once. A connect also logs the controller's
  GATT profile and what its Device Information service says about itself, so
  the next report of a Click that stays silent says which of the several
  devices sold under that name it is. The serial number is deliberately not
  read: it identifies the unit, and these lines can leave the phone in a bug
  report.

- **Tapping "View Summary" after a ride no longer closes the app.** On every
  Android version below 15 the tap killed the process outright. The ride
  itself was already saved by then, so it was waiting in History when the app
  was reopened, but the summary it was meant to open never appeared. The cause
  was one call in the navigation code that Java 21 and Android 15 added to a
  standard interface and older Android does not have: reaching for it on
  Android 14 or earlier is an immediate crash. The same screen opened from
  History always worked, because that path never made the call.

- **Heart rate now lands in the heart rate fields of exported FIT files.** Four
  fields were numbered wrongly, so a ride opened in an analysis platform showed
  its average and maximum heart rate as speeds, listed a lap's index as its
  sport, and filed the rider's FTP as total work. Re-exporting an affected ride
  produces a correct file; rides already uploaded need to be re-uploaded to be
  fixed at the far end.

## [1.6] (9) - 2026-08-25

A ride you don't have to plan, and a run of fixes for sensors that
connect but say nothing.

### Added

- **Just ride.** A ride you don't have to plan. The Rides tab has a **Just
  ride** card that starts a session with no workout behind it: the trainer
  opens at 40 % of your FTP, the timer counts up, and the ride runs until you
  end it. The HUD's ± buttons move the target ten watts at a time instead of
  nudging an interval's difficulty, so you set the effort as you go and change
  it whenever you feel like it - the pill above them shows the wattage you've
  chosen even before you start pedalling, and a paired Zwift Click steps it the
  same way. There's nothing to skip ahead to, so the interval label, the
  up-next line and the timeline are simply gone. Everything else works exactly
  as it does on a structured ride: the ride is recorded, survives the app being
  killed and offers to resume on the wattage you were holding, saves to your
  history, and exports to FIT or TCX (as one lap, since there was no plan to
  break it into).

- **Interval targets in exported FIT files.** A lap in an exported ride now
  carries what the interval asked for, not just what you did: target power in
  watts, the same target as a percentage of FTP, and the cadence range where
  the interval sets one. Analysis platforms that read FIT custom fields will
  show these alongside your actuals, so a structured session no longer exports
  as an anonymous power trace. Rides with no workout behind them, and intervals
  with no cadence ask, mark the target as absent rather than reporting zero.

### Fixed

- **Riding with a sensor switched off no longer grows the app's memory for the
  whole session.** Searching for a paired-but-absent heart rate strap or
  controller means scanning in windows for as long as the ride lasts, and every
  Bluetooth device that answered was being kept in memory afterwards. Neither
  search can filter by service - a Garmin strap doesn't advertise one, and a
  Zwift Click sometimes omits its own - so what was kept was every radio in
  range, not just the sensors, and since Bluetooth addresses are rotated for
  privacy the same neighbouring phone came back as a new one every few minutes.
  Nothing ever read any of it. A search now keeps only the devices it actually
  offers you, and starts each window from a clean slate, so an hour of looking
  costs the same as a minute of it. The trainer search, which can filter and so
  grew far more slowly, had the same missing cleanup and gets it too.

- **A heart rate monitor stuck on "waiting for first sample" now recovers, or
  tells you what to do.** The strap connected, the app said so, and no reading
  ever appeared - for every strap tried, across app restarts, until the phone
  was rebooted. The cause is on the phone rather than the strap: when the
  Bluetooth stack declines to switch a sensor's notifications on, that refusal
  was being swallowed one layer below the app, so the app had a connection it
  believed was healthy and a data stream that would never carry anything.
  Nothing recovered because, as far as either side was concerned, nothing had
  failed. The app now watches for the first reading after connecting, rebuilds
  the connection from scratch if none arrives within fifteen seconds, and after
  three silent attempts stops retrying and says the strap is connected but
  sending nothing, with the fix that actually works: turn Bluetooth off and on,
  or restart the phone. Closing the app never helped, because the stuck state
  lives in Android's Bluetooth service and not in the app.

- **Turning Bluetooth back on now reconnects straight away.** Sensors used to
  take up to a minute to come back, because every failed attempt while
  Bluetooth was off pushed the next retry further out, and the app kept
  counting that against the sensor once the radio returned. Since the advice
  for a stuck heart rate monitor is now to restart Bluetooth, a minute of
  nothing happening looked like the advice had not worked. The app waits for
  the radio instead of retrying into it, and starts looking the moment it comes
  back: measured on a Fairphone 4, reconnecting went from about 45 seconds to
  under a tenth of a second. Nothing is attempted and nothing fills the
  diagnostic log while Bluetooth is off, and a report drafted afterwards says
  why the app was waiting, which on older phones also covers location services
  being switched off (below Android 12 a sensor search silently finds nothing
  without them).

- **Bug reports about sensors are no longer blank.** The diagnostic log carried
  nothing at all from the Bluetooth library, which is how a report about a
  silent heart rate monitor could arrive without one line about the heart rate
  monitor in it. The library's own account of connecting, discovering services,
  switching notifications on, and quietly giving up on them now goes into the
  log with the rest. Per-reading traffic is left out so a long ride cannot push
  everything else out of the buffer, and sensor readings themselves are never
  recorded - only that one arrived. Device addresses stay masked.

- **"Auto-reconnect scan failed" no longer leads every bug report.** Two
  routine events, a scan window ending and scanning pausing when the app goes
  to the background, were being written down as failures. That put an alarming
  line at the top of reports about unrelated problems, one that had reliably
  sent people looking in the wrong place. Real scan failures are still
  reported.

- **A dropped heart rate connection could leave its reader running.** The
  subscription to the strap was started on one thread and recorded on another,
  so a disconnect arriving in between tidied up the wrong one and left the old
  reader alive. Same fix the trainer received earlier.

- **Bug reports sent after a crash are no longer empty.** The diagnostic log
  only ever lived in memory, so it died with the app: a report drafted after a
  crash could only show the launch that came after it, which is why reports
  arrived containing nothing but `App start`. The log is now also written to
  private storage as you ride, and a report includes the previous run's log
  alongside the current one. A crash costs at most the last five seconds of
  lines.

- **Reports now say why the app stopped.** Android keeps its own record of how
  each process ended, and the app never read it. A report now includes the last
  three, which distinguishes an app crash from the system reclaiming memory
  from a rider force-stopping the app - none of which the app's own logs can
  tell apart. Where Android captured a stack trace, that comes along too.
  Requires Android 11 or newer; below that the section says so.

- **A crash after a crash.** If the app was killed mid-ride, Android restarted
  its ride service in the background, and the app's attempt to stand that
  service down was refused - which threw, and killed the freshly started
  process. One kill could become a run of them. Both the start and stop paths
  now handle the refusal instead of dying on it.

## [1.5] (8) - 2026-07-28

Cadence targets for intervals, and two fixes for rides that could be lost
before they reached your history.

### Added

- **Cadence targets on intervals.** An interval can now ask for a cadence as
  well as a power - a single value or a range - so a workout can call for
  grinding a climb at 70-80 rpm or spinning leg speed at 105. The trainer holds
  the power in ERG mode exactly as before; the target only tells you how to
  spin while it does, and it is entirely optional. Set one per interval in the
  **workout editor** (tick CADENCE on a selected block, then give it a min and,
  if you want a range, a max).

  While riding, the CADENCE card shows your target with an arrow for which way
  to move - **▲** to spin up, **▼** to ease off, **✓** when you're there. The
  number turns green on target and amber off it, and a small bar under it shows
  how far off you are, because being 2 rpm under and 30 rpm under call for
  different responses. If your trainer isn't reporting cadence, the target is
  shown without a verdict rather than nagging you about a miss it can't see.

  Workouts carrying a cadence target are format v2. Ones without stay v1, so
  they still load in older versions of the app. Third-party links can set
  targets too, via a new `r` suffix in the editor's URL API
  (`45m@78r70-80`) - see [docs/EDITOR_URL_API.md](docs/EDITOR_URL_API.md).

### Fixed

- **Resuming a ride after a crash no longer throws away everything you rode
  before it.** The app remembered *where* you were in the workout but not what
  you had done to get there, so a session interrupted an hour in and resumed was
  saved with the full duration against a power curve covering only the minutes
  after the resume - and the exported `.fit` was that short too. Samples are now
  streamed to an append-only log as you ride and replayed into the session when
  you tap Continue, so a crash costs at most the last few seconds instead of the
  whole ride.
- **A finished ride can no longer be lost before it reaches your history.**
  Between the trophy screen appearing and you tapping View Summary, the ride
  existed only in memory - so anything that killed the app in that window took
  it with it, as did simply backing out of the completion screen. The ride is
  now held on disk until the save actually lands, and if it never did you get a
  **Ride not saved** banner offering to put it in your history.

## [1.4] (7) - 2026-07-06

A UI polish release. The Progress, History, and Settings screens are reworked
and the ride screen gains a few quiet celebration cues. Nothing changes what a
ride is worth or how the trainer is driven.

### Changed

- **Progress screen comes alive.** Rider level, XP, and the weekly streak now
  animate in on entry - the XP bar fills, the XP number counts up, and the
  streak flame breathes. A new **badges** section shows what you have unlocked,
  with locked badges hinting at what is next. All of it is decoration on top of
  the same pure progression functions in `shared/progression`.
- **Redesigned ride history.** Each session leads with a calendar date tile
  (weekday + day number) instead of a date-as-text line, with the workout name
  and one compact stats line beside it. Each week group header now carries that
  week's own totals - rides, time, and kilojoules - so you can see how much you
  did that week without opening any ride.
- **Reorganised Settings.** Related fields are grouped into single cards instead
  of floating as three separate boxes, and the **Report a problem** block is now
  one tappable row, with the what-gets-collected disclosure moved into the
  confirmation dialog right before anything is sent.
- **Ride-screen cues.** Holding the ERG target now shows a soft "ON TARGET" glow
  behind the power number, the screen edges flash once in the new zone's colour
  as each interval begins, and completing a workout gets an animated trophy.

### Editor (web)

- **Keyboard shortcuts panel** listing the editor's shortcuts (select, toggle,
  range, box-select, duplicate, delete, nudge power/duration).
- **Cleaner workout-details section** for the name and description fields.

### Notes

- `versionCode 7`, signed with the project's release key.

## [1.3] (6) - 2026-07-05

### Added

- **End & save a paused ride.** When a ride is paused - either you paused it, or
  it auto-paused because you stopped pedaling - you can now tap **End & save** to
  finish and keep it in your history, instead of having to pedal all the way to
  the end. A short confirmation spells out that the ride so far is saved and
  can't be resumed.
- **Redesigned home screen.** The "Ride" screen now leads with an **Up next**
  hero that proposes one workout to do next (an unfinished ride, else your
  last-ridden workout, else a newest favourite), and starts it in a single tap
  when the trainer is connected. Below it, a **Quick picks** shelf surfaces your
  starred and most-ridden workouts.
- **Search and duration filters.** Find a workout by name or tag, and filter the
  library by length (up to 30 min, 30-60, or 60+) alongside the existing
  Favourites and tag filters.
- **Favourite from the list.** Star or unstar a workout straight from the library
  row, without opening it first.
- **Ride again from history.** Each past session now has a **Ride again** button
  that jumps straight back to that workout (shown only when the workout is still
  in your library).
- **Trainer status chip and QR scan in the top bar.** The home top bar now shows
  a single trainer-status chip (device name / pair / connecting) that opens
  pairing, plus a dedicated button to scan a workout QR code from the web editor.

### Changed

- **"Get workouts" screen reorganised.** Scanning a workout from the web editor
  is now the headline action, with Import file and Paste URL as secondary tiles;
  imported-workout management is tucked behind an expandable row and hidden
  entirely when you have none.
- **Cleaner ride header.** The landscape header drops the workout title so the
  live state and clock have room, and an auto-paused ride now reads
  "AUTO-PAUSED · PEDAL TO RESUME".

### Fixed

- **Shared ride card chart.** For a ride that ended early, the power curve is now
  plotted on the planned time axis so it lines up with the workout's zone bars
  instead of stretching across the whole width.
- **Filter no longer strands the list.** If the active filter disappears (you
  unstar your last favourite, or delete a workout), the list falls back to "All"
  instead of showing nothing.

### Notes

- `versionCode 6`, signed with the project's release key.

## [1.2.2] (5) - 2026-06-16

### Changed

- **Ride in any orientation.** The ride screen is no longer locked to landscape -
  it follows your device's own rotation, so you can train in portrait or
  landscape. The HUD now picks its layout from the actual screen space: a
  vertical stack in portrait (big power on top, cadence and heart rate beneath),
  a compact three-across on a landscape phone, and the full three-across with the
  live stats strip on tablets and foldables.

### Fixed

- **Heart rate (and the other metrics) no longer get cut off or overlap.** The
  HUD previously switched into the tablet layout on short screens and clipped the
  readouts; sizes now scale to the available height so nothing is hidden by
  accident. The portrait header also reflows to two rows instead of squashing the
  workout title and status into a sliver.

### Notes

- `versionCode 5`, signed with the project's release key.

## [1.2.1] (4) - 2026-06-16

### Fixed

- **ERG mode on the Zwift Hub (and other strict FTMS trainers).** These trainers
  reject target-power commands with "Control Not Permitted" unless control has
  genuinely been granted first. The app now takes control (FTMS Request Control)
  together with each target-power write until the trainer confirms the grant,
  and automatically re-requests control if a command is ever rejected, instead
  of giving up. If control still can't be obtained, the error now explains the
  usual cause: another app (e.g. Zwift) is connected to the trainer and holding
  control - close it and re-pair.

### Notes

- `versionCode 4`, signed with the project's release key.

## [1.2] (3) - 2026-06-16

### Added

- **Report a problem.** A new Settings entry (and a **Report** link on in-app
  error banners) drafts an email to the developer with recent diagnostic logs
  and your device model, Android version, and app version. No personal data
  (no name, email, body metrics, or location) is gathered, and nothing is sent
  until you press send in your email app.
- **"Connected" notification.** A brief in-app banner confirms when your
  trainer, heart-rate monitor, or controller connects, including silent
  reconnects after a device wakes from sleep.

### Changed

- **Much more persistent device connection.** Paired trainers, heart-rate
  monitors, and controllers now keep trying to reconnect instead of giving up
  after one scan: open the app before waking your trainer and it connects once
  the trainer starts advertising, and a mid-ride drop recovers on its own.
  Scanning is limited to when the app is in use or a ride is active, so it
  doesn't drain the battery in the background, and an already-paired device is
  no longer asked to pair again.

### Fixed

- **ERG mode on strict trainers (e.g. Zwift Hub).** The app now enables FTMS
  Control Point indications before sending target power, which these trainers
  require - so ERG mode holds the target watts instead of silently doing
  nothing. When ERG control does fail, a clear banner explains why instead of
  failing quietly.

### Notes

- `versionCode 3`, signed with the project's release key.

## [1.1] (2) - 2026-06-15

### Added

- **Activity export (FIT / TCX).** Export any past ride from its overview as a
  FIT or TCX file and hand it to the OS share sheet - send it to Strava, Garmin
  Connect, intervals.icu, TrainingPeaks, or save it anywhere. A small sheet lets
  you pick the format. Both encoders are pure-Kotlin in the shared module (no
  third-party libraries), and the whole flow is offline - nothing leaves the
  device except through the app you choose.
- **Tablet & large-screen UI.** The app now adapts to tablets and unfolded
  foldables:
  - A side **navigation rail** at wide widths (the bottom bar stays on phones).
  - A **multi-column workout list** instead of stretched full-width cards.
  - A reworked **ride HUD** with much larger power / cadence / heart-rate
    readouts, a taller workout-profile timeline, and a live stats strip showing
    elapsed time, average power, and interval progress, all tinted to the
    current power zone.

### Changed

- **Backup & sync is now wipe-safe.** If the app's local data looks accidentally
  reset (after a reinstall, cleared storage, or a bad migration), sync now
  **pauses instead of overwriting your backup**, and surfaces a clear choice:
  **Restore from backup** (pull your rides and settings back onto the device) or
  keep the device's data. Each backup file is also kept as a recoverable `.prev`
  copy before being overwritten. Automatic and manual syncs both respect the
  pause until you resolve it.

### Project / distribution

- The app is now distributable via **[Obtainium](https://github.com/ImranR98/Obtainium)**,
  which tracks signed APKs from the Codeberg releases for automatic updates.
  Install links / badge added to the README and the [indoorbike.app](https://indoorbike.app) home page.
- New **editor URL workout API**: third-party tools can open the in-browser
  workout editor with a workout preloaded via plain URL parameters. Human docs
  at [indoorbike.app/api](https://indoorbike.app/api/), machine spec at
  `indoorbike.app/editor/llms.txt`, plus optional "Open in Indoor Bike" badges.
- The editor now also loads workouts immediately with a non-blocking **Undo**
  instead of a confirmation dialog.

### Notes

- `versionCode 2`, signed with the project's release key.
- Indoor rides carry no GPS, so GPX export is intentionally not offered - FIT and
  TCX both express time, power, heart rate, and cadence without a position track
  and are accepted by the major training platforms.

## [1.0] (1)

- Initial pre-release: BLE FTMS smart-trainer control, ERG-mode power-zone
  workouts, plain-text JSON workouts with an in-browser editor, on-device
  progression (streaks, levels, badges, time-in-zone), FIT export with optional
  Intervals.icu sync, and folder-based cross-device backup. F-Droid posture
  throughout (no Google Play Services, no analytics, no proprietary blobs).
