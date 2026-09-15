---
name: indoorbike-companion
description: Build a companion app that drives an Indoor Bike trainer ride over the local network - the game owns the world, position and virtual speed; the phone owns the trainer, the gears and the recording. Use when implementing or debugging the client side of the Indoor Bike Companion API (WebSocket, JSON, `_indoorbike._tcp`, `ws://host:port/companion`, `world`/`telemetry`/`requestShift` frames), when a companion cannot pair, connect or find the phone on the network (UDP discovery beacon on port 47601, mDNS), when trainer resistance feels wrong, or when a companion is being written in Godot, Unity, Unreal, C#, Python or TypeScript.
---

# Building an Indoor Bike companion

You are building the **client** side of the Indoor Bike Companion API. The phone running Indoor
Bike is the WebSocket **server**; your app connects to it.

Read [references/protocol.md](references/protocol.md) before writing frame-handling code. It is
the complete, normative reference: every frame, field, type, range, error and close code. This
file is the workflow and the things that are easy to get wrong.

For Godot specifically, read [references/godot.md](references/godot.md).

## The split, which decides everything else

| You own | The phone owns |
|---|---|
| The world and where the rider is in it | The BLE link and FTMS quirks |
| **Virtual speed** | Virtual gears and all shifting logic |
| The gradient under the wheels | The force commanded to the trainer |
| Rolling resistance, drag, wind, draft, surfaces | The safety envelope |
| When the ride ends | Session state, ride record, FIT/TCX export |

The phone never learns the rider's position. You send a gradient, a speed, and cumulative
totals. Nothing in the protocol carries a coordinate during a ride.

**You own speed because the phone needs it and should not compute it.** The commanded force
contains `0.5 * rho * CdA * v^2`, so the phone must *know* a speed. Letting you supply it is
what makes drafting, surfaces, wind and power-ups your own arithmetic instead of protocol
fields. Apply them to the speed you compute, then send the result.

## Order of work

1. **Connect and handshake.** Manual host/port/code entry first. Discovery and QR can wait;
   nothing else works until the handshake does.
2. **Render telemetry.** Prove the socket both ways before you drive anything: show power,
   cadence, heart rate and gear from `telemetry` frames.
3. **Send a flat road.** `world` at 10 Hz with `gradient: 0.0` and the speed from
   `telemetry.speedMps`. The trainer should feel like flat ground. If it does not, stop and fix
   it here.
4. **Add terrain.** Now the gradient comes from your world.
5. **Add gears.** Draw the gear display from `welcome.gears`; wire `requestShift`; handle
   `gearChanged` including `changed: false`.
6. **Add discovery.** Bind UDP 47601 and read the beacon; see `references/godot.md`. The
   phone's address is the datagram's source, never a field in the payload.
7. **Add `course`, `setState` and `finish`.** These are what make a companion ride land in the
   rider's history looking like a ride rather than like a log file.
8. **Send `setState: "active"` when your ride begins.** Nothing is recorded until you do.
9. **Add `controls`, if you show menus.** Lends the rider's phone to a screen they cannot
   otherwise answer.

Do not start at step 4. A companion that renders a mountain before it has proven a flat road
has two untested systems and no way to tell which is wrong.

## The fourteen rules that prevent most bugs

1. **Send `hello` first.** Any other first frame is `unauthorized` and a close with 4002.
2. **`role` defaults to `controller`**, and any value the phone does not recognise (including
   wrong case) silently grants **observer**, which cannot steer.
3. **Take the rider's speed from `telemetry.speedMps`, do not integrate one from power.** This
   is the single biggest feel bug in a new companion. See the next section.
4. **Always send both `gradient` and `speedMps`.** `speedMps` is required. A `world` frame
   without it is `malformed` and closes the socket with 4008.
5. **`seq` must strictly increase.** Frames with `seq <= last` are dropped **silently**. This
   presents as "the road is frozen" with no error anywhere.
6. **`distanceM` and `ascentM` are cumulative totals, never deltas**, and never decrease. Send
   a smaller value and it is ignored.
7. **Never hardcode the WebSocket port.** It is ephemeral, different every ride. Read it from
   the UDP beacon, the mDNS SRV record, or the QR payload. The only fixed port in the protocol
   is **47601**, where discovery datagrams arrive - a listener cannot discover the port it
   should be listening on.
8. **Read `welcome.honours` and `welcome.capabilities`.** They are what this build *acts on*,
   which is not what the protocol defines. Branch on `capabilities`, never on the negotiated
   version number. `crr`, `cda`, `airDensity` and `headwindMps` are reserved and **not
   honoured**.
9. **Missing telemetry fields mean absent, not zero.** No `hr` means no strap, and no
   `speedMps` means the trainer cannot count cadence. Defaulting to 0 puts a heart rate of zero
   on your HUD and stops the rider dead.
10. **Follow `stateChanged`, do not assume your `setState` took effect.** The rider has their
    own pause button and the phone has a stop-pedalling watchdog, so pauses happen that you did
    not ask for.
11. **Surface `warning` frames in development.** `fieldNotHonoured` is the difference between
    "drafting doesn't work" and "this build ignores `cda`". `rejected` is the difference
    between "my course never shows up" and "your polyline is too long".
12. **Ignore unknown fields, frame types and enum values.** Never treat them as errors. This is
    what lets your build share a socket with a later one.
13. **Send a fresh random nonce in every `pairProof`, and check `welcome.proof`.** A reused
    nonce and an unchecked proof each undo most of what pairing is for.
14. **Smooth the gradient before you send it.** It does not come out of a route file ready
    to use, and it goes straight into the force on the rider's legs. See the next section but
    one.

## Speed: take it, do not compute it

`telemetry.speedMps` is how fast the rider's legs and their chosen gear are turning the road
out. **Use it as the bike's speed.**

A chain does not slip, so `v = cadence x development` is not an estimate of the rider's speed,
it *is* their speed, and the phone knows it on the first turn of the crank. If you integrate a
speed out of power instead, you are solving `m . dv/dt = P/v - F_road` from a standing start.
That model is not wrong so much as answering a different question: it describes a bicycle that
is genuinely stationary, rather than one whose cranks are already turning at 80 rpm. What the
rider sees is watts and cadence arriving from the trainer while the speed sits at zero for five
to ten seconds - at the start of a course, and again every single time they stop and start.

You still own the world. Take `speedMps` as the drivetrain's output, apply whatever your game
adds on top - drafting, surfaces, wind, a descent that carries the rider faster than their gear
could turn - and send the result back as `world.speedMps`. The two agree wherever the rider is
settled, because both ends are solving `P = F_road(v) . v`, and they are meant to disagree
everywhere else.

When the field is **absent**, the trainer cannot count cadence and there is no drivetrain speed
to be had. Only then fall back to integrating from power.

## Gradient and distance: a route file gives you neither

A GPX, a TCX or a height field gives you positions and altitudes. Both of the numbers the phone
wants are one step on from those, and it is an easy step to skip.

**The gradient has to be smoothed, because it is a force.** `world.gradient` goes into the
force commanded to the trainer, and nothing on the app side filters it: the phone clamps it,
applies the deadman, and commands it. So whatever shape your gradient has, the rider's legs
get. Differencing consecutive altitudes gives a step function, and the steps are not small.

There are two smoothing steps and the first one does most of the work. Resample the elevation
onto a fixed grid, **smooth the elevation itself** over about +/-50 m, and only then take a
**centred difference over about 40 m**. Measured on a real 6.7 km GPX resampled every 10 m,
looking at the worst jump between two neighbouring samples:

| how the gradient is derived | steepest | worst jump between samples |
|---|---|---|
| raw difference between altitudes | 17.1% | **13.4 points** |
| 40 m centred difference alone | 11.1% | **3.0 points** |
| elevation smoothed +/-50 m, then 40 m centred difference | 10.1% | **0.6 points** |

A 13-point step is nearly half the range from flat to the 30% ceiling, arriving in one go, once
every ten metres of road. What the rider feels is the trainer lurching at a steady rhythm on
ground that looks smooth. Three points is still a 25 N kick at 84 kg, which is why the
elevation smoother is not optional: the centred difference alone leaves five times more of the
problem than it needs to.

The windows are sized against the noise rather than the terrain. Consumer GPS altitude wanders
a metre or two between samples ten metres apart, and a shorter window leaves several per cent
of phantom gradient on a road that is dead flat. A hundred metres is still short enough that no
real ramp is smoothed away, so erring wide is the right way to be wrong.

Do it once, where the route is prepared, and read it back at ride time. Deriving it twice with
slightly different arithmetic - once for the trainer, once for anything you draw - is the bug
this rule exists to prevent, and it shows up as resistance that disagrees with the number on
the screen.

**A note on distance.** `speedMps` and `distanceM` are both defined along the road surface,
while latitude and longitude give you horizontal distance. The two differ by
`1 / cos(atan(gradient))`, so converting is one multiplication in the same place you smooth the
gradient. It is worth doing and it is not worth worrying about: on a hilly 6.7 km route the
whole correction is under 0.2%, which is far inside the accuracy of the drag and rolling
coefficients the speed was computed with in the first place. Convert because it is nearly free,
not because the ride record is wrong without it.

## Making the ride land in history properly

Three frames turn a companion ride from a column of numbers into something the rider recognises
a week later. All three are optional, and all three are worth the hour.

- **`course`** carries the road: a name, the world, the course's own length and climb, an
  encoded polyline and an elevation profile. Fields merge across frames, so send the `name`
  early - the phone's HUD shows it while the ride is happening - and the `shape` at the end,
  which is the only order a free-roaming game can manage. With it, the ride draws its map and
  its elevation chart in history and in the share image exactly as a route ride does. Without
  it, twenty rides all called "Companion ride" with no shape.
- **`setState`** pauses and resumes the recording. If your game has a pause, a menu or a
  loading screen, use it, or the ride record quietly accumulates minutes of zeroes nobody rode.
- **`finish`** ends the ride on the phone. Nothing else can know a companion ride is over.

`finish.summary` is the short form of `course` and merges into the same place. You need one of
the two, not both.

## A session is many rides

`finish` ends a ride, not the connection. The phone writes that ride to the rider's history
straight away, with nobody tapping anything, and stays connected with the clock stopped. Send
`setState: "active"` again and the next ride begins.

```
setState active -> ride -> finish
                            <- stateChanged finished   (saved to history)
                            <- stateChanged paused     (ready for another)
setState active -> ride -> finish
```

- **Never wait for the socket to close.** It does not close on a finish. A companion that waits
  for one before starting the next ride waits forever. (Before 0.7 it did close, with `4005`.)
- **Send a new `course` for the new ride.** The pairing, the version and your `controls` all
  survive, because the connection did. The course does not: it described the ride that ended.
- **Keep counting `distanceM` if you like.** The phone takes your first report after each
  `setState: "active"` as that ride's origin, so a counter that runs all session and one that
  restarts per ride both record correctly.
- **A late `course` still lands.** If you only know your polyline after the ride, send it after
  the `finish` and the phone folds it into the ride it already wrote.
- **Keep sending `world` between rides.** The trainer is not being driven while no ride is
  running, the same as before the first one, but the deadman is still watching and the gradient
  you are sending is the one that bites the instant the next ride starts.
- **Always send `finish`. A disconnect is not a cancel.** If your socket closes mid-ride and
  does not reconnect within 25 seconds, the phone ends the session and **saves** the ride that
  was underway, flagged as cut off by a disconnect. The rider sees a "disconnected" panel with a
  countdown and the session's rides the moment you drop. Reconnect inside the window (a game
  restart, a sleeping laptop) and nothing is lost or ended.

`4005` now means the rider left Companion mode on the phone. That is the end of the session and
the last thing you will hear.

## Your game owns the start

The app prepares the ride the moment you connect. Telemetry starts flowing, the road is applied,
the trainer responds. **But the clock does not run and nothing is written down until you send
`setState: "active"`.**

```json
{ "t": "setState", "state": "active" }
```

Send it when your world says the ride has begun: past the menus, off the start line, out of the
lobby, whenever that is for you. Until then a rider can spin up freely and none of it lands in
their ride record.

There is no countdown. Your game has its own idea of when a ride begins and the app cannot see
it, so it does not count anybody in; two countdowns fighting each other is worse than either.
The phone shows "Ready, start the ride in the game" while it waits.

**If you forget this, the rider pedals, the road works, and nothing is ever recorded.** It is
the single easiest way to ship a companion that looks right and saves nothing.

## Optional: be remembered, and stop typing codes

The pairing code is per ride. New code, new port, every time. Pair permanently once and you
connect straight into the ride afterwards with no code and nothing for anyone to tap.

**Pairing, once.** Send a stable `deviceId` in your `hello`. Ask *your* user whether they want
this, then send `{"t": "pairRequest", "name": "Your app"}`. The rider is asked on the phone. You
get back a `paired` frame with a key; store it against the app's `deviceId`. A `pairDeclined` is
not an error - offer to ask again next time.

**Reconnecting.** Send `hello` with your `deviceId` and no token. The app answers `challenge`
with its own id and a nonce instead of welcoming you. Reply:

```
proof = hex(HMAC_SHA256(keyBytes, "indoorbike-companion-v1:companion:" + appNonce + ":" + yourNonce))
```

with your own **fresh random 16-byte** nonce. The welcome that follows carries the app's own
proof over the same nonces with `:app:` instead of `:companion:`. **Verify it.**

Four things to get right:

1. **A new nonce every time.** Reusing one is signing something only the app chose.
2. **Verify `welcome.proof`**, or you trust whatever answered on the port.
3. **Fall back, do not fail shut.** The rider can forget you; then it is the code again.
4. **Never log the key.** It drives somebody's trainer.

It authenticates, it does not encrypt: the socket is plaintext either way, and anything on the
network can still read the rider's watts.

## Optional: put the rider on your bike

By default the rider rides the drivetrain they chose in the app's settings, which is a
reasonable bike and the one they picked. **Most companions should leave it alone.** Send a
`drivetrain` frame only when your game has an opinion: a mountain stage that hands out a
32-tooth granny ring, a track session on a fixed gear, a bike the rider unlocked.

```json
{ "t": "drivetrain", "name": "Race bike", "chainrings": [50, 34],
  "sprockets": [11, 12, 13, 14, 15, 17, 19, 21, 23, 25, 28, 32],
  "wheelCircumferenceMm": 2105 }
```

1x, 2x or 3x is the length of `chainrings` - there is no separate field for it, because a field
like that can disagree with the list beside it.

Two things to know. An invalid spec is **refused whole, not repaired**: a 0-tooth sprocket gets
a warning naming it and the rider keeps the bike they had. And the ladder you get back is
**shorter than the list you sent**, because near-identical ratios are dropped, so a 2x with
fifty-odd combinations becomes about twenty gears. The app answers with a `drivetrainChanged`
event carrying the resulting ladder and the current gear; draw your gear display from that.

Send it during setup, before `setState: "active"`. Nothing is persisted, and the rider's own
setting comes back when the ride ends.

## Menus: lend the rider your phone

Your game is on a television across the room. The rider is on a bike, with a phone on the bars
and no keyboard. When you put a menu up, they cannot answer it.

`controls` fixes that. While you say a controllable screen is visible, the app draws a pad of
up, down, left, right, enter and back, and each press comes back as a `controlPressed` event.

```json
{ "t": "controls", "visible": true, "title": "Choose a route",
  "enabled": ["up", "down", "enter", "back"] }
```

**`enabled` is the part that matters.** The app cannot see your screen. It does not know whether
what is up is a list of four things, a yes/no dialog, or a loading state that ignores
everything, so it draws every control you do not list as visibly dead rather than hiding it. A
rider must never press something that looks live and get nothing, and the pad must not reshuffle
under a thumb already moving toward a button.

Send a new frame whenever selectability changes. Send `visible: false` when the menu closes, or
the rider is left holding a remote wired to nothing.

## Do not chase the light feeling at the start of a ride

The app helps riders off the line. Pulling away from a standstill is genuinely hard - almost
none of the resistance is air at walking pace, so what is left is gravity, rolling and the mass
of the bike, and the rider gets a heavy grinding pedal with no reward for it. For the first few
seconds the app therefore commands about half the real force, and fades to full physics over ten
seconds once the rider passes 5 km/h.

This is entirely app-side. Your gradient is being honoured exactly as you sent it, and
`telemetry.appliedGradient` will confirm that. If a ride feels light for the first few seconds
and normal thereafter, that is this and not a bug in your world.

## Rates

| Thing | Rate | Note |
|---|---|---|
| `world` out | 4-10 Hz | Fixed timer, **not** per frame. A 144 Hz game must not send 144 Hz |
| `telemetry` in | `welcome.tickMs` (250 ms) | Interpolate for rendering; do not ask for faster |
| `requestShift` | max 10/s | Excess is warned and dropped, never an error |
| `course` out | whenever | Merged field by field. Once at the start and once at the end is the usual shape |
| `setState` out | on your own transitions | A request, not a command. `stateChanged` is the answer |
| `controls` out | when a menu opens, changes or closes | The last frame wins. Do not forget `visible: false` |
| Deadman | 1500 ms | Then the gradient ramps to flat over 2000 ms |

The physics on the phone moves four times a second. A faster telemetry stream would be
inventing data, which is why there is no way to ask for one.

## The deadman, and what it means for your loop

If your `world` frames stop for 1500 ms, the phone ramps the gradient to zero over the next
2000 ms and emits `worldTimeout`. Speed is held, not ramped, so the rider lands on an ordinary
flat road rather than against nothing.

This fires on a loading screen, an alt-tab that pauses your main loop, a garbage-collection
stall, or a menu. **Keep sending `world` whenever the rider could still be pedalling**, even if
your world is paused - send the current gradient and a speed of zero rather than nothing at
all. Treat `worldTimeout` as a bug report about your own frame loop.

## Debugging

| Symptom | Cause to check first |
|---|---|
| Socket closes immediately, code 4002 | First frame was not `hello`, or the token is wrong |
| Socket closes, code 4001 | Version ranges do not overlap. The error names both |
| Socket closes, code 4008 | A frame failed to parse. Usually a missing `speedMps` |
| Connected but cannot steer | You were granted `observer`. Check `welcome.role` |
| `error` `notController` | Same. Reconnect with `role: "controller"` |
| Road frozen, no errors | `seq` is not increasing |
| Resistance feels flat on hills | Check `telemetry.appliedGradient`, not what you sent |
| Resistance wrong at speed | You are not sending a real `speedMps` |
| Distance stuck | You are sending deltas instead of cumulative totals |
| 4003 on connect | Another controller holds the ride |

`telemetry.appliedGradient` is what the rider is actually feeling, after clamping and the
deadman. Display that, not your own value. `trainerForceN` and `trainerGradePercent` are
published so you can tell a protocol bug from a physics disagreement -
`trainerGradePercent` is a *force* expressed against an assumed 84 kg trainer mass, not a
gradient; never display it as one.

## Driving the phone by hand

A **debug build** of Indoor Bike ships a harness built for exactly this job. It is the fastest
way to make the phone do a specific thing on demand, and it beats a mock server as soon as you
want to test how your game *feels* rather than how it parses.

Setup, once: Settings, Devices, Trainer, **Use mock trainer** (debug builds only). Then choose
**Companion** on the Ride tab.

**In a debug build the pairing code is always `AAAAAA`.** Hardcode it in your development
build and stop reading it off the screen. Release builds generate a fresh one per ride, so do
not ship a client that assumes it - keep the entry field.

On the companion HUD there is a **DEBUG** strip along the bottom. Expand it and you get:

| Control | What it does |
|---|---|
| **Manual: on** | The mock trainer stops inventing a rider and reports exactly what you set |
| Power / Cadence / Heart steppers | The readings that reach you in `telemetry` |
| **Wobble** | A slow sine on power, so your smoothing has something to smooth |
| Coast / Endurance / Threshold / Sprint | One-tap presets, including 0 W and 700 W |
| Shift down / up / **as Click** | Real shifts, so you see `gearChanged` with each `source` |
| `worldTimeout`, `worldResumed`, `trainerLost`, `riderInput +/-` | One-off events, injected |
| Revoke control, End ride | Real actions that change the ride, not just the wire |

Two things to know about it:

- **Injected events are indistinguishable from real ones on the wire.** That is deliberate: you
  should not need a test mode of your own to receive them.
- **The rest of the stream is completely ordinary.** The session machinery, the physics, the
  force model and the wire format are all the real ones. The only difference is that a person
  is choosing the watts.

If your companion connects but stalls before sending a road, the pairing screen has a debug
button that opens the HUD anyway, so the harness is reachable even when your end is the broken
one.

## Testing without a phone

There is a working minimal client in the Indoor Bike repository worth reading before you write
yours: `scripts/companion_probe.py` is a companion in one file, standard library only, with a
hand-rolled WebSocket client. It does the handshake, pumps `world` frames, sends `drivetrain`,
`controls`, `setState` and `finish`, and prints everything that comes back. It is the shortest
complete example of the client side of this protocol that exists.

For the other direction, write a mock server before you have a trainer to hand. It needs to: accept a WebSocket on
`/companion`, answer `hello` with a `welcome`, push `telemetry` every 250 ms, and echo
`gearChanged` on `requestShift`. That covers every path except the trainer feel, and it lets
you develop on a plane.

Also test these, because riders hit all of them:

- The phone's Wi-Fi drops mid-ride (socket closes without a close frame)
- Your app is alt-tabbed for 5 seconds (deadman)
- The rider shifts on the handlebar (`gearChanged` with `source: "zwiftClick"`)
- The rider ends the ride on the phone (`stateChanged` `finished`, then close 4005)
- **A second ride straight after the first**, on the same connection, without restarting
  anything. `companion_probe.py --scenario twice` is this exact test if you want something
  to compare against
- A second instance of your app connects (4003)

## Scope

Do not ask the phone for anything outside the protocol. It will not send position, it will not
accept a route, and it will not let you shift directly. If a feature seems to need one of
those, it belongs on your side of the socket.

The Indoor Bike app is GPLv3; a closed-source companion is fine, because a separate program
communicating over a network socket is not a derivative work. That holds only while the
boundary stays a socket: do not link the app's code into your process.
