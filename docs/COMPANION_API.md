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

**The phone never learns the rider's position.** It is told how fast they are going, what the
road is doing under them, and how far they have come in total. Those are enough to command a
trainer and write a ride file, and they are not a location.

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

**The phone owns the drivetrain.** A chain does not slip, so a gear decides how fast the road
goes past for a given cadence - `v = cadence x development` - and the road force is evaluated
at that. A virtual gear that does not decide it is not a gear at all, only a multiplier on a
number somebody else chose, worth a few per cent a shift.

The two are not rivals. A settled rider solves `P = F_road(v) . v` from either end and lands
on the same speed, so they agree wherever the road is steady and part company only where a
bicycle would: mid-acceleration, while coasting, and when a gear cannot turn the road out fast
enough. Telemetry reports the phone's answer as well, so a disagreement is visible rather than
mysterious.

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
  a one-tap **Take back control** that drops to a flat road without ending the recording.

**What is authenticated and what is not.** Pairing establishes who is on the other end. It does
not encrypt anything: traffic is plaintext on the LAN, so anything on the network can read a
rider's watts, and that is as true after pairing as before it. This is a deliberate limit, not
an oversight. Encrypting the stream would mean every companion needing TLS with custom pinning,
which is awkward in a game engine and would price out exactly the people this API exists for.

Do not run this on a network you do not trust.

### Discovery

mDNS / DNS-SD, service type `_indoorbike._tcp.local.`, via Android's `NsdManager`.

| TXT key | Example | Meaning |
|---|---|---|
| `proto` | `0.1-0.9` | Supported protocol range, `min-max` |
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

**Current version: 0.9, and this build accepts everything back to 0.1.**

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
| `capabilities` | string[] | no | What the companion can do, e.g. `["summary"]`. `screenshot` gets the rider a camera button |

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
  "ascentM": 214.0 }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `seq` | int | yes | Monotonic. The phone drops out-of-order frames |
| `gradient` | float | yes | Rise over run. `0.06` is a 6% climb, `-0.06` a descent |
| `speedMps` | float | yes | Virtual speed **along the road surface**. Drives the drag term |
| `distanceM` | float | no | Cumulative along-road distance this ride. For the ride record |
| `ascentM` | float | no | Cumulative metres climbed this ride. For the ride record |

`distanceM` and `ascentM` are cumulative rather than per-frame deltas so that a dropped frame
costs nothing and a reconnecting companion resynchronises by stating the total. They must be
monotonically non-decreasing. **They are how a game ride gets a real distance and elevation
profile into the FIT export instead of a flat-road estimate**, and they are not position: how
far and how much up, never where.

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
| `name` | string | What to call the ride. Becomes the saved session's name |
| `world` | string | The game or world it was in, shown beside the name |
| `distanceM` | number | The **course's** length. Not how far the rider got - that is `world.distanceM` |
| `ascentM` | number | The course's own total climb, on the same footing |
| `shape` | string | Precision-5 encoded polyline, the same encoding as `RoutePolyline`. Max 32 768 characters |
| `profile` | int[] | Elevation in whole metres, evenly spaced along `shape`. A sample with a fraction is rounded to the metre. 64 samples is plenty. Max 1 024 |

`shape` and `profile` describe the course, never a position on it, and nothing in the protocol
ever asks where on that road the rider is. A companion that would rather disclose nothing sends
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

`state` is `"active"` or `"paused"`. Anything else is refused with a `rejected` warning rather
than guessed at: reading a misspelt word as "paused" would silently stop recording a ride the
rider is still doing, and they would find out afterwards.

**`"active"` is also how a ride starts.** The app prepares the ride the moment you connect and
begins streaming telemetry and applying the road immediately, but the clock does not run and
nothing is written down until this frame arrives. Send it when your world says the ride has
begun: past the menus, off the start line, whenever that is for you.

There is deliberately **no countdown**. Your game has its own idea of when a ride begins and the
app cannot see it; two countdowns fighting each other is worse than either. The app counts a
rider in only for the modes it starts itself.

Deliberately the same frame as resume, because the question a companion is answering is always
"should the ride be running now", and it should not have to know whether the app thinks of this
as a start or a continuation.

A game that has its own pause - a menu, a loading screen, a rider who stopped - should use
`"paused"`, or the ride record quietly accumulates minutes of zeroes nobody rode.

**This is a request, and it can legitimately do nothing.** A ride that has not started cannot be
paused, and one already paused cannot be paused again. The `stateChanged` event is the answer,
and it is broadcast for rider-initiated pauses too - the phone has its own pause button, a
stop-pedalling watchdog, and a foreground service that can force one - so a companion should
follow `stateChanged` rather than assume its own request took effect.

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
| `name` | string | Shown on the header in place of `hello.client.name`. Trimmed to 40 characters |
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
  "honours": ["gradient", "speedMps", "distanceM", "ascentM"],
  "capabilities": ["requestShift", "label", "summary"],
  "role": "controller",

  "rider":   { "massKg": 78.0, "ftpWatts": 250 },
  "bike":    { "massKg": 9.5, "drivetrain": "road-double" },
  "gears":   [ { "i": 0, "ratio": 1.21, "label": "34/28", "loadFactor": 0.72 },
               { "i": 1, "ratio": 1.36, "label": "34/25", "loadFactor": 0.79 } ],
  "gearIndex": 6,

  "trainer": { "name": "KICKR CORE", "connected": true, "control": "sim" },

  "limits":  { "gradient": [-0.30, 0.30], "speedMps": [0.0, 30.0] },

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
| `speedMps` | **The drivetrain's own speed. Use this as the bike's speed.** Omitted when the trainer cannot report cadence |
| `gearIndex`, `loadFactor` | The current gear |
| `appliedGradient` | What the phone is actually using, **after clamping**. May differ from what was sent |
| `trainerForceN` | Newtons at the wheel, after gear multiplication and the soft ceiling |
| `trainerGradePercent` | The grade actually written over FTMS. Differs from `appliedGradient` because it encodes a force against an assumed 84 kg |

`trainerForceN` and `trainerGradePercent` look like internals and are published on purpose.
They are literally "what the trainer is doing", and without them nobody implementing against
this can tell a protocol bug from a physics disagreement.

**`speedMps` deserves its own paragraph, because getting it wrong is the most common way a
companion feels broken.** A chain does not slip, so `v = cadence x development` is not an
estimate of the rider's speed, it *is* their speed, and it is known on the first turn of the
crank. A companion that integrates speed out of power instead is solving
`m . dv/dt = P/v - F_road` from a standing start, and that model is not wrong so much as
answering a different question: it describes a bicycle that is genuinely stationary, rather than
one whose cranks are already turning at 80 rpm. The visible symptom is watts and cadence
arriving from the trainer while speed sits at zero for several seconds, at the start of a course
and again every time the rider stops and starts.

You still own the world. Take `speedMps` as the drivetrain's output, apply whatever your game
adds on top - drafting, surfaces, wind, a downhill that carries the rider past what their gear
could turn - and send the result back as `world.speedMps`. The two agree wherever the rider is
settled, because both ends solve `P = F_road(v) . v`, and they are meant to disagree everywhere
else.

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
```

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
folds it into the ride it has already written, so the map still lands.

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
  "honours": ["gradient", "speedMps", "distanceM", "ascentM"],
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

- **Road parameters beyond gradient.** `crr`, `cda`, `airDensity` and `headwindMps` are
  reserved names and are **not** honoured in 0.1. They are unnecessary while the companion owns
  speed, since drafting, surfaces and wind are all its own arithmetic. They would only become
  meaningful if a future mode gave speed back to the phone. A companion sending them gets a
  `fieldNotHonoured` warning.
- **Steering.** No frame. A companion takes its own input for anything that is not a shift.
- **Trainer control tiers.** 0.1 assumes an FTMS `sim`-capable trainer.
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
