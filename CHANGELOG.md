# Changelog

All notable changes to **Indoor Bike** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); versions map to the Android
`versionName` (with `versionCode` in parentheses).

## [Unreleased]

### Changed

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
