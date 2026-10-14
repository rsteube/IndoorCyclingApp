# Companion API

The wire protocol for **Companion mode**: a ride where a separate program on another device
owns the world and Indoor Bike owns the trainer, the gears and the recording.

> **Status: draft, version 0.1. The app side is built.** Protocol core, WebSocket transport,
> mDNS discovery, pairing, the ride mode and the HUD all exist; see
> [Where it lives in the code](#9-where-it-lives-in-the-code) for the map and
> [the companion protocol reference](../skills/indoorbike-companion/references/protocol.md) for
> the exhaustive, implementer-facing description of what the app actually does with every frame.
>
> This is deliberately a *first guess*. It exists so that both ends can start being written
> against something concrete, and it is expected to change while the first companion is built.
> The version is `0.x` precisely so that it may break: see [Versioning](#versioning).
>
> For the design rationale behind the whole idea (why not ERG, control tiers, scopes,
> licensing, where the server lives) see [LIVE_API.md](LIVE_API.md). This document is the part
> you implement against. Where the two disagree, this one is newer.

## 1. What Companion mode is

A fourth ride mode alongside Workout, Route and Just Ride. The rider selects it on the phone,
pairs a companion, and rides. The phone shows a normal dashboard (power, cadence, heart rate,
gradient, gear). The trainer is in FTMS simulation mode. Everything about *where the rider is*
lives in the companion.

```
   Kickr Core / HRM / Zwift Click
             │ BLE
   ┌─────────▼─────────────────┐
   │  Indoor Bike (GPLv3)      │        ws:// + mDNS, LAN only
   │   trainer, gears,         │◄───────────────────────────────┐
   │   force model, recording  │                                │
   └───────────────────────────┘                    ┌───────────┴───────────┐
                                                    │  Companion            │
                                                    │  world, position,     │
                                                    │  speed, gradient      │
                                                    └───────────────────────┘
```

### The split

| | Owns |
|---|---|
| **Phone** | The BLE link and FTMS quirks. Virtual gears and shifting logic. The force actually commanded to the trainer. The safety envelope. The session state machine, the ride record and the FIT/TCX export. |
| **Companion** | The world. Where the rider is. Virtual speed. The gradient under the wheels. Rolling resistance, aerodynamics, wind, draft, surfaces. When the ride ends. |

**The phone knows where the rider is only when the companion says.** It is told how fast they
are going, what the road is doing under them, and how far they have come in total, which is
enough to command a trainer. From 0.11 a companion may also say where on its course the rider
is (`lat`, `lon` and `altM` on the [`world`](#world) frame), and the phone records exactly that,
second by second, into the ride's FIT and TCX files, so a ride shared from a game shows the road
it was ridden on. That is a place in the game's world, not where the rider's trainer stands. The
phone never works a position out for itself, and a companion that sends none rides as before.

### Why the phone still commands a force rather than passing the grade through

This is the one piece of the shipped route mode that Companion mode inherits wholesale, and it
is the reason the split lands where it does. From
[SimulationEngine](../shared/src/commonMain/kotlin/app/indoorbike/shared/simulation/SimulationEngine.kt):

```kotlin
val road = RideDynamics.roadForceNewtons(gradient, speedMps, totalMassKg)
return softCeiling((road * gearFactor).coerceAtLeast(floor))
```

Handing the trainer a grade and letting its own road model do the rest fails twice, and a
rider feels both immediately:

- **Shifting on the flat would do nothing.** Grade is zero there, so scaling it by the gear
  scales zero.
- **The trainer's aerodynamic term comes from its own flywheel speed**, which reflects the
  real gear the bike is parked in, not the virtual one. On a descent in a small real gear that
  term collapses and the rider spins against nothing.

So the phone computes the road force itself, multiplies it by the virtual gear, and commands
that force (expressed as a grade, because FTMS has no force field). See
[ARCHITECTURE.md](ARCHITECTURE.md#simulation-route-rides) for the full argument.

### Whose speed the road is evaluated at

The road force needs a speed for its aerodynamic term, and there are two in play.

**You own the world.** Where the rider is, how fast their avatar is moving, and every rule
about drafting, surfaces and wind that produced it. That is the speed on the `world` frame,
and nothing here second-guesses it.

**The road force is evaluated at your speed.** At the instant of a shift the bike is still doing
the speed it was doing, so the force at the wheel has not moved and the rider meets it through a
different ratio:

```
force at the trainer = (road force at world.speedMps + m . a) x loadFactor(gear)
```

with `loadFactor` the virtual gear's development over the assumed real one (3.35 m per crank
turn), as `welcome.gears[].loadFactor` gives it. The road used to be evaluated at
`cadence x development` instead, which put the cube of the ratio on the legs in the seconds
after a shift rather than the ratio.

**The phone owns the drivetrain.** A chain does not slip, so a gear decides how fast the road
goes past for a given cadence - `v = cadence x development` - and telemetry reports that as the
phone's own speed. A settled rider solves `P = F_road(v) . v` from either end and lands on the
same speed, so the two agree wherever the road is steady and part company only where a bicycle
would: mid-acceleration, while coasting, and when a gear cannot turn the road out fast enough.
Telemetry reports both, so a disagreement is visible rather than mysterious.

A [run](#4c-run-practice-014) is the exception: there the road is a pure load, with no air and no
mass, and the game decides what it is worth.

The road force also carries an acceleration term, `m_eff . dv/dt`, differentiated off the
speed *you* send. You are already integrating that equation to produce speed; the phone was
commanding only its `F_road` half, which is why a bike used to weigh nothing the moment it
changed speed.

The consequence for a companion author: **you own speed, and you must send it.** If you do not
send speed the phone has neither a fallback for a cadence-less power source nor an
acceleration to bill for, and the resistance will be wrong on anything but a standstill.

## 2. Transport

- **WebSocket** (`ws://`), JSON text frames, UTF-8. One connection per client.
- **Path**: `/companion`. Version is negotiated in-band, not in the path, so a mismatch
  produces a readable error instead of an HTTP 404.
- **The phone is the server.** It holds the BLE link, so anything else means proxying the
  trainer through a second hop.
- **The server listens only while a Companion-mode ride is prepared or active.** It is not a
  background daemon. Ride ends, socket closes, mDNS advertisement withdrawn.
- **Local network only.** No relay, no cloud, no accounts. To reach it from elsewhere, put
  both devices on the same WireGuard or Tailscale network; the protocol is unchanged.

### Security model, stated plainly

A device already on the rider's network, once authenticated, can change the resistance of a
trainer that a person is clipped into. That is the threat model, and it is why control is never
implicit:

- The rider starts Companion mode deliberately. **Nothing listens otherwise**, which is the
  single most valuable property here and the reason pairing did not change it.
- A stranger must present the pairing code, shown on the phone, fresh for every ride.
- A companion the rider has *permanently paired* skips the code and proves itself with a key
  instead. See [permanent pairing](#permanent-pairing) below.
- The connected companion's self-declared name and address are always visible on the HUD, with
  a one-tap **Take control** that drops to a flat road without ending the recording. It lets go
  of everything the companion was holding at once: the hill, a `setPower` target, an untracked
  stretch. The companion is told with `4004`.

**What is authenticated and what is not.** Pairing establishes who is on the other end. It does
not encrypt anything: traffic is plaintext on the LAN, so anything on the network can read a
rider's watts, and that is as true after pairing as before it. This is a deliberate limit, not
an oversight. Encrypting the stream would mean every companion needing TLS with custom pinning,
which is awkward in a game engine and would price out exactly the people this API exists for.

Do not run this on a network you do not trust.

### Limits a companion has to live within

None of these get in the way of a companion that behaves. They exist because the phone is
driving a trainer somebody is riding, and a socket on the local network is open to more than
the game it was opened for.

- **Finish the handshake within 10 seconds.** A connection that has not completed its `hello`
  (and, for a paired companion, its `pairProof`) 10 s after it opened is sent `unauthorized` and
  closed with `4002`. One that never said anything used to stay open for good.
- **Say `hello` first.** Before the handshake, a frame of a type this build does not know is not
  a later protocol version talking, since every version's first frame is a `hello`: it is
  answered with `unauthorized` and `4002`. After the handshake, unknown frames are ignored, as
  they always were.
- **Keep the handshake small.** Until the handshake is done, only text frames of at most 16 KB
  are read; anything else closes the connection with `4002`. A `hello` is a few hundred bytes.
  After it, a frame may be up to 8 MB, which is what a `screenshot` needs.
- **Nest no deeper than 32 levels.** A frame whose JSON nests deeper is `malformed` (`4008`).
- **Answer pings.** The app pings every 5 s and closes a connection that has not answered for
  10 s. Every WebSocket library answers pings on its own; a companion only has to keep reading
  its socket. This is also how a game that froze or lost its network is noticed in seconds
  rather than when the operating system gives up on the TCP connection.
- **Keep reading.** Frames to each companion go through their own queue. Telemetry that finds 4
  frames already waiting is skipped rather than queued, so a slow reader gets fewer, fresher
  frames instead of an ever-older backlog; any frame that cannot be written within 5 s closes
  the connection. A companion that stops reading no longer holds up the others, or the ride.
- **Wrong codes cost time.** The third failed handshake from an address makes it wait 1 s before
  it may try again, doubling with each further failure to at most 60 s. After 10 failed
  attempts in all, the pairing code is replaced with a new one and the phone shows it. Paired
  companions never use a code and are unaffected.
- **At most 8 connections at once, 4 of them still in their handshake.** One more is closed
  straight away with `1013` ("try again later").
- **`setState` at most twice a second.** Every state change writes to the trainer. A third
  request within a second is dropped with a `rateLimited` warning; follow `stateChanged` as
  always.
- **Names are cleaned.** Control characters and bidirectional-text overrides are removed from a
  client's name before the rider sees it, so a name cannot pose as another by reordering itself.

### Discovery

mDNS / DNS-SD, service type `_indoorbike._tcp.local.`, via Android's `NsdManager`.

| TXT key | Example | Meaning |
|---|---|---|
| `proto` | `0.1-0.11` | Supported protocol range, `min-max` |
| `path` | `/companion` | WebSocket path |
| `app` | `2.1.0` | App version, informational |
| `session` | `3f9a1c` | Stable id for this ride, for reconnection |
| `mode` | `companion` | Reserved: other modes may advertise later |

The port is ephemeral and published in the SRV record. Do not hardcode one.

**Typed fallback**, for networks where discovery is blocked. The pairing screen always shows
the address, the port and the code as text, in the order a connect form asks for them. There is
deliberately no QR code: a companion runs on whatever drives the screen the rider is watching,
which is usually a desktop with no camera pointed anywhere useful.

The deep-link form still exists for anything that wants to hand over a whole connection string
at once:

```
indoorbike://companion?host=192.168.1.42&port=47612&token=8F3K2M&session=3f9a1c
```

## 3. Versioning

The explicit requirement: both ends confirm they speak the same protocol, and fail with a
message a human can act on when they do not.

### Numbering

`major.minor`. **While `major` is 0, a minor bump may break compatibility.** This is normal
semver 0.x, and it is the honest description of a protocol being designed alongside its first
client. When the first companion ships, this goes to 1.0 and the rules below become binding.

**Current version: 0.15, and this build accepts everything back to 0.1.**

0.15 rides a run in ERG, where both ends list `runPower`: the game opens the run with a
`setPower`, the phone holds the trainer at it for the whole run, and the rider moves it 5 W at a
time with the buttons that shift on a bike. A `requestShift` does the same. Cadence is shown
against a band of 80 to 100 rpm. Without the capability at either end a run is the 0.14 load.
See [a run held in ERG](#a-run-held-in-erg-015).

0.14 added [run practice](#4c-run-practice-014): a `course` that carries a `mode` may say
`"sport": "run"`, and the ride is a runner on the game's course driven from a bike trainer. The
game sends one gear in an ordinary `drivetrain`, the phone stops shifting and hides the gears,
evaluates the road as a pure load (no air, no momentum, no launch assist) and shows pace. The
saved ride stays a cycling activity. A new optional `world.courseGradient` carries the real
course grade for display while `gradient` carries the grade the trainer should feel. Additive,
and per build like free ride: the phone lists `run` in `welcome.capabilities` where it draws a
run (Android), and a game sends `sport` only to a phone that listed it.

0.13 came out of the first rides of 0.12, and is additive. A `junction` may carry the shape of
each way out and of the road in, so the phone draws the same junction the game does, and
`commitInS` for a countdown; the game offers one junction at a time, always the next one, and
always sends `passed`. Destinations are browsed by category instead of searched: a game that
lists `placeCategories` sorts its places into at most four categories and answers a
`placesQuery.category`. See [free ride](#4b-free-ride-route-choice-and-navigation-012).

0.12 added [free riding](#4b-free-ride-route-choice-and-navigation-012): `course.mode` says
whether a ride is a course or a free ride, and a `course` that carries it replaces the ride's
description instead of merging into it. A game that lists `routeChoice` describes each junction
ahead in a `junction` frame and takes the rider's pick back as a `turnChosen` event; one that
lists `navigation` answers `placesQuery` with `places` and, once a destination is chosen, keeps
the phone told with `navigation` frames. Additive in both directions and **per build**: the
Android app lists `routeChoice` and `navigation` in `welcome.capabilities`, while the iOS and
Sailfish apps speak 0.12 without listing them until they draw a junction card. A game sends
those frames only to a phone that listed them, and steers the ride itself otherwise.

0.11 added where the rider is: optional `lat`, `lon` and `altM` on the [`world`](#world)
frame. The phone records them second by second beside power, with the cumulative `distanceM` it
already took, and writes them into the ride's FIT and TCX files: position, altitude, and each
second's distance as the companion counted it. Nothing is worked out on the phone. Additive in
both directions: the three fields are listed in `welcome.honours`, an older app answers them with
`fieldNotHonoured`, and a companion that sends none rides exactly as before.

0.10 added [untracked riding](#untracked-riding-and-setpower) and the
[`setPower`](#setpower) frame: a ride state where the rider works and none of it is written
down, and a fixed wattage to work at inside it. Pushing a bike up a climb the rider stalled on,
a warm-up, a cool-down. Additive, and gated on the `untracked` and `setPower` capabilities: a
companion from before 0.10 is told `paused` for the new state and never meets the new frame.

0.9 added screenshots: the phone sends [`requestScreenshot`](#requestscreenshot) when the rider
taps the camera button, and a companion that listed `screenshot` in its `hello` answers with a
[`screenshot`](#screenshot) frame carrying a JPEG or PNG, kept with the ride. Additive in both
directions: nothing is requested from a companion that did not say it answers.

0.8 added the [`brand`](#brand) frame: a name, a square PNG icon and three colours, so the
phone's ride header looks like it belongs to the game driving it. Additive in both directions.
The app adjusts colours it cannot draw legibly and says what it used in an `adjusted` warning.

0.6 added permanent pairing: `hello.deviceId`, the `pairRequest` / `paired` ceremony and the
`challenge` / `pairProof` handshake. Two devices that have met once recognise each other
afterwards with no code and no tapping. Optional, and a companion that ignores all of it keeps
working exactly as before.

0.5 added the `drivetrain` frame, so a game can put the rider on the bike the game thinks they
are riding. Optional: leave it out and the rider keeps the drivetrain they chose in settings.

0.4 moved the moment a ride starts, and is **the one change so far that is not purely
additive**. The app now records nothing until the companion sends `setState: "active"`, and
there is no countdown when it does. A companion that never sends it will connect, stream
telemetry and apply the road, and never start a ride. If you are updating an existing
companion, this is the line to act on.

0.3 added the `controls` frame and the `controlPressed` event, so a companion showing a menu on
a screen across the room can borrow the phone as a remote for it.

0.2 added three things, all of them additions:

- `telemetry.speedMps`, the drivetrain's own speed, so a companion no longer has to integrate
  one out of power. This is the one you should adopt first: it is why speed used to sit at zero
  for several seconds after the rider started pedalling.
- The `course` frame, so a companion ride is recorded, listed and shared like a route ride
  instead of like a column of numbers.
- The `setState` frame, so a game's own pause reaches the recording.

Nothing a 0.1 companion sends has changed meaning, so an older one connects and behaves exactly
as it always did. **Branch on `welcome.capabilities`, not on the version number** - it is what
this build will actually act on, as opposed to what the protocol defines.

From 1.0 onward:

- **Minor bump: additive only.** New optional fields, new frame types, new enum values. An
  implementation of 1.0 must keep working against a 1.4 peer.
- **Major bump: everything else.** A removed field, a changed meaning, a changed unit, a
  changed default.

### Negotiation

The client speaks first, declaring the range it supports. The server picks the highest version
both understand and states it in `welcome`.

```jsonc
// client -> server, first frame after the socket opens
{ "t": "hello", "protoMin": "0.1", "protoMax": "0.1",
  "token": "8F3K2M",
  "client": { "name": "Alpine Climb", "version": "0.3.1", "platform": "windows" },
  "role": "controller" }
```

```jsonc
// server -> client
{ "t": "welcome", "proto": "0.1", ... }
```

**Both ends must check.** The server rejects a client whose range does not overlap its own. The
client must equally verify that `welcome.proto` is a version it can actually speak, because a
server may legitimately choose a version the client listed but has since deprecated.

### Failing meaningfully

A version failure sends an `error` frame **and then closes with an application close code and
reason**. Both are required: a client that fails negotiation may close the socket before it
processes the frame, and then the close reason is the only thing it ever sees.

```jsonc
{ "t": "error", "code": "protoUnsupported",
  "message": "Companion 'Alpine Climb' speaks protocol 2.0-2.3; this build of Indoor Bike speaks 0.1-0.1. Update Indoor Bike, or run a companion build that supports 0.1.",
  "serverRange": "0.1-0.1", "clientRange": "2.0-2.3" }
```

```
WebSocket close: 4001 "protocol mismatch: server 0.1-0.1, client 2.0-2.3"
```

The client's own message when *it* rejects the server must name both sides the same way. The
rule for both: **say what each end speaks and which end to update.** "Incompatible version" is
not an acceptable message.

| Close code | Meaning |
|---|---|
| `4001` | Protocol version mismatch |
| `4002` | Bad or missing token |
| `4003` | A controller is already connected |
| `4004` | Control revoked by the rider |
| `4005` | The app left Companion mode. Since 0.7 a `finish` does **not** close the socket; this is the rider backing out of the screen |
| `4008` | Malformed frame the server could not parse. Mid-ride the ride pauses, exactly as for a disconnect |

### How this grows without churn

Two mechanisms, deliberately separate:

- **Version** answers "do we speak the same language". It changes rarely.
- **Capabilities** answer "what does this particular build actually do". They change freely.

A build that gains support for a new road parameter announces it in `welcome.honours`. That is
not a version bump, and a companion written before it existed is unaffected.

Required receiver behaviour, both directions:

1. **Ignore unknown fields.** Never an error.
2. **Ignore unknown frame types.** Log if useful, never close the socket.
3. **Tolerate unknown enum values.** Fall back to a documented default.
4. **Never silently drop something meaningful.** If the phone receives a road field it does not
   honour, it must emit a `warning` **once per connection** naming the field. This is the
   single most useful debugging affordance in an evolving protocol: it is the difference
   between "drafting doesn't work" and "this build ignores `cda`".

**Numbers are read for their value, not their spelling.** Where a field is an integer, the phone
takes any whole number however it is written: `921`, `921.0` and `9.21e2` are the same value.
Engines that load their data from JSON write integers with a trailing `.0` without being asked
(Godot's `JSON.stringify` does it to every number that went through `JSON.parse_string`), and
builds up to 2.2 refused such a frame as malformed. A number with a real fraction is still
refused where a count belongs: `steps: 1.5` is a bug, and closes with 4008. Send plain integers
anyway, because a released phone that refuses them is still out there.

## 4. Frames

Every frame is a JSON object with a `t` discriminator. Fields the sender has no value for are
**omitted, not zeroed** - a missing `hr` means no strap, not a heart rate of zero.

### Client to server

#### `hello`

First frame. See [Negotiation](#negotiation).

| Field | Type | Required | Notes |
|---|---|---|---|
| `protoMin`, `protoMax` | string | yes | Supported range, inclusive |
| `token` | string | yes | From the QR payload or typed by the rider |
| `client` | object | yes | `name`, `version`, `platform`. `name` is shown on the HUD |
| `role` | string | no | `controller` (default) or `observer` |
| `capabilities` | string[] | no | What the companion can do, e.g. `["summary"]`. `screenshot` gets the rider a camera button. `routeChoice` and `navigation` (0.12): see [free ride](#4b-free-ride-route-choice-and-navigation-012) |

**Send nothing until the `welcome` arrives.** A phone that remembers a companion answers
`hello` with a `challenge` rather than a `welcome`, and while that proof is outstanding every
other frame is `unauthorized` and closes with 4002. A road pump started on connect rather than
on the welcome loses that race whenever the answer takes longer than one tick.

**One controller, many observers.** A second controller is refused with close code `4003`. An
observer receives telemetry and events but may not send `world`, `requestShift` or `finish`.

#### `world`

The road under the rider. Send at your frame rate; 4 to 10 Hz is sensible. The phone applies
the newest frame on its next 250 ms tick and rate-limits its own BLE writes, so faster than
10 Hz buys nothing.

```jsonc
{ "t": "world", "seq": 412,
  "gradient": 0.062,
  "speedMps": 7.42,
  "distanceM": 8412.5,
  "ascentM": 214.0,
  "crr": 0.0122 }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `seq` | int | yes | Monotonic. The phone drops out-of-order frames |
| `gradient` | float | yes | Rise over run. `0.06` is a 6% climb, `-0.06` a descent |
| `speedMps` | float | yes | Virtual speed **along the road surface**. Drives the drag term |
| `distanceM` | float | no | Cumulative along-road distance this ride. For the ride record |
| `ascentM` | float | no | Cumulative metres climbed this ride. For the ride record |
| `crr` | float | no | Rolling resistance under the tyres, clamped to [0.002, 0.030]. Absent means tarmac, 0.005 |
| `lat` | float | no | 0.11. The rider's latitude on your course, WGS 84 degrees. Only with `lon` |
| `lon` | float | no | 0.11. The rider's longitude on your course, WGS 84 degrees. Only with `lat` |
| `altM` | float | no | 0.11. The rider's altitude, metres above sea level, -500 to 9000. May be sent without `lat`/`lon` |
| `courseGradient` | float | no | 0.14. The real grade of the course here, rise over run, for display only and never in the force. -1 to 1, refused outside it. Absent means the phone shows `gradient`. See [run practice](#4c-run-practice-014) |

**The surface, and why it is the one road parameter the phone takes.** Drafting and wind change
how fast the rider goes, and a companion that owns speed can put them into the speed it reports.
A gravel road changes how hard the pedals are, and no speed the companion reports will make a
trainer push back harder - so the surface has to cross the socket. For a gravel bike: tarmac
0.005, compacted gravel 0.009, mixed gravel 0.012, loose gravel 0.017, grass 0.016, with road
tyres losing about half as much again. At 85 kg and 25 km/h, tarmac to mixed gravel is some
fifty watts. It goes into the same force the gradient does, and it does not touch the gradient the phone displays. Omit it and the road is
tarmac; a frame without it is a frame that has stopped describing a surface, not one asking for
the last surface to be held. Past the deadman it ramps back to tarmac over the same 2000 ms the
gradient flattens over.

`distanceM` and `ascentM` are cumulative rather than per-frame deltas so that a dropped frame
costs nothing and a reconnecting companion resynchronises by stating the total. They must be
monotonically non-decreasing. **They are how a game ride gets a real distance and elevation
profile into the FIT export instead of a flat-road estimate.** From 0.11 the phone also records
`distanceM` every second, so each second of the export covers the ground you said it did.

**`lat`, `lon` and `altM` are where (0.11), and they are how a game ride gets its track.** The
phone keeps the newest of them and, once a second while a ride is recorded, writes it beside
power, cadence and heart rate. The ride's FIT file carries them as each record's
`position_lat`, `position_long`, `altitude` and `enhanced_altitude`, and its TCX file as each
trackpoint's `Position` and `AltitudeMeters`, so Intervals.icu, Strava and the rest draw the
road the ride was on. Nothing is worked out on the phone:

- A position is recorded only inside the same 1500 ms lease the road is ridden on. Go quiet and
  the seconds after are recorded with no position, a gap in the track, never a rider standing
  at the last place you mentioned or one placed between two reports.
- `lat` and `lon` are one value. Send both or neither; one without the other is refused.
- Out of range (latitude beyond ±90, longitude beyond ±180, altitude outside -500 to 9000 m,
  or not a number) is **refused, not clamped**: a coordinate pulled back inside the range is a
  place the rider never was. Each is a `rejected` warning, once per field per connection, and
  the rest of the frame still lands, an altitude included when only the place was wrong.
- Nothing is recorded while the ride is [untracked](#untracked-riding-and-setpower).

Send them on every `world` frame at your frame rate, from the same simulation tick as `speedMps`
and `distanceM`, so all three describe the same instant. Precision is yours to choose: six
decimals of a degree is about ten centimetres, more than any map draws.

`gradient` is clamped to ±0.30 on arrival, matching route mode. `speedMps` is clamped to
0 to 30. A clamp raises a `warning`, not an error.

#### `requestShift`

Ask the phone to shift. The companion has no shifting logic of its own: it does not know the
drivetrain's ratios or limits, and it does not decide what a shift means.

```jsonc
{ "t": "requestShift", "steps": 1 }
```

`steps` is signed: positive is a harder gear. The phone clamps through
`Drivetrain.clampIndex()` and answers with a `gearChanged` event whether or not the index
moved, so a companion can give the rider a "no gears left" cue.

Rate-limited to 10 per second. Excess requests are dropped with a `warning`, never an error: a
held gamepad button must not kill the connection.

During a [run](#4c-run-practice-014) there is nothing to shift: a `requestShift` is dropped with
a `rejected` warning, once per connection, and no `gearChanged` answers it. A run
[held in ERG](#a-run-held-in-erg-015) (0.15) takes it as `steps` x 5 W on its power target
instead, with no warning.

#### `label`

Name the ride. May be sent at any time; the last value before `finish` wins.

```jsonc
{ "t": "label", "name": "Stelvio, north side", "world": "Alpine Tour" }
```

Without this, the ride is saved as "Companion ride", which makes a history of them useless.
`course` sets the same name and more besides, so a companion that sends one does not need this.

#### `course`

The road the rider is on, so a companion ride can be recorded like a route ride. Requires the
`course` capability.

```jsonc
{ "t": "course", "name": "Volcano Circuit", "world": "Watopia",
  "distanceM": 12400, "ascentM": 180,
  "shape": "_p~iF~ps|U_ulLnnqC…", "profile": [412, 418, 431, …] }
```

**Why this exists.** A route ride's record can be thin because its route is not: the session
names a course in the app's catalogue, and the post-ride card, the share image and the history
entry all go and fetch the GPX. A companion ride has no catalogue entry to fetch - the road
lived in your game, and once the socket closed there was nothing left of it - so it saved as
numbers with no shape. This is the course travelling with the ride instead.

**Send it whenever you like, as often as you like. Fields merge.** A frame that omits a field
leaves whatever was already known alone, so the usual shape is a `name` early, while the ride is
still happening and the HUD can show it, and a `shape` at the end, which is the only order a
free-roaming game can manage: it does not know what road the rider took until they have taken
it.

| Field | Type | Notes |
|---|---|---|
| `name` | string | What to call the ride. Becomes the saved session's name, and the ride's `virtual_route` in its FIT file |
| `world` | string | The game or world it was in, shown beside the name |
| `distanceM` | number | The **course's** length. Not how far the rider got - that is `world.distanceM` |
| `ascentM` | number | The course's own total climb, on the same footing |
| `shape` | string | Precision-5 encoded polyline, the same encoding as `RoutePolyline`. Max 32 768 characters |
| `profile` | int[] | Elevation in whole metres, evenly spaced along `shape`. A sample with a fraction is rounded to the metre. 64 samples is plenty. Max 1 024 |
| `mode` | string | `"course"` or `"freeRide"` (0.12). **Present, the frame replaces the ride's description instead of merging.** Absent means a course and merges, as before |
| `area` | object | Free ride only: `{ "id", "name", "release" }`, the area being ridden |
| `sport` | string | 0.14, only with `mode`: `"run"` for [run practice](#4c-run-practice-014), `"ride"` or absent for a bike. Sent only to a phone listing `run` |

**A `course` with `mode` (0.12) is the whole description of a ride, sent at its start.** It
replaces what the phone knew rather than merging into it, so a free ride after a course does not
inherit the course's length. That is also why a free ride sends `distanceM` and `ascentM` as
explicit `null`: a phone older than 0.12 merges, and a `null` is how it is told the course's
length is not this ride's. A `mode`-carrying `course` **always describes the next ride or the
one under way, never one already finished**: a finished ride is amended only by
`finish.summary` or by a `course` without `mode`. The phone otherwise tells a course for the
ride just saved from one for the next by its name, and every free ride in an area is called
after the area. It is also where a ride's [junction](#junction) `seq` starts again.

```jsonc
{ "t": "course", "mode": "freeRide", "name": "München - Altstadt", "world": "Col de Berg",
  "area": { "id": "muenchen-altstadt", "name": "München - Altstadt", "release": "2026.11.1" },
  "distanceM": null, "ascentM": null }
```

`shape` and `profile` describe the course, never a position on it. Where on that road the rider
is travels separately, second by second, as the `world` frame's `lat`, `lon` and `altM` (0.11),
and only if you send them. A companion that would rather disclose nothing sends
none of this and the ride saves exactly as before: real numbers, no shape.

An over-long `shape` or `profile` is **refused whole, not truncated**, with a `rejected`
warning. Half a polyline is not a shorter road, it is one that leaves the map. The rest of the
frame still lands, so a companion that sends an enormous track alongside a perfectly good name
still gets the name.

A field that cannot be read at all, a `distanceM` of `"3.4 km"` or a `profile` with a `null` in
it, is **left out with a `rejected` warning naming it**, and the rest of the frame lands the same
way. `course` and `finish` are the only frames taken in part like this. They describe the ride
rather than steer it, and refusing one whole would close the socket in the middle of a ride.
Every other frame that cannot be read is `malformed`.

#### `setState`

Pause or resume the recording on the phone. Requires the `setState` capability.

```jsonc
{ "t": "setState", "state": "paused" }
```

`state` is `"active"`, `"paused"` or, with the `untracked` capability,
[`"untracked"`](#untracked-riding-and-setpower). Anything else is refused with a `rejected`
warning rather than guessed at: reading a misspelt word as "paused" would silently stop
recording a ride the rider is still doing, and they would find out afterwards.

**`"active"` is also how a ride starts.** The app prepares the ride the moment you connect and
begins streaming telemetry immediately, but the clock does not run, nothing is written down,
and the trainer is not driven at all until this frame arrives: the engine that applies the road
starts with the ride. Send it when your world says the ride has begun: past the menus, off the
start line, whenever that is for you. If you want the rider to feel a road before then, ask for
`"untracked"`.

There is deliberately **no countdown**. Your game has its own idea of when a ride begins and the
app cannot see it; two countdowns fighting each other is worse than either. The app counts a
rider in only for the modes it starts itself.

Deliberately the same frame as resume, because the question a companion is answering is always
"should the ride be running now", and it should not have to know whether the app thinks of this
as a start or a continuation.

A game that has its own pause - a menu, a loading screen, a rider who stopped - should use
`"paused"`, or the ride record quietly accumulates minutes of zeroes nobody rode.

At most two `setState` frames a second are acted on; a third within the second is dropped with a
`rateLimited` warning. See [the limits](#limits-a-companion-has-to-live-within).

**This is a request, and it can legitimately do nothing.** A ride that has not started cannot be
paused, and one already paused cannot be paused again. The `stateChanged` event is the answer,
and it is broadcast for rider-initiated pauses too - the phone has its own pause button, a
stop-pedalling watchdog, and a foreground service that can force one - so a companion should
follow `stateChanged` rather than assume its own request took effect.

#### Untracked riding, and `setPower`

Sometimes the rider is working and none of it belongs in the activity. They stall on a climb
they cannot ride and push the bike to the top; they warm up before your start line; they spin
through your menus; they cool down after the finish. `"active"` would record all of it, and
`"paused"` stops the ride and drops the trainer to 0 W, leaving them grinding against whatever
the machine's minimum brake happens to be.

```jsonc
{ "t": "setState", "state": "untracked", "label": "Pushing the bike" }
{ "t": "setPower", "watts": 125 }
```

**Untracked is active with the record switched off.** Telemetry streams, the engine drives the
trainer from your `world` frames exactly as it does while active, the lease runs, and the phone
says "Not recording" with your `label` beside it. What stops is everything that writes the ride
down: the clock holds, no sample is kept, and `distanceM` / `ascentM` sent while untracked are
neither banked nor remembered as a maximum. **Keep untracked travel out of the totals you send
afterwards.** Exports show the stretch the way they show a pause, with the timer stopped.

It is allowed before the ride starts, which is how a warm-up works: `elapsedS` stays at 0 and
the first `"active"` is still the beginning of the ride. The same goes for the ride the app
prepares after a `finish`, which is where a cool-down goes. `finish` from an untracked ride
that never started saves nothing.

`setPower` holds the trainer in ERG at a wattage, and `"watts": null` hands it back to the
road. It is **honoured only while the ride is untracked** - a power target may never shape a
ride that is being recorded - and it is cleared whenever the ride leaves that state, for any
reason, so send it again after every `stateChanged: "untracked"`. Values are clamped to
`welcome.limits.powerWatts`; a frame with no `watts` at all is malformed.

Two rules protect the rider, and neither depends on your game behaving well. **The target
rides on the `world` lease**: stop sending frames and the commanded wattage decays to zero over
the same ramp a gradient does, so a game that hangs cannot leave a rider pinned at 300 W. And
**a rider who stops pedalling is let go**: ERG raises the brake as the cranks slow, so below
20 rpm for two seconds the phone commands 0 W, and takes the target back over three seconds
once they are turning again. `telemetry.powerTarget` stays put through both; only
`powerCommanded` moves.

The full rules, including what the app does on a disconnect, are in
`skills/indoorbike-companion/references/protocol.md`, sections 7b.1 and 7b.2.

#### `pairRequest`

Ask to be remembered, so neither person ever types a code at each other again. Requires the
`pair` capability.

```jsonc
{ "t": "pairRequest", "name": "Alpine Climb" }
```

Send it once, after an ordinary code-authenticated handshake, and only when your own user has
asked for it. The rider is then asked on the phone. Agreeing on both devices is the whole
ceremony.

The answer is a `paired` frame with a key, or a `pairDeclined` frame. Declining is **not an
error**: the ride carries on exactly as it was, and you should offer to ask again next time
rather than treating it as a failure.

You must have sent a `deviceId` in your `hello`, or there is nothing to remember you by.

#### `pairProof`

Answer a `challenge`. See [reconnecting](#reconnecting-without-a-code).

#### `drivetrain`

The bike the rider is pretending to be on. Optional, and requires the `drivetrain` capability.

```jsonc
{ "t": "drivetrain",
  "name": "Race bike",
  "chainrings": [50, 34],
  "sprockets": [11, 12, 13, 14, 15, 17, 19, 21, 23, 25, 28, 32],
  "wheelCircumferenceMm": 2105 }
```

Most companions will never send this. Leaving it out gives the rider the drivetrain they picked
in the app's own settings, which is a reasonable bike and the one they chose. Send it when the
*game* has an opinion: a mountain stage that hands out a 32-tooth granny ring, a track session
on a fixed gear, a bike the rider unlocked.

| Field | Type | Notes |
|---|---|---|
| `chainrings` | int[] | Teeth. One to three of them, each 20 to 70. Order does not matter |
| `sprockets` | int[] | Teeth on the cassette. One to sixteen, each 9 to 60. Order does not matter |
| `wheelCircumferenceMm` | int | Rolling circumference with the tyre on. Absent means 2105, a 700x25. 900 to 2500 |
| `name` | string | What to call it on the phone. Absent builds one from the teeth |

**1x, 2x and 3x is the length of `chainrings`.** There is no separate field saying which,
because a field like that can disagree with the list beside it and then somebody has to decide
which one is lying.

**It is refused whole rather than repaired.** A drivetrain describes a physical object, so a
zero-tooth sprocket is a bug rather than a preference: quietly rounding it up would hide the bug
and hand the rider a bike nobody designed. The `rejected` warning names the offending number and
the drivetrain the rider already had stays.

**The ladder you get back is not the list you sent.** Combinations within 2.5 % of one already
in the ladder are dropped, so a 2x with fifty-odd chainring and sprocket pairings comes back as
about twenty distinct gears. The app answers with a `drivetrainChanged` event carrying the whole
resulting ladder and the gear the rider is now in. Draw your gear display from that, not from
what you sent.

Send it during setup, before `setState: "active"`. It is accepted later too, and a rider whose
bike changes mid-ride is kept in the nearest gear the new ladder has to the one they were
turning, rather than thrown into a different one. Nothing here is persisted: the rider's own
setting is untouched underneath and comes back when the ride ends.

A [run](#4c-run-practice-014) uses this frame for its one gear, and the rider's own bike also
comes back at the next `course` with `mode` that is not a run, so a bike ride after a run never
inherits the runner's gear.

#### `controls`

A controllable screen is up, or has gone away. Requires the `controls` capability.

```jsonc
{ "t": "controls", "visible": true,
  "title": "Choose a route", "hint": "Pick a lap count",
  "enabled": ["up", "down", "enter", "back"] }
```

**Why this exists.** Your game is on a television across the room; the rider is on a bike with a
phone on the bars. When you put a menu up, they have no way to answer it. This lends them the
phone as a remote: while `visible` is true the app draws a control pad, and every press comes
back as a `controlPressed` event.

| Field | Type | Notes |
|---|---|---|
| `visible` | bool | Whether a controllable screen is up. When false, every other field is ignored |
| `title` | string | What the screen is. Shown above the pad. Trimmed to 48 characters |
| `hint` | string | One line of help, shown under the title. Same limit |
| `enabled` | string[] | Which controls will answer. Any of `up`, `down`, `left`, `right`, `enter`, `back` |

**`enabled` is the whole point.** The app is driving a screen it cannot see, and has no idea
whether what is up is a list of four things, a yes/no dialog, or a loading state that ignores
everything. Controls you do not list are drawn **visibly dead** rather than hidden, so a rider
never presses something that looks live and gets nothing, and the pad never reshuffles under a
thumb that is already moving toward it.

An unrecognised name is refused with a `rejected` warning naming it, and the rest of the frame
still lands. Send a fresh frame whenever what is selectable changes; the last one wins. Send
`visible: false` when the screen is dismissed, or the rider is left holding a pad for a menu
that is not there.

Nothing here reaches the trainer, the physics or the ride record. It is a remote control that
happens to share a socket with a bicycle.

#### `brand`

How your companion looks on the phone. Requires the `brand` capability. Controller only.

```jsonc
{ "t": "brand",
  "name": "Col de Berg",
  "icon": "iVBORw0KGgoAAAANSUhEUgAAAQAAAAEA...",
  "colors": { "accent": "#E4572E", "onAccent": "#FFFFFF", "header": "#3B1F2B" } }
```

**Why this exists.** The rider has two screens in front of them, yours and the phone on the
bars, and until 0.8 nothing on the phone said they belonged together. Send this and the phone's
ride header takes on your identity: your icon, your name, your colours. It is the visual
handshake between the two ends.

| Field | Type | Notes |
|---|---|---|
| `name` | string | Shown on the header in place of `hello.client.name`, and credited with the ride in its exported file (below). Trimmed to 40 characters |
| `icon` | string | A **PNG**, base64. Square, 48 to 512 px, at most 96 000 characters. 256 px with transparency is a good choice |
| `colors.accent` | `#RRGGBB` | Required whenever `colors` is sent. Your main brand colour |
| `colors.onAccent` | `#RRGGBB` | Text and glyphs drawn on the accent. Absent picks light or dark, whichever reads |
| `colors.header` | `#RRGGBB` | The header band. Absent is derived from the accent |

**Fields merge, like `course`.** A field you leave out keeps what it had; a field sent as `null`
clears it. So send everything once after `welcome`, and change the colours later (a night stage,
a different team) without sending the picture again. Everything you declared is kept for the
whole session, including while you are disconnected, so the "disconnected" panel still looks
like you. A *different* companion taking over does not inherit it.

**What it styles, and what it does not.**

| On the phone | Styled | Why |
|---|---|---|
| Ride header band | `header`, with a line of `accent` along its bottom edge, running up under the system bar | The header is the one place that says what is driving the ride |
| Icon on the header | `icon`, or a monogram of `name` on `accent` if there is no icon | Identity at a glance, and a header that does not change shape when the icon arrives |
| Name and course on the header | `name`, then your `course` name and world underneath | |
| Shift paddles, while pressed | `accent` fill, `onAccent` glyph | A press lighting up in your colour is where the phone most obviously feels like part of the game. The idle paddle stays the app's |
| Remote control pad, while pressed | `accent` fill, `onAccent` glyph | It is your menu, borrowed. Which buttons are live stays the app's call |
| Disconnected and session-ended panel | `icon` and an `accent` line | It should still look like the thing that left |
| Power, heart-rate and gradient colours | **Never** | They carry meaning, and the rider has learnt them in every other mode |
| Warnings, errors, the "saved" notice | **Never** | Amber has to keep meaning "look at this" |
| Back, pause, end, the app's navigation | **Never** | The rider's way out is not the game's to decorate |
| The pairing dialog | **Never** | It is where the rider decides whether to trust you. The thing asking does not get to dress the question |
| The ride's share card | `name` and `icon`, as a credit beside the app's own | See below |

**Your name and icon outlive the connection, on one card.** A ride driven by a companion keeps
that companion's name and icon on the phone, filed under the ride, and the share card a rider
makes of that ride credits you on it: your icon and name beside the app's own wordmark, with
"with" in front. Your icon is drawn exactly as you sent it, because a game's mark is its own.
Your colours are not used there: the card is the app's, and this is a credit rather than a
takeover. Nothing else is kept, nothing leaves the phone by itself, and a rider who never makes
a card never sends it anywhere.

**Colours are adjusted, not refused, and you are told.** The phone is a dark screen, glanced at
from a saddle. The app checks each colour against its own surfaces:

- `accent` must reach **2.5:1** against the app's card colour (`#3A435E`). Below that it is
  lightened until it does. That is deliberately under WCAG's 3:1: the accent only fills large
  areas, where hue does work that a luminance ratio cannot see, and 3:1 would lighten an
  ordinary brand red. A navy or a deep purple, which really do vanish on blue-grey, still get
  corrected.
- `onAccent` must reach **4.5:1** on the accent, or it is replaced with light or dark text,
  whichever reads better.
- `header` always carries the app's light text (`#F2F4F7`), so it must reach **4.5:1** against
  it, and is darkened until it does.

Every change arrives as an `adjusted` warning with the value actually drawn, so a colour that
looks different on the phone is never a mystery:

```jsonc
{ "t": "warning", "code": "adjusted", "field": "colors.accent",
  "message": "#1B2A5A was too dark to see on the app's dark surface; lightened to reach 2.5:1. The phone is using #828AA4." }
```

**Your name credits the ride, too.** The phone keeps it with every ride you drive, and writes
it into the ride's FIT file as the session developer field `virtual_platform`, beside the
course's `name` as `virtual_route`, with `sub_sport` set to `virtual_activity`. An upload to
Intervals.icu is named `"<name>: <course>"` and described as "Ridden in <name> on Indoor Bike".
Services that read rides, VertMatch among them, credit the game by this exact string, so keep
it the same from one version to the next. A companion that sends no `brand` is credited by
`hello.client.name`.

**A bad field costs that field, not the frame.** An icon that is not a PNG, not square, out of
range or not valid base64 is refused with a `rejected` warning that says which, and the name and
colours still land. `colors` without a valid `accent` is refused whole and the previous colours
are kept. The size check reads the PNG header rather than decoding it, so a small file claiming
to be forty thousand pixels wide is turned away before anything tries to draw it.

**Why a PNG in the frame, and not a URL.** The app makes no requests to the internet on the
rider's behalf and has to work on a network that has none. An image travels on the socket that
is already open. Keep it small: it shares that socket with the road.

Nothing here is stored. It lives for the session and is gone when the rider leaves Companion
mode. It is not written to the ride record or to history.

#### `finish`

End the ride. Companion mode has no route, so nothing else can know when it is over.

```jsonc
{ "t": "finish",
  "summary": { "shape": "_p~iF~ps|U_ulLnnqC…", "profile": [412, 418, 431, …] } }
```

`summary` is optional and requires the `summary` capability. It is the short form of `course`
and is merged into exactly the same place, so the two are interchangeable and a companion needs
only one of them. Use `summary` if the road is the only thing you have to say and you only know
it at the end; use `course` if you also want to name the ride or hand over the course's own
length and climb.

When present, the phone stores it against the session and renders it on the post-ride card and
the share image via the existing
[RouteSummaryPanel](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/simulation/RouteSummaryPanel.kt),
which is the same panel a route ride uses. The ordering is fixed: the course reaches the ride
record before the ride is told to end, because ending it is what writes the row.

| Field | Type | Notes |
|---|---|---|
| `shape` | string | Precision-5 encoded polyline, the same encoding as `RoutePolyline` |
| `profile` | int[] | 64 elevation samples in metres, the same shape as `RouteEntry.profile`. Fractions are rounded |

On a free ride (0.12) both describe the line actually ridden rather than a course known
beforehand, so the shape may cross itself and the profile runs over the distance ridden. The
shape is the same. A `finish` also clears the junction card and the navigation display, and the
next ride's junctions start again from `seq` 0.

**A `finish` always ends the ride.** If its `summary`, or a field inside it, cannot be read, that
part is left out with a `rejected` warning whose `field` names it (`summary`, or
`summary.profile`), and the ride ends and saves without it. Ending the ride is the part that
matters, and a missing map is not worth a lost ride. Builds up to 2.2 refused the whole frame
instead: the socket closed with 4008, the ride never ended, and the rider's next ride was saved
on top of it under the new name.

**This is the only frame that may carry geography, and it arrives after the ride is over.**
During the ride the phone still knows nothing about where the rider was. A companion that
would rather not disclose even this simply omits `summary`.

#### `screenshot`

A picture of your screen, in answer to a [`requestScreenshot`](#requestscreenshot). Declare the
`screenshot` capability in `hello` and the rider gets a camera button in the phone's ride header;
without it the phone never asks. Controller only.

```jsonc
{ "t": "screenshot", "id": 3, "image": "/9j/4AAQSkZJRgABAQAAAQABAAD..." }
{ "t": "screenshot", "id": 4, "error": "The game is on a loading screen" }
```

| Field | Type | Notes |
|---|---|---|
| `id` | integer | The request's `id`, echoed. Required |
| `image` | string | A JPEG or a PNG, base64. Each edge 16 to 4 096 px. At most 6 000 000 base64 characters |
| `error` | string | Instead of `image`: why you cannot take one, in words the rider can be shown. Up to 200 characters |

**Take what the rider is looking at.** The rendered frame of your main view, scaled so its longest
edge is at most the request's `maxEdgePx`. A JPEG at quality 85 is a few hundred kilobytes and is
the right choice for a game frame; a PNG of the same frame can be ten times that. There is no
format field: the phone reads the first bytes, so nothing can disagree with the picture.

**Keep the road coming while you encode.** Encoding a frame can take longer than the deadman's
1.5 seconds on a slow machine if you do it on the thread that sends `world`. Do it off that
thread, or at least send `world` before and after.

**Only when asked, and once per request.** A `screenshot` with an `id` the phone did not ask for,
or one already answered, is refused with a `rejected` warning on field `id`: the rider decides
when a picture is taken, not the game. An image that is not a JPEG or PNG, is not valid base64,
or is too big is refused with `rejected` on field `image`, and the rider is told it could not be
kept. The size is read from the image header, never by decoding it.

**The phone waits 15 seconds**, then tells the rider no screenshot came. A picture that arrives
later is still kept. Send `error` rather than nothing when you cannot take one: silence reads to
the rider as the game ignoring them.

**Where it goes.** The picture is kept on the phone with the ride that was underway when the
rider asked, even if that ride has ended by the time it arrives. It shows on the ride's summary,
in History and on the session panel, and a tap opens it full screen. It is not exported, uploaded
or synced, and deleting the ride deletes it.

#### `ping`

`{ "t": "ping", "id": 17 }`. Answered with `pong`. Optional; the WebSocket's own ping/pong is
usually enough.

### Server to client

#### `welcome`

Sent once, in response to `hello`. Everything a companion needs to configure itself.

```jsonc
{ "t": "welcome", "proto": "0.1", "app": "2.1.0", "sessionId": "3f9a1c",
  "tickMs": 250,
  "honours": ["gradient", "speedMps", "distanceM", "ascentM", "crr"],
  "capabilities": ["requestShift", "label", "summary"],
  "role": "controller",

  "rider":   { "massKg": 78.0, "ftpWatts": 250 },
  "bike":    { "massKg": 9.5, "drivetrain": "road-double" },
  "gears":   [ { "i": 0, "ratio": 1.21, "label": "34/28", "loadFactor": 0.72 },
               { "i": 1, "ratio": 1.36, "label": "34/25", "loadFactor": 0.79 } ],
  "gearIndex": 6,

  "trainer": { "name": "KICKR CORE", "connected": true, "control": "sim" },

  "limits":  { "gradient": [-0.30, 0.30], "speedMps": [0.0, 30.0], "crr": [0.002, 0.030] },

  "resume":  { "distanceM": 12840.0, "ascentM": 310.5 }
}
```

Three parts of this are worth calling out:

- **`honours`** is what this build actually acts on, as distinct from what the protocol
  defines. Check it. A field absent from `honours` will be ignored and warned about.
- **`gears`** is the whole drivetrain, with real teeth, so the companion can render a genuine
  gear display ("34/17") rather than an index. `loadFactor` is the multiplier applied to the
  road force, should the companion want to show effort rather than teeth.
- **`resume`** is non-null when a crashed ride is being recovered, and tells the companion how
  far the record already believes the rider has come, so it can place them accordingly.

#### `paired` and `pairDeclined`

```jsonc
{ "t": "paired", "deviceId": "<the app's id>", "name": "Indoor Bike", "key": "<64 hex chars>" }
{ "t": "pairDeclined", "reason": "The rider declined." }
```

Store `key` against `deviceId` and use it for every future connection. The app has already
stored its half.

#### `challenge`

```jsonc
{ "t": "challenge", "deviceId": "<the app's id>", "nonce": "<32 hex chars>" }
```

Sent instead of a `welcome` when your `hello` carried a `deviceId` the app has a pairing for.
`deviceId` is the *app's* identity, so you know which of your stored keys to use.

#### `telemetry`

Pushed at the phone's tick rate (`tickMs`, currently 250 ms). Interpolate for rendering rather
than asking for more: the physics only moves four times a second, and a faster stream would be
inventing data.

```jsonc
{ "t": "telemetry", "seq": 1043, "tMono": 128500, "state": "active",
  "power": 243, "cadence": 88, "hr": 152, "speedMps": 8.33,
  "gearIndex": 11, "loadFactor": 1.18,
  "appliedGradient": 0.062,
  "trainerForceN": 96.4, "trainerGradePercent": 11.7,
  "elapsedS": 512 }

```

| Field | Notes |
|---|---|
| `seq` | Increments by one. A gap means frames were lost |
| `tMono` | Milliseconds since ride start, monotonic, immune to clock adjustment |
| `power`, `cadence` | From the trainer. Omitted if not reporting |
| `hr` | From the strap. Omitted if none |
| `speedMps` | **The bike's speed as the phone models it. Use this as the bike's speed.** From the rider's legs while pulling away, carried by the bike's mass after. Omitted until the trainer has reported cadence |
| `gearIndex`, `loadFactor` | The current gear |
| `appliedGradient` | What the phone is actually using, **after clamping**. May differ from what was sent |
| `trainerForceN` | Newtons at the wheel, after gear multiplication and the soft ceiling |
| `trainerGradePercent` | The grade actually written over FTMS. Differs from `appliedGradient` because it encodes a force against an assumed 84 kg |
| `powerTarget` | The wattage a [`setPower`](#untracked-riding-and-setpower) asked for, after clamping. Present only while one is held |
| `powerCommanded` | What the trainer is being told right now. Differs from `powerTarget` while the lease decays it or the rider has been let go. Present only while a target is held |

While a power target is held the road is not being applied, so `appliedGradient`,
`trainerForceN` and `trainerGradePercent` report `0.0` and `speedMps` may be omitted.

`trainerForceN` and `trainerGradePercent` look like internals and are published on purpose.
They are literally "what the trainer is doing", and without them nobody implementing against
this can tell a protocol bug from a physics disagreement.

**`speedMps` deserves its own paragraph, because getting it wrong is the most common way a
companion feels broken.** It is the phone's model of the bike's speed, built from two halves
that are each wrong where the other is right:

- **Pulling away, it is the rider's legs.** A chain does not slip, so below walking pace the
  speed is `cadence x development`, and it is there on the first turn of the crank. A companion
  that integrates speed out of power from a standing start is modelling a bicycle that is
  genuinely stationary, and the rider watches watts and cadence arrive while the speed sits at
  zero for several seconds.
- **Once moving, it has the bike's mass in it.** It is integrated from the rider's power on the
  road you described, `m . dv/dt = P/v - F_road`, so it changes as fast as a bicycle can and no
  faster.

The second half is why it is no longer `cadence x development` all the way up. Right after a
shift the legs are still turning at the old cadence, so that multiplies them by the *new* gear:
on an upshift it reported the bike one sprocket faster, instantly, for nothing. A companion that
rode its world at it sent that speed straight back, the phone evaluated the air at it, and the
rider met the cube of the gear ratio rather than the ratio: on the flat at 30 km/h, 45 % more
force for one sprocket where a bicycle asks for 15 %. Riders felt it as a wall on every upshift.
A speed with mass in it cannot jump, so a companion that echoes it cannot turn a shift into one.
Nothing about using it changed: a companion written against the old meaning is already using
the new one.

You still own the world. Take `speedMps`, apply whatever your game adds on top - drafting,
surfaces, wind, a downhill that carries the rider past what their gear could turn - and send the
result back as `world.speedMps`. Integrate with mass if you add to it; adding a speed that
jumps puts the same wall back.

It is **omitted, not zeroed**, when the trainer cannot count cadence. There is then no
drivetrain speed to be had, and echoing your own number back at you would look like agreement
and mean nothing. Do not read an absent field as a standstill: fall back to your own model.

#### `event`

```jsonc
{ "t": "event", "event": "gearChanged", "index": 12, "changed": true, "source": "zwiftClick" }
{ "t": "event", "event": "controlPressed", "control": "enter" }
{ "t": "event", "event": "drivetrainChanged", "index": 6, "gears": [ … ] }
{ "t": "event", "event": "riderInput", "input": "plus" }
{ "t": "event", "event": "stateChanged", "state": "paused", "reason": "autoPause" }
{ "t": "event", "event": "worldTimeout", "fallback": "flat" }
{ "t": "event", "event": "worldResumed" }
{ "t": "event", "event": "trainerLost" }
{ "t": "event", "event": "controlRevoked", "by": "rider" }
{ "t": "event", "event": "powerTargetChanged", "watts": 125, "source": "companion" }
{ "t": "event", "event": "turnChosen", "junctionId": "j-4471-1", "optionId": "a" }
{ "t": "event", "event": "placesQuery", "requestId": "q7", "query": "markt", "limit": 20 }
{ "t": "event", "event": "destinationChosen", "placeId": "p-viktualienmarkt" }
{ "t": "event", "event": "destinationCleared" }
```

The last four are 0.12's, sent only to a controller that listed `routeChoice` or `navigation`;
see [free ride](#4b-free-ride-route-choice-and-navigation-012).

`powerTargetChanged` says what the requested power target is now, with `source` naming who
changed it: `companion` for your own `setPower`, `state` for one the ride's own state cleared.
**An absent `watts` means released**, since the app omits a field it has no value for rather
than writing `null`. The lease decay and the release of a stopped rider are not changes to what
was asked for and never appear here; watch `telemetry.powerCommanded` for those.

`gearChanged` carries `source`: `zwiftClick`, `screen` or `companion`. It is emitted on every
shift, including one that changed nothing (`changed: false`), so a companion can distinguish
"shifted" from "already in the hardest gear". Telemetry also carries the gear, but the event
arrives at once rather than up to 250 ms later, which matters for a shift sound.

`riderInput` forwards controller presses the phone did not consume as shifts, so a companion
can bind them to its own actions.

#### `requestScreenshot`

The rider tapped the camera button. Sent only to a controller whose `hello` listed the
`screenshot` capability, only while a ride is underway, and one at a time.

```jsonc
{ "t": "requestScreenshot", "id": 3, "maxEdgePx": 1920 }
```

| Field | Type | Notes |
|---|---|---|
| `id` | integer | Unique on this connection. Echo it in your [`screenshot`](#screenshot) |
| `maxEdgePx` | integer | Scale so the longest edge is at most this. The phone accepts up to 4 096 |

#### `warning`

Non-fatal. Something was ignored, clamped or rate-limited, and the sender should know.

```jsonc
{ "t": "warning", "code": "fieldNotHonoured", "field": "cda",
  "message": "This build ignores 'cda'; drag uses the app's default CdA of 0.36. See welcome.honours." }
{ "t": "warning", "code": "clamped", "field": "gradient",
  "sent": 0.42, "applied": 0.30 }
{ "t": "warning", "code": "rateLimited", "frame": "requestShift" }
```

`fieldNotHonoured` is emitted **once per field per connection**, not per frame.

| Code | Meaning |
|---|---|
| `fieldNotHonoured` | A field this build ignores. Once per field per connection |
| `clamped` | A value was held inside its range; `sent` and `applied` say how far |
| `rateLimited` | A frame arrived faster than the app acts on it |
| `rejected` | A value was dropped rather than trimmed, because trimming would be worse than nothing, or a field of `course` or `finish` could not be read. `field` names it, dotted when nested (`summary.profile`). The frame's other fields still applied |
| `adjusted` | A value was changed to keep the screen legible, and the message names the value used. The frame took effect (0.8) |

#### `error`

Fatal for the current operation, and usually followed by a close. See the close-code table in
[Failing meaningfully](#failing-meaningfully).

| Code | Meaning |
|---|---|
| `protoUnsupported` | No overlapping protocol version |
| `unauthorized` | Bad or missing token |
| `controllerBusy` | Another controller holds the session |
| `notController` | An observer sent a controlling frame |
| `noTrainer` | No trainer connected |
| `malformed` | Frame could not be parsed. Followed by 4008 |

## 4a. Permanent pairing

### The problem it solves

The pairing code is the right ceremony for a stranger and the wrong one for the machine in the
same room the rider has already trusted twenty times. It is also per ride, so the code and the
port are different every time and there is nothing for a companion to remember.

Pairing fixes that. After one agreement on both devices, a companion that finds the phone
connects straight into the ride: no code, no prompt, nothing to tap.

### Pairing, once

1. Connect and hand over the code as usual. Send a `deviceId` in your `hello`.
2. Ask your own user whether they want this. Do not send `pairRequest` on your own initiative.
3. Send `pairRequest`.
4. The rider is asked on the phone and accepts.
5. You receive `paired` with a key. Store it against the app's `deviceId`.

### Reconnecting without a code

1. Find the phone as usual, over the beacon or mDNS.
2. Send `hello` with your `deviceId` and **no token**.
3. The app answers `challenge` with its own `deviceId` and a fresh `nonce`.
4. Look up your key for that `deviceId`, generate your own fresh nonce, and answer:

```jsonc
{ "t": "pairProof", "nonce": "<your 32 hex chars>", "proof": "<64 hex chars>" }
```

where

```
proof = HMAC_SHA256(key, "indoorbike-companion-v1:companion:" + appNonce + ":" + yourNonce)
```

expressed as lowercase hex, and `key` is the **raw bytes** the hex in `paired` decodes to.

5. A correct proof is answered with the ordinary `welcome`, carrying `proof` of the app's own:

```
HMAC_SHA256(key, "indoorbike-companion-v1:app:" + appNonce + ":" + yourNonce)
```

**Check it.** Skipping that leaves you trusting whatever answered on the expected port, which is
the position pairing exists to get out of.

### Rules that matter

- **Your nonce must be fresh and random every connection.** Both nonces go into both proofs, so
  a reused one is you signing something only the app chose, which is most of the way back to a
  recording being useful. 16 bytes. The app refuses anything that is not 32 hex characters.
- **`deviceId` is not a credential.** It says which pairing to look up; the proof decides
  everything. Generate one once, keep it forever, never reuse one between two companions.
- **Keep the key somewhere private** and never log it. It authenticates you to somebody's
  trainer.
- **A forgotten pairing falls back, it does not fail shut.** Companion mode lists every paired
  companion and the rider can forget one at any time, with no confirmation step. When they do,
  your `hello` gets the ordinary unauthorized answer and they can pair again with the code.
  Handle that rather than assuming a stored key works forever.
- **Everything is lowercase hex.** No base64, no padding rules, no alphabet variants.

### What it does not do

Encrypt anything. See [the security model](#security-model-stated-plainly).

## 4b. Free ride: route choice and navigation (0.12)

A free ride lets the rider ride through an area of the game's world, choosing a turn at every
junction from the phone, and ask to be taken somewhere. Specified with Col de Berg, whose own
account of the game's side is `docs/companion-route-choice.md` in that repository.

- **Free ride.** A [`course`](#course) with `"mode": "freeRide"` starts one: no length or climb
  known in advance, and a `finish` summary describing the road actually ridden. It is recorded
  like any other ride, track and all.
- **Route choice.** The game describes the next junction in a [`junction`](#junction) frame; the
  phone draws it and sends the rider's pick back as a `turnChosen` event.
- **Navigation.** The phone asks the game for places with `placesQuery`, sets one with
  `destinationChosen`, and is kept told with [`navigation`](#navigation) frames.

### Negotiation

| Capability | In `hello` (the game) | In `welcome` (the phone) |
|---|---|---|
| `routeChoice` | Sends `junction`, acts on `turnChosen` | Draws the junction card and sends `turnChosen` |
| `navigation` | Sends `navigation` and `places`, acts on `placesQuery`, `destinationChosen`, `destinationCleared` | Offers search and destinations |

**Per build, and both ends must list one for it to be used.** Android lists both; the iOS and
Sailfish builds speak 0.12 and list neither yet. The game sends route-choice frames only to a
phone listing `routeChoice` and navigation frames only to one listing `navigation`, branching on
the capability, never the version. Without them the game steers the free ride itself, from its
own keys and pad or automatically. The phone ignores these frames, silently, from a game that
did not list the capability, and never sends the events to one.

Courses are unchanged: a ride with `"mode": "course"`, or no mode, never sees any of this.

### `junction`

Game to phone. The next junction where the rider has a choice; a junction with one way on is
never sent.

```jsonc
{ "t": "junction", "junctionId": "j-4471-1", "seq": 12, "phase": "open",
  "distanceM": 342.0, "commitInM": 132.0, "selectedOptionId": "b", "selectedBy": "default",
  "options": [
    { "optionId": "a", "bearingDeg": -84, "kind": "left", "roadName": "Rumfordstraße",
      "roadClass": "residential", "surface": "asphalt", "gradientAheadPct": 0.4,
      "climbAheadM": 2, "toNextJunctionM": 180, "isDefault": false, "isNavigation": false },
    { "optionId": "b", "bearingDeg": 3, "kind": "straight", "roadName": "Reichenbachstraße",
      "roadClass": "tertiary", "surface": "asphalt", "gradientAheadPct": -0.2,
      "climbAheadM": 0, "toNextJunctionM": 260, "leadsTo": "Isar",
      "isDefault": true, "isNavigation": false } ] }
```

Sent once the junction is within the decision range (typically 300-450 m), again whenever
anything in it changes, at least once a second while `open`, and at most twice a second. Not
sent periodically while the ride is paused; the last frame stays on screen.

| Field | Type | Notes |
|---|---|---|
| `junctionId` | string | Stable for this junction on this ride. A new id replaces the card |
| `seq` | integer | Increases with every `junction` frame of a ride. A lower one than already shown is stale and dropped. **Starts again with each ride**: the phone forgets it at `finish` and at a `course` that carries `mode` |
| `phase` | string | `open`: a choice can still be made. `committed`: final. `passed`: the phone clears the card |
| `distanceM` | number | Metres along the ride to the junction, when sent |
| `commitInM` | number | Metres until the choice is final; 0 once committed |
| `options` | array | 2 to 6, left to right as the rider sees them |
| `selectedOptionId` | string | What the ride will take |
| `selectedBy` | string | `default`, `rider` or `navigation` |

Each option: `optionId`, `bearingDeg` (relative to the rider's heading, -180..180, negative to
the left), `kind` (`sharpLeft` .. `sharpRight`), optional `roadName`, `roadClass` (as
OpenStreetMap's `highway`), `surface`, optional `trailGrade` (S0-S6 as 0-6), `gradientAheadPct`,
`climbAheadM`, `toNextJunctionM`, optional `leadsTo`, `isDefault` (exactly one) and
`isNavigation` (at most one, only while navigating).

**What the phone checks.** The shape, not the routing: every option the game offers is legal
and leads on, and that is the game's guarantee. A frame with fewer than 2 or more than 6
options, a missing or duplicated `optionId`, no default or two, two navigation options, a
`selectedOptionId` that names none of them, or an unknown `phase` or `selectedBy`, is **refused
whole and the last good card kept**, with a `rejected` warning naming the field, sent once per
field per connection. A missing field costs the card, never the socket. Text over 48 characters
is trimmed. An unknown `kind` is drawn from `bearingDeg` alone.

**What the phone draws.** One row of buttons in the order sent, a thumb-sized target each, with
an arrow at the option's own bearing, the road's name, `leadsTo`, and the gradient and surface
when they are worth saying. The filled button is `selectedOptionId`, always as the game last
said it. A `passed` frame clears the card it describes and no other.

**The junction diagram (0.13).** Three optional fields let the card draw the junction the game
draws on its own tile and over the road:

| Field | Type | Notes |
|---|---|---|
| `options[].points` | `[[x, y], ...]` | The first ~40 m of the way out, in metres: x to the rider's right, y ahead, the junction at the origin. At most 16 points, to 0.1 m |
| `approachPoints` | `[[x, y], ...]` | The road in, the same way, every y below zero |
| `commitInS` | number | Seconds to the commit at the current speed, shown as "Choose within N s" |

When every option has at least two points the card draws the diagram above the buttons: the
road in, each way out, the selected one bold. Points that cannot be drawn (more than 16, not an
x and a y, or more than 200 m out) cost the diagram, not the card, with a `rejected` warning on
`options.points` once per connection; the arrows at `bearingDeg`, which stays required, are
drawn as before. A `junction` is read field by field like `course`, so a mistyped field costs
the card, never the socket.

**Lifecycle (0.13, the game's side).** One card at a time, always the next junction the rider
will reach, `open` then `committed` then `passed`, and `passed` always sent, including when the
game's own junction tile goes away for any other reason (quitting to its menu, a ride ended
without `finish`). The phone clears the card on that `passed`, on `finish`, on a `course` with
`mode`, and when the controller goes; nothing else clears it, pause included.

### `turnChosen`

Phone to game, when the rider taps an option:

```jsonc
{ "t": "event", "event": "turnChosen", "junctionId": "j-4471-1", "optionId": "a" }
```

**Not optimistic.** The tap changes nothing on the phone; the game answers an open junction
with a `junction` frame selecting the option (`selectedBy: "rider"`, or `"navigation"` for the
navigation option), and that is what fills the button. After the commit point the game answers
with the current frame instead, so the card shows what will really happen. A tap while the ride
is paused is sent too, and answered the same way. The phone sends one only for the card on
screen while it is `open`, and at most two a second.

### `navigation`

Game to phone. Where the ride is taking the rider.

```jsonc
{ "t": "navigation", "active": true,
  "destination": { "placeId": "p-viktualienmarkt", "name": "Viktualienmarkt" },
  "distanceM": 2140, "etaS": 371,
  "next": { "distanceM": 342, "kind": "left", "roadName": "Rumfordstraße" },
  "arrived": false }
```

Sent when a destination is set, changed or cleared, when the route changes, at each
instruction, and at least every 5 s while navigating; at most once a second. `active: false`
clears the phone's display and carries nothing else. `arrived` is true once, on arrival; the
phone shows it for a few seconds, once per destination, and the game sends `active: false`
shortly after. `etaS` is absent while the rider is stopped.

### `placesQuery` and `places`

```jsonc
{ "t": "event", "event": "placesQuery", "requestId": "q7", "query": "", "limit": 50,
  "category": "monuments" }
```

```jsonc
{ "t": "places", "requestId": "q7", "places": [
  { "placeId": "p-siegestor", "name": "Siegestor", "kind": "monument", "category": "monuments",
    "distanceM": 2140, "bearingDeg": -35 } ] }
```

The phone holds nothing of the area: every list is the game's answer, ranked by riding distance
from where the rider is now. **Since 0.13 the phone browses rather than searches**: it always
sends an empty `query` (places worth going to nearby, never streets) and `limit: 50`, and a rider
narrows the list by category instead of typing. Riders found a search field hard to use with a
hand on the bars. Requests are kept at most two a second, and only the answer to the last one
asked is shown; an older `requestId` is dropped. A place with no `placeId` or `name` is left out.
`kind` is OpenStreetMap's word, shown under the name.

**Categories (0.13).** A game that lists `placeCategories` in `hello.capabilities` puts every
place in exactly one of these, in `places[].category`, and answers a `placesQuery.category`
with that category alone (absent means every kind). There are never more than four; the phone
owns the words and the icons.

| `category` | What is in it |
|---|---|
| `squares` | Squares, marketplaces, village and suburb centres |
| `monuments` | Monuments, castles, churches and other places of worship, museums, attractions, fountains, stations |
| `food` | Restaurants, cafés, ice cream, beer gardens, pubs |
| `nature` | Parks, gardens, water, beaches, peaks, viewpoints, waterfalls |

That is all four, and there will be no fifth. To a game without `placeCategories` the phone sends no category
and shows one list of everything nearby.

### `destinationChosen` and `destinationCleared`

```jsonc
{ "t": "event", "event": "destinationChosen", "placeId": "p-viktualienmarkt" }
{ "t": "event", "event": "destinationCleared" }
```

The game routes from the next junction it can still choose at, including one already open, and
answers with `navigation`; the next `junction` marks the option on the route `isNavigation` and
selects it unless the rider already chose. An unknown `placeId` is answered `active: false`.
`destinationCleared` drops it and the game steers by its default again. The two share an
allowance of two a second.

### Limits

| Frame | Direction | Most per second |
|---|---|---|
| `junction` | game to phone | 2 |
| `navigation` | game to phone | 1 |
| `places` | game to phone | in answer only |
| `turnChosen` | phone to game | 2 |
| `placesQuery` | phone to game | 2 |
| `destinationChosen`, `destinationCleared` | phone to game | 2 together |

Every text field is at most 48 characters, trimmed by the game and again by the phone.

## 4c. Run practice (0.14)

A run is a trail runner on the game's course, driven by the rider pedalling an ordinary smart
trainer: no treadmill, no foot pod. It is for runners learning a race course and for aerobic
cross-training before one. Specified with Col de Berg, whose K110 Innsbruck course is the first.

The game owns the runner. It turns watts into the runner's speed with its own model (Minetti's
metabolic cost per grade), and decides what the pedals should feel. The phone keeps everything
else exactly as for a bike ride: power, cadence and heart rate in telemetry, the deadman, the
recording, `setState`, untracked riding and `setPower`.

### Negotiation

| Capability | In `hello` (the game) | In `welcome` (the phone) |
|---|---|---|
| `run` | Sends `course.sport` | Rides, shows and records a run as below |

Per build, and both ends must list it, as for [free ride](#negotiation). The Android app lists
it; iOS and Sailfish speak 0.14 without it. To a phone that does not list it the game sends no
`sport`, and its one-gear `drivetrain` is the whole fallback: the rider gets the runner's gear
and a bike's display.

### Starting a run

`sport` travels on a `course` that carries a `mode`, because a sport is part of the whole
description of a ride. It is not a mode of its own: K110 is a `course`, and a free run on trails
would be a `freeRide` with the same `sport`.

```jsonc
{ "t": "course", "mode": "course", "sport": "run",
  "name": "K110 run practice: Innsbruck to Birgitz", "world": "Col de Berg",
  "distanceM": 23400, "ascentM": 1180, "shape": "...", "profile": [574, 590, ...] }
{ "t": "drivetrain", "name": "Runner", "chainrings": [42], "sprockets": [17],
  "wheelCircumferenceMm": 2105 }
{ "t": "setState", "state": "active" }
```

- `"run"` is a run. `"ride"` or no `sport` is a bike ride, as before. Any other value is a bike
  ride, with a `rejected` warning naming `sport` once per connection.
- A `course` without `mode` never changes the sport.
- The sport changes only between rides: a different sport is a new ride, a `course` with `mode`.
- `name` is the ride's title, as for any ride: it becomes the saved ride's name and its FIT
  `virtual_route`. There is no separate activity name.
- Send the one-gear `drivetrain` after the `course`, for each run. At the next `course` with
  `mode` that is not a run, the phone puts the rider's own bike back.

### What the phone does differently

**No shifting.** The one gear is the only gear. The phone drops shifts from every source (its
screen, Zwift Click and other controllers, the keyboard) and answers a `requestShift` with a
`rejected` warning once per connection. There is no `gearChanged` during a run.

**The road is a pure load.** The game sets the load with `world.gradient`: a base load plus a
scaled share of the course's grade, clamped so that a 30 % wall is still turnable on one gear.
For a run the phone commands exactly

```
F = m . g . (gradient + crr) / sqrt(1 + gradient^2) x loadFactor
```

and nothing else: no air drag, no acceleration term, no launch assist, no freewheel. `m` is
`welcome.rider.massKg` plus `welcome.bike.massKg`, `crr` is `world.crr` (0.005 when absent) and
`loadFactor` is the one gear's, as `drivetrainChanged.gears[0].loadFactor` reports it. What the
phone keeps from a bike ride: `gradient` clamped to ±0.30 on arrival, the force compressed
towards 265 N, and the deadman ramping the load to flat after 1.5 s of silence.

The watts a force costs depend on the rider's real gear on the trainer, which neither end
knows. In the assumed real gear (3.35 m a crank turn) 90 rpm is 5.0 m/s at the wheel, so a
`gradient` of 0.02 at 87 kg and a factor of 1.55 is 33 N, about 165 W. Tune by feel and from
the power in telemetry. With 87 kg and that gear the force reaches the ceiling at about 0.19,
so keep the load below about 0.18 for it to stay linear.

**Speed is the runner's.** `world.speedMps` is the runner's speed and is what the phone shows,
as pace in min/km. `telemetry.speedMps`, cadence times the one gear, is a bike's speed and means
nothing for a run; it is still sent, and a game ignores it.

**The grade shown is the course's.** `world.courseGradient` is the real, smoothed grade under the
runner. The phone shows it instead of `gradient`, which is a load rather than a grade, and never
puts it into the force. Send it on every `world` frame from the same tick as `gradient`; a frame
without it is shown its `gradient`. A value outside -1 to 1 is refused with a `rejected` warning
once per connection. It is honoured in any sport, so a bike game can use it as well.

### A run held in ERG (0.15)

Where both ends list `runPower`, a run is ridden in ERG: the trainer holds a power target, and
the rider moves it with the buttons that shift on a bike. The game turns the watts into the
runner's speed as before, and the rider's cadence into the runner's steps.

| Capability | In `hello` (the game) | In `welcome` (the phone) |
|---|---|---|
| `runPower` | Sends the run's opening `setPower`, takes `powerTargetChanged` | Holds a run in ERG; + and - move the target |

Only with `run` at both ends as well. The Android app lists both; to a phone or a game without
`runPower` a run is the 0.14 load above, unchanged.

**One target, the run's.** The game sends `setPower` once at the start of each run, after the
`course` and the one-gear `drivetrain`: the endurance watts it already paces runs at. It is
honoured in any state (`active`, `paused`, `untracked`), the one recorded ride a target may
hold. The phone keeps it for the whole run, through pauses, `active` and `untracked` and a
controller reconnecting, and clears it only at `finish`, at the next `course` with `mode`, when
the rider takes back control, and on `setPower` with `null`. So the game does not send it again
after a `stateChanged`: that would undo the rider's own presses. It sends another only when it
changes phase on purpose, such as a warm-up held in `untracked` and then the run itself.

**The rider moves it.** Each press of + or -, on the screen's paddles, on Zwift Click and other
controllers or on the keyboard, moves the target by **5 W**, clamped to 0 to 1000 W. A game's
`requestShift` during the run does the same, `steps` x 5 W, under the same limit of ten a second,
with no warning and no `gearChanged`. Every change is said in a `powerTargetChanged` event with
`watts` and a `source` of `screen`, `zwiftClick`, `companion` or `state`, and every `telemetry`
frame carries `powerTarget`. A press before the game has set a target does nothing.

**Ramps.** The opening target opens from the rider's current power over 2000 ms, as any new
target does. A press ramps from what the trainer is being told at 25 W per half second, so one
5 W step lands in a tenth of a second.

**What the 0.10 hold already does still applies.** A rider at 20 rpm or below for 2 s is let go
and taken back over 3 s once past 30 rpm; a game that goes quiet decays the target to nothing
over 2 s after the 1.5 s lease, so keep sending `world` at 10 Hz. While held, telemetry's
`appliedGradient`, `trainerForceN` and `trainerGradePercent` are 0, `speedMps` is absent and
`powerCommanded` is there. `world.gradient` is still read, for nothing but the record of the
road; `courseGradient` is still what the phone shows.

**Before a target, or without one.** Until the first `setPower`, after `setPower` with `null`,
or where the trainer cannot take a target (no FTMS control point, or no trainer, answered with a
`rejected` warning on `watts`), the run rides the 0.14 load. A game sees this as no
`powerTarget` in telemetry.

**Cadence.** A runner training on a bike should keep the legs turning fast: **80 to 100 rpm**,
160 to 200 steps a minute at two steps a crank turn. The phone shows cadence large during a run,
with that band as a quiet hint rather than an alarm, and the game shows it on its own screen.
Nothing enforces it.

**The record stays a bike ride.** The FIT file says cycling, sub-sport virtual activity, so an
invented pace never lands in a runner's running records or their bests on Strava. Distance,
ascent and the track are the runner's, from `world.distanceM`, `ascentM`, `lat`, `lon` and
`altM` as for any ride. The phone does not estimate a bike's distance or speed from power for a
run, and shows pace rather than speed.

## 5. Units and conventions

| Quantity | Unit |
|---|---|
| Distance, altitude | metres |
| Speed | metres per second, **along the road surface**, not horizontal |
| Gradient | fraction, rise over run |
| Force | newtons |
| Mass | kilograms |
| Power | watts |
| Time | milliseconds for timestamps, seconds for durations |
| Angles | none appear in this protocol |

**Speed and distance are along-road.** This matches what `RideDynamics` integrates, what a
wheel sensor measures, and what a companion advancing along a spline through its world
actually wants. A companion whose world is parameterised horizontally divides by
`sqrt(1 + gradient²)` itself, since it is the end that knows the gradient.

Gradient as a fraction rather than a percentage because that is what `sin`, `atan` and
`RideDynamics` take, and mixing the two is the classic factor-of-100 bug.

## 6. Safety

The companion can make a flywheel very hard to turn while somebody is clipped into it. Most of
the envelope already exists in the shipped route mode and applies unchanged.

**Already built:**

- `softCeiling()` compresses towards `MAX_TRAINER_FORCE_NEWTONS` (265 N, roughly a 32% grade
  at the assumed mass) rather than clipping, so a companion sending nonsense cannot command
  more than a trainer would have refused anyway, and every gear still maps to its own force.
- BLE writes are rate-limited at 200 ms, with a 60 ms floor for shifts and coalescing rather
  than dropping, so a chatty companion cannot saturate the GATT queue and knock out cadence.

**To add for this mode:**

- **Clamp on arrival**, before the value reaches the physics or the display: gradient to
  ±0.30, speed to 0 to 30 m/s. Raise a `clamped` warning.
- **Deadman.** `world` frames carry an implicit lease. After 1.5 s of silence the phone ramps
  the gradient to zero over about 2 seconds, emits `worldTimeout` and shows a banner. Ramping
  rather than stepping because dropping off a 12% climb in one tick is a jolt; the existing
  `FORCE_EPSILON_NEWTONS` and `sendOwed` machinery paces the writes correctly on its own.
  Do **not** implement this as `stop()`: that deliberately leaves the trainer alone, which
  would freeze the last commanded force in place.
- **No companion connected is a valid state.** Flat road, gears still work, ride still records.
  Companion mode degrades to a free ride rather than refusing to start or ending the session.
- **Take back control**, always visible, one tap, drops to a flat road without ending the
  recording.

## 7. Session lifecycle

```
  idle ──select Companion mode──► listening ──rider taps Start──► active
                                      │                            │  ▲
                                      │                     autoPause  │pedalling
                                      │                            ▼  │
                                      │                          paused
                                      │                            │
                                      └──────────┬─────────────────┘
                                                 │ finish (companion) or rider ends
                                                 ▼
                                             finished
```

- **`listening`**: the server is up and advertised, the pairing token is on screen, no ride is
  recording yet. A companion may connect and will receive telemetry with `state: "listening"`.
- **`paused` before the ride has begun**: connected, telemetry flowing, the road being applied,
  and the clock not running. A companion ride sits here until `setState: "active"` arrives. The
  phone says "Ready, start the ride in the game", because pedalling will not start it.
- **`active`**: recording. `world` frames are honoured.
- **`paused`**: not recording. Reached by auto-pause, by the rider's own pause button, by a
  `setState` frame, or by the foreground service forcing one. The existing rule that a
  still-rolling simulated ride counts as active applies unchanged, using the companion's speed.
  The phone collapses its own richer set of states - paused, stopped pedalling, counting down -
  into this one, because the only fact a game can act on is that the recording is not
  advancing.
- **`finished`**: the ride is over and has been written to the rider's history. The socket
  **stays open**, and `paused` follows immediately: the app is ready for another ride.

Every transition between these is broadcast as a `stateChanged` event, whoever caused it. A
companion that only reacted to the pauses it asked for would carry on rolling its world while
the recording sat still.

### A session is many rides (0.7)

`finish` ends a ride. It does not end the connection, and it never did end the evening.

When it arrives the app writes the ride to history immediately, with nobody tapping anything,
and stays where it was: connected, negotiated, streaming telemetry, clock stopped. Send
`setState: "active"` again and the next ride starts. A rider on a bike does not get off it to
confirm a lap, so nothing asks them to.

Between rides the trainer is not being driven, exactly as it is not before the first ride of
a session: no ride is running, so there is no road to hold anybody to. Keep sending `world`
frames anyway. The deadman is still watching, and the gradient you send is the one that takes
effect the instant the next ride starts.

```
setState active  ->  ... riding ...  ->  finish
                                          <- stateChanged finished   (written to history)
                                          <- stateChanged paused     (ready for another)
setState active  ->  ... riding ...  ->  finish
```

Three things follow, and they are the whole of what a companion has to get right:

- **Do not wait for a close.** Before 0.7 the app closed with `4005` on every finish, so a
  companion could treat the socket shutting as its cue. It no longer shuts. A companion that
  waits for it before starting the next ride waits forever.
- **Everything you declared is still yours.** The connection did not restart, so the pairing,
  the negotiated version and your `controls` are all still in place. The *course* is not: it
  described the ride that just ended, and the app clears it. Send a new `course` for the new
  ride.
- **Distance keeps counting if you want it to.** `distanceM` is cumulative and the app no
  longer assumes it restarts. It takes your first report after each `setState: "active"` as
  that ride's origin and records the ride as the distance since. Counting through the whole
  session and restarting per ride both come out right.

**`4005` now means the app has left Companion mode**, which is the only thing that really ends
a session: the rider backed out of the screen. That is the last frame a companion will get.

### What the rider sees

Nothing that interrupts them. There is no summary screen between rides, because the game is
still running and the next lap may start in seconds. The strip at the top of their HUD names
the ride that was just saved and then keeps a count for the rest of the session.

If you send a `course` after the `finish`, which a free-roaming game often has to, the app
folds it into the ride it has already written, so the map still lands. **Unless it names a
different ride**: a `course` between rides whose `name` is not the saved ride's is taken as the
next ride's, sent before you start it, and the saved ride is left exactly as it was. So a late
`course` either carries no `name` or repeats the saved one.

### What is on the ride screen

The power their legs are making, in the largest number the screen will hold, and the two shift
paddles. That is the whole screen, in your colours and under your name.

Everything else you send is still read, still recorded and still in the ride file. It is simply
not repeated on the phone: the speed, the distance, the gradient, the gear and the climb are
already on the big screen a metre in front of the rider, drawn by you, and a second copy on the
handlebars is one more place to look for something they are looking at already. The phone shows
the one thing you cannot: what the rider is actually producing.

**While you are building, turn it all back on.** Companion mode's own screen has **Show every
reading while riding**. With it on, the ride screen is the full set, gradient, gear, cadence,
heart rate, speed, distance and climb, which is how you see what the app made of the frames you
sent. It is off by default, it stays where you left it, and it changes nothing on the wire.

### When the companion goes away

If your socket closes **without a `finish`**, you have stopped driving the ride, not ended it.
So the phone pauses it and waits for you:

- **The ride pauses at once.** Trainer at 0 W, clock stopped, nothing recorded. It is a hard
  pause: a rider turning the pedals does not restart a ride with no game describing the road.
- **The rider is told, plainly.** A panel over their HUD says **Ride paused**, names you as
  disconnected, and lists the rides this session with the paused one among them.
- **There is no timeout.** The phone waits for as long as you are gone. Nothing about a game
  going quiet says the rider is done, so nothing ends the ride on a timer.
- **A socket the phone closed counts the same.** A frame of yours that was `malformed` mid-ride
  gets an `error` and a 4008, and the ride pauses exactly as if you had dropped. The `error`
  names the frame and the field; read it before you reconnect, or you will send the same frame
  again.
- **When you reconnect, the ride carries on by itself.** Same ride, same clock. Your new
  connection receives `stateChanged: active` straight after `welcome`, so you know it is running.
  If you have come back into a menu rather than onto the road, send `setState: "paused"`.
  "Reconnect" means the same companion: a *different* `hello.client.name` finds the ride paused
  and resumes it with `setState: "active"` like any other pause.
- **If you had paused the ride yourself before dropping, it stays paused** when you return. You
  paused it; you resume it.
- **If you are not coming back, the rider taps End ride.** The ride is saved as an ordinary
  completed ride, and the panel becomes the session's summary. The same happens when the rider
  backs out of a live session while you are still connected: you get `finished`, then `4005`.

What this means for you: **send `finish` when a ride ends, every time.** Dropping the socket is
not a way to end a ride; it leaves the rider on a paused screen deciding what you meant. Handle
a reconnect that lands in a running ride, which after a two-second Wi-Fi drop is exactly what
should happen. And send `course` with a name for each ride, or it is listed as "Companion ride".

A ride interrupted by the phone itself (the app killed mid-ride) is offered on the rider's home
screen afterwards as one to save, never to resume, because nothing is left to drive its road.

The ride is a synthetic single-segment `Workout` with a reserved id, following the free-ride
and route-ride precedent, so recording, crash recovery, the unfinished-ride banner, history,
progression and export all work with no new plumbing.

## 8. A worked exchange

```jsonc
// -> client connects to ws://192.168.1.42:47612/companion
{ "t": "hello", "protoMin": "0.1", "protoMax": "0.2", "token": "8F3K2M",
  "client": { "name": "Alpine Climb", "version": "0.3.1", "platform": "windows" },
  "role": "controller", "capabilities": ["summary", "course", "setState"] }

// <- negotiated, configured
{ "t": "welcome", "proto": "0.2", "tickMs": 250,
  "honours": ["gradient", "speedMps", "distanceM", "ascentM", "crr"],
  "capabilities": ["requestShift", "label", "summary", "course", "setState"],
  "gears": [ … ], "gearIndex": 6, "rider": { "massKg": 78.0, "ftpWatts": 250 }, … }

// name the ride now; its shape is only known at the end
{ "t": "course", "name": "Stelvio, north side", "world": "Alpine Tour",
  "distanceM": 24800, "ascentM": 1533 }

// rider taps Start on the phone
// <- { "t": "event", "event": "stateChanged", "state": "active" }

// steady state, roughly 4 Hz each way. speedMps comes back off the telemetry,
// gets the world's own effects applied, and goes out again.
// <- { "t": "telemetry", "seq": 1043, "tMono": 128500, "state": "active",
//      "power": 243, "cadence": 88, "hr": 152, "speedMps": 7.42, "gearIndex": 11,
//      "appliedGradient": 0.062, "trainerForceN": 96.4, … }
{ "t": "world", "seq": 412, "gradient": 0.062, "speedMps": 7.42,
  "distanceM": 8412.5, "ascentM": 214.0 }

// the rider hits a 14% ramp; the game asks for an easier gear
{ "t": "requestShift", "steps": -1 }
// <- { "t": "event", "event": "gearChanged", "index": 10, "changed": true, "source": "companion" }

// the rider reaches for the Click instead
// <- { "t": "event", "event": "gearChanged", "index": 9, "changed": true, "source": "zwiftClick" }

// the game sends a field this build does not act on
{ "t": "world", "seq": 500, "gradient": 0.08, "speedMps": 4.1, "cda": 0.24 }
// <- { "t": "warning", "code": "fieldNotHonoured", "field": "cda",
//      "message": "This build ignores 'cda'; drag uses the app's default CdA of 0.36." }

// the rider opens the game's menu. setState does not stop the deadman, so the
// world frames keep going out.
{ "t": "setState", "state": "paused" }
// <- { "t": "event", "event": "stateChanged", "state": "paused" }
{ "t": "world", "seq": 501, "gradient": 0.08, "speedMps": 0.0 }
{ "t": "setState", "state": "active" }
// <- { "t": "event", "event": "stateChanged", "state": "active" }

// summit, ride over. The shape goes over before the finish, because ending the
// ride is what writes the row.
{ "t": "course", "shape": "_p~iF~ps|U…", "profile": [412, 418, …] }
{ "t": "finish" }
// <- { "t": "event", "event": "stateChanged", "state": "finished" }   // written to history
// <- { "t": "event", "event": "stateChanged", "state": "paused" }     // ready for another

// the socket is still open. Another lap needs nothing but a new course and a start.
{ "t": "course", "name": "Stelvio, south side" }
{ "t": "setState", "state": "active" }
```

## 9. Where it lives in the code

The protocol is pure Kotlin in `shared/commonMain`, with no sockets, no coroutines, no clock of
its own and nothing Android in it. Frames go in with the current time, effects come out. That is
the same discipline [FitWriter](../shared/src/commonMain/kotlin/app/indoorbike/shared/fit/FitWriter.kt)
and the physics already follow, and it is what lets the deadman be tested at a hundred times real
speed with no waiting and no flakiness. It also comes along free when iOS happens.

| File | Contents |
|---|---|
| [`CompanionProtocol.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/companion/CompanionProtocol.kt) | Versions, negotiation and its messages, close codes, every constant and clamp |
| [`CompanionFrames.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/companion/CompanionFrames.kt) | Every frame in both directions |
| [`CompanionCodec.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/companion/CompanionCodec.kt) | JSON in and out, with the tolerance the growth rules require |
| [`CompanionRoad.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/companion/CompanionRoad.kt) | The road, its clamps, sequencing, and the deadman ramp |
| [`CompanionSession.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/companion/CompanionSession.kt) | One connection as a state machine: handshake, roles, warn-once, rate limiting |
| [`CompanionScreenshot.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/companion/CompanionScreenshot.kt) | A screenshot's format and size, read from its header without decoding it |
| [`CompanionRoute.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/companion/CompanionRoute.kt) | Free ride (0.12): the junction card, navigation and places as the phone shows them, and the shape checks |
| [`RunPace.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/companion/RunPace.kt) | Run practice (0.14): a runner's speed as minutes a kilometre, the same on every platform. The run's pure load is `VirtualDrivetrain.pureLoad` |

`CompanionSession.onFrame(text, nowMs)` returns a list of `CompanionEffect`. The transport turns
`Send` and `Close` into WebSocket writes; the ride turns `ApplyRoad`, `Shift`, `SetLabel`,
`SetCourse`, `SetRideState`, `ReportProgress` and `Finish` into calls on the companion engine
and the session manager. The session itself performs none of them, which is why it needs no
device to test.

A course reaches the ride record through the engine, not the host:
[`CompanionCourse`](../shared/src/commonMain/kotlin/app/indoorbike/shared/companion/CompanionCourse.kt)
is merged onto `CompanionEngine.course`, which is where `RideSaver` reads it when the ride is
written, alongside the cumulative distance and ascent that were already kept there.
[`courseTrackOf`](../shared/src/commonMain/kotlin/app/indoorbike/shared/route/CourseTrack.kt)
turns the stored shape and profile back into an ordinary `RouteTrack`, and
[`rideRoute`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ride/RideRoute.kt) is the
one function the post-ride screen and the share sheet ask, so neither has to know whether the
road came from the catalogue or from a game.

The Android side, which is where the sockets and the platform live:

| File | Contents |
|---|---|
| [`CompanionHost.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/companion/CompanionHost.kt) | Ktor CIO WebSocket server, one session per connection, effect dispatch, telemetry ticker |
| [`CompanionAdvertiser.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/companion/CompanionAdvertiser.kt) | mDNS via the platform `NsdManager`, with TXT records |
| [`CompanionPairing.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/companion/CompanionPairing.kt) | Pairing code, local addresses, the QR payload |
| [`CompanionScreen.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/companion/CompanionScreen.kt) | Pairing, then hand-off on the first `world` frame |
| [`CompanionHud.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/companion/CompanionHud.kt) | The HUD, which is the route HUD minus its three "where" elements |
| [`CompanionFreeRide.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/screens/companion/CompanionFreeRide.kt) | The junction card, the navigation strip and the destination search (0.12) |
| [`RideScreenshotStore.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ride/RideScreenshotStore.kt) | Screenshots on disk, one folder per ride, keyed by the ride's start time |
| [`RideScreenshots.kt`](../mobile_clients/android/src/main/kotlin/app/indoorbike/ui/components/RideScreenshots.kt) | The screenshots card, the strip on the session panel, and the full-screen viewer |

And the ride itself: [`CompanionEngine`](../shared/src/commonMain/kotlin/app/indoorbike/shared/companion/CompanionEngine.kt)
commands the trainer from a road it is handed rather than one it reads off a track, reusing the
shipped force ceiling, assumed trainer mass, freewheel floor and BLE pacing constants rather
than copying them. `WorkoutSessionManager.prepareCompanion(ftp)` runs it against a synthetic
workout with a reserved id, so recording, crash recovery, history, progression and export need
no new plumbing.

**No new manifest permission was needed.** `INTERNET` was already declared, inbound sockets
need none, and `NsdManager` needs none. Whether a sustained socket wants the foreground
service typed `dataSync` on Android 14+ is worth confirming on a real device; it was left
alone rather than adopted speculatively, since that type carries a cumulative daily cap on
Android 15.


## 10. Testing it against a real phone

The protocol's unit tests are fast and thorough and they prove one thing: that the state machine
agrees with itself. They cannot tell you that a `controls` frame puts buttons on a tablet, that
`setState: "active"` starts a ride without a countdown, or that a `drivetrain` frame changes the
ladder a rider is shifting through. Only a socket to a real phone does that, and until
[`scripts/companion_probe.py`](../scripts/companion_probe.py) existed the only way to open one
was to build a game.

So the probe is a companion in one file. Standard library only, including a hand-rolled
WebSocket client: pulling in `websockets` would make the one tool for reproducing a bug the one
tool nobody has installed.

```bash
# Prepare a companion ride on the phone, then:
python scripts/companion_probe.py --discover
python scripts/companion_probe.py --host 192.168.86.23 --port 45809
python scripts/companion_probe.py --host 192.168.86.23 --port 45809 --scenario controls
```

`--discover` listens for the UDP beacon on 47601 and takes the phone's address from the
datagram's source, which is also the quickest way to prove discovery works on a given network.
Debug builds pin the pairing code to `AAAAAA`, so `--code` is only needed against a release
build.

The scenarios, in `SCENARIOS` at the bottom of the file:

| Scenario | What it does |
|---|---|
| `smoke` | The whole protocol in the order a real companion would use it: drivetrain (valid, then deliberately invalid), course, world frames, a menu with half the pad dead, the start frame, a hill, shifts, pause and resume, and optionally `finish` |
| `world` | Pumps world frames at a fixed gradient. For feeling a hill with your legs |
| `controls` | Puts a menu up and reports which buttons the rider pressed |
| `pair` | Asks to be remembered, stores the key, and proves the next connection needs no code |
| `brand` | Sends a name, a generated PNG icon and colours, then a deliberately unreadable accent to show the `adjusted` warning, then rides under the brand so it can be looked at |
| `screenshot` | Rides and answers every `requestScreenshot` with a generated PNG. Tap the camera button on the phone. `--screenshot-reply error` answers with a reason, `silent` never answers so the phone's timeout shows |
| `drop` | Rides, drops the socket with no `finish`, stays away for `--menu-seconds` (make it long), reconnects, and checks the ride carried on by itself before finishing it properly |
| `twice` | A whole session down one socket: rides, finishes, checks the ride was written with nothing tapped, rides again with the game's distance counter running straight through, and sends the second course *after* its finish |
| `freeride` | The game's side of 0.12: a free ride at a steady 8 m/s whatever the trainer says, a junction every 600 m offered from 400 m out and committed 200 m before it, taps answered as the spec says, search answered from a few made-up places, and a destination reached 1.5 km after it is chosen. Tap the junction card, search, choose a place and stop navigating on the phone |
| `run` | The game's side of 0.14: a run course and a one-gear `drivetrain`, then a course climbing from 0 to 25 % with the load kept between 0.01 and 0.08 and the force printed beside the one the spec promises, two `requestShift` (one warning, no `gearChanged`), a `courseGradient` of 1.7 (refused), and with `--finish` a bike ride after it that must bring the rider's own bike back. Look for pace and no paddles on the phone |
| `runpower` | The game's side of 0.15: lists `runPower`, starts a run, sends one `setPower` of 180 W, two `requestShift` (190 then 185 W, no warning, no `gearChanged`), pauses and resumes without sending the target again (it must still read 185 W), and with `--finish` ends the run, which clears it. Press the paddles on the phone during it and watch the `powerTargetChanged` events say `screen` |

Add a scenario rather than editing one. The value of the file is that a scenario you ran last
month still runs the same way.

`--godot-numbers` spells every whole number in every frame the probe sends as Godot does,
`921.0`, so any scenario doubles as a check that the phone reads numbers for their value.
`--scenario twice --godot-numbers` is the evening that found it: two rides whose `finish`
carries a profile written that way, which must still save as two rides.

**Two things a desk run shows that are not faults.** With nobody on the trainer,
`telemetry.speedMps` is absent, because no crank has turned and there is therefore no drivetrain
speed to report. And the ride will start on `setState: "active"` and then auto-pause a few
seconds later, because the stop-pedalling watchdog is doing its job. Put a rider on the trainer
to see either of them hold.

**The probe follows its own advice**, and it is worth knowing why: it keeps sending `world`
frames while its menu is up. The first version did not, tripped the deadman within 1.5 seconds,
and produced a `worldTimeout` in its own transcript - which is exactly the mistake this
specification warns companion authors about, committed by the tool written to demonstrate the
specification.

## 11. Known gaps

Deliberately unresolved in 0.1, listed so neither end designs around their absence.

- **Road parameters beyond gradient and surface.** `cda`, `airDensity` and `headwindMps` are
  reserved names and are **not** honoured. They are unnecessary while the companion owns speed,
  since drafting and wind are its own arithmetic. They would only become meaningful if a future
  mode gave speed back to the phone. A companion sending them gets a `fieldNotHonoured`
  warning. `crr` was one of them until a companion needed a gravel road to be felt rather than
  watched, and is now honoured; see `world` above.
- **Steering.** No frame. A companion takes its own input for anything that is not a shift,
  except the turn at a junction of a free ride, which 0.12's `turnChosen` hands to the phone.
- **Free ride on iOS and Sailfish.** Both speak 0.12 but list neither `routeChoice` nor
  `navigation` until they draw a junction card and a search, so a game steers their free rides
  itself.
- **Run practice on iOS and Sailfish.** Both speak 0.14 without listing `run`, so a game sends
  them no `sport`: the rider gets its one gear with a bike's display, shifting and record.
- **Trainer control tiers.** 0.1 assumes an FTMS `sim`-capable trainer, and 0.10 adds ERG for
  a `setPower` target while the ride is untracked, which is the one place a wattage is ever
  commanded on a companion's behalf.
  [LIVE_API.md](LIVE_API.md#1-the-decision-that-shapes-everything-erg-is-the-wrong-control-channel)
  designs `resistance` and `none` fallbacks; they are not in this profile yet, and `welcome`
  reserves `trainer.control` to carry them.
- **Scopes.** [LIVE_API.md](LIVE_API.md#scopes) splits consent into `metrics`, `biometrics`,
  `profile` and `control`. Companion mode needs all four, so 0.1 treats pairing as one
  decision. A read-only observer should get a narrower grant before this is published widely.
- **The live road does not survive a crash, deliberately.** A resumed companion ride sits on
  flat until the companion reconnects and says otherwise, because the app has no idea what hill
  it was on. The *course* does survive: it is in the crash-recovery snapshot, so a ride
  recovered from the unfinished-ride banner still saves with its name and its shape.
- **Progression.** Whether a companion ride counts towards streaks, levels and badges is a
  product decision, open in LIVE_API.md and still open here.
- **The protocol's own home.** LIVE_API.md argues for a separate Apache-2.0 repository holding
  the spec, JSON schemas, conformance tests and reference clients, so that a closed-source
  companion can link a client library without GPL obligations. That argument stands and this
  document should move there once it stabilises.
