Indoor Bike 2.0.1

A new look, and roads to ride on.

Changed
- **The whole app was redrawn in a new design system, Slate.** Five slate-blue surfaces, three cool whites, one typeface, and no accent colour at all: the power zones are now the only saturated colour anywhere, so a colour on screen always means an intensity. The ember orange is gone, and with it the tinted icon wells, the gradient fills and the outlines around cards - a card is a surface one step lighter than the canvas and nothing else. Bebas Neue, Inter and JetBrains Mono are replaced by Manrope, bundled as before, with tabular figures so a ticking number does not shuffle the digits beside it.

- **A new mark.** The same ridge and frame, recoloured and drawn at one stroke weight, with the rear wheel replaced by the flywheel of a direct-drive trainer - which is what the app actually talks to. It replaces the orange mark everywhere: the launcher icon and its themed variant, the notification icon, the site, and the wordmark on a shared ride card.

- **The home screen asks what you are riding, instead of listing workouts.** Workout, Route, or Just ride; you pick the kind first and the specific one on the screen after. A list of workouts was the right home screen when a workout was the only thing a ride could be, and stopped being right the moment a ride could also be a road. Searching and filtering the library did not go away, it moved to where choosing a workout actually happens.

- **In a ride, the power number is the biggest thing on the screen** by a long way, and it carries the zone colour, so intensity reads from across a room. The timeline under it is one bar per step: ridden steps at full strength, what is still to come at half.

- **Screen titles collapse as you scroll**, folding into the row the back button already occupies and coming back when you scroll to the top. The tab bar sits at the bottom edge as a floating pill with the list running underneath it, and screens slide rather than cross-fade, so the animation says which direction you went.

- **The gradient reading is drawn on the road around you** - a fixed 50 m behind and 500 m ahead, whatever the route's length. On a 169 km stage the whole-route profile cannot tell you how much further this climb goes or where the next one starts, because a two kilometre climb is a pixel wide.

- **A gear shift bites harder.** The model was exactly faithful to a bike, where one sprocket is a 5 to 8 % change in force. On the road that is plainly felt because your cadence drops with it; indoors, where the virtual speed comes from your power alone, you absorb it by pushing 5 % harder at the same cadence and never notice you shifted. Each shift now reaches further, and the gear matching your own real one still carries the road's own force unchanged. If shifting still feels soft, a 1x drivetrain in Settings gives much bigger steps than a 2x road bike does.

- **A repository's workouts and its routes are one stop.** There is a Workouts / Routes switch at the top of the browser whenever a repository carries both, so reaching the other half no longer means going back to the library and coming in through a different button.

Added
- **Community routes.** Repositories can publish roads as well as intervals. A route listing carries what a picker needs - distance, climbing, a gradient histogram, a small elevation profile and the shape of the road - and points at its GPX rather than containing it, so browsing fifty routes costs one small file and the geometry is downloaded once, for the one route you add. Each keeps showing the credit its data licence asks for.

- **Estimated ride time per route, for you.** Every route card says how long it will take at *your* FTP and weight, worked out from the gradient histogram rather than from distance, because 500 m of climbing taken as one steady drag and the same 500 m taken as twenty ramps are not the same evening. The physics is the physics the ride itself uses, so the estimate and the road agree.

- **Ride a long road across several sittings.** A published route is as long as the road it came from, and a Grand Tour stage is eight hours. Stop part-way and the app remembers where you got to; next time you pick that route it asks whether to carry on or start again, and the card says how far along you are. Climbing carries over, so a route ridden in pieces still totals the whole climb.

- **Long press a route to remove it from your device.** Removing one takes its saved position with it and leaves your rides in your history; you can add it again from the repository it came from.

- **Follow a community repository and the app tells you when it publishes something new.** Following is about being told, not being given: nothing reaches your library until you open the repository and add it. Checks happen when you open the home screen, at most every few hours, and never while the app is closed.

- **A route publishing tool on the website.** Drop a GPX at [indoorbike.app/routetool](https://indoorbike.app/routetool/) and it builds the `routes.json` a repository publishes - give it the file you already have and it adds to that, a route at a time, rather than handing you one entry to paste. It will show you the listing as the app draws it before you publish anything. Nothing is uploaded; the file is read in your browser.

Fixed
- **Cadence and heart rate were cut off on a landscape phone.** The simulation HUD stacked fixed type sizes and ran past the bottom of the screen, and the two readings that fell off were the ones you cannot work out from anything else on it.

- **A long route would download and then never finish.** The spinner stayed up for good on anything much past a few hundred kilobytes of GPX: a 42 KB route parsed in milliseconds and a 531 KB one took minutes. The parser looked for a namespace prefix by scanning to the end of the document and only bounded the result afterwards, and a GPX with no namespace prefixes - which is most of them - has no colon to stop that search, so the cost grew with the square of the file.

- **The keyboard no longer covers what you are typing.** Drawing edge to edge means the window no longer shrinks when the keyboard opens, so Settings, Connections, the onboarding sheet, the add-by-URL sheet and the rename dialog now make room for it themselves.

- The last row of the workout, route and repository lists no longer sits under the navigation bar, and the system's translucent scrim no longer shows as a band across the bottom on three-button navigation.

Notes
- The route list is marked Beta. Routes are the newest part of the app, they are what most of this release is about, and there are rough edges still to file off.
- Nothing to redo. Your workouts, history, paired devices and settings carry over.
- This is a big visual change and it is not optional: there is no way back to the orange theme.

Install
- Add this repo to [ObtainX](https://github.com/bikram-agarwal/ObtainX) for automatic updates, or grab the APK below.
- **The Codeberg mirror is retired, and this is the last release on it.** If your ObtainX source still points at Codeberg you will stop seeing updates: repoint it at [CodeFloe](https://codefloe.com/SnappTechnology/IndoorCyclingApp). Obtainium cannot track CodeFloe, so for some people this is a change of app as well as a change of address.

Signed with the project's release key; `versionCode 15`.
