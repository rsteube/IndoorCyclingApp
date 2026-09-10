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

Traffic is plaintext on the LAN, authenticated by a pairing token. A device that is already on
the rider's home network and holds a valid token can change the resistance of a trainer that a
person is clipped into. That is the threat model, and it is why control is never implicit:

- The rider starts Companion mode deliberately. Nothing listens otherwise.
- The token is shown on the phone and must be presented by the companion. It is per ride.
- The connected companion's self-declared name and address are always visible on the HUD, with
  a one-tap **Take back control** that drops to a flat road without ending the recording.

Do not run this on a network you do not trust.

### Discovery

mDNS / DNS-SD, service type `_indoorbike._tcp.local.`, via Android's `NsdManager`.

| TXT key | Example | Meaning |
|---|---|---|
| `proto` | `0.1-0.1` | Supported protocol range, `min-max` |
| `path` | `/companion` | WebSocket path |
| `app` | `2.1.0` | App version, informational |
| `session` | `3f9a1c` | Stable id for this ride, for reconnection |
| `mode` | `companion` | Reserved: other modes may advertise later |

The port is ephemeral and published in the SRV record. Do not hardcode one.

**QR fallback**, for networks where mDNS is blocked. The phone renders a payload the companion
can scan or the rider can type:

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

**Current version: 0.3, and this build accepts everything back to 0.1.**

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
| `4005` | Ride ended |
| `4008` | Malformed frame the server could not parse |

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
| `capabilities` | string[] | no | What the companion can do, e.g. `["summary"]` |

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
| `profile` | int[] | Elevation in whole metres, evenly spaced along `shape`. 64 samples is plenty. Max 1 024 |

`shape` and `profile` describe the course, never a position on it, and nothing in the protocol
ever asks where on that road the rider is. A companion that would rather disclose nothing sends
none of this and the ride saves exactly as before: real numbers, no shape.

An over-long `shape` or `profile` is **refused whole, not truncated**, with a `rejected`
warning. Half a polyline is not a shorter road, it is one that leaves the map. The rest of the
frame still lands, so a companion that sends an enormous track alongside a perfectly good name
still gets the name.

#### `setState`

Pause or resume the recording on the phone. Requires the `setState` capability.

```jsonc
{ "t": "setState", "state": "paused" }
```

`state` is `"active"` or `"paused"`. Anything else is refused with a `rejected` warning rather
than guessed at: reading a misspelt word as "paused" would silently stop recording a ride the
rider is still doing, and they would find out afterwards.

A game that has its own pause - a menu, a loading screen, a rider who stopped - should use this,
or the ride record quietly accumulates minutes of zeroes nobody rode.

**This is a request, and it can legitimately do nothing.** A ride that has not started cannot be
paused, and one already paused cannot be paused again. The `stateChanged` event is the answer,
and it is broadcast for rider-initiated pauses too - the phone has its own pause button, a
stop-pedalling watchdog, and a foreground service that can force one - so a companion should
follow `stateChanged` rather than assume its own request took effect.

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
| `profile` | int[] | 64 elevation samples in metres, the same shape as `RouteEntry.profile` |

**This is the only frame that may carry geography, and it arrives after the ride is over.**
During the ride the phone still knows nothing about where the rider was. A companion that
would rather not disclose even this simply omits `summary`.

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
| `malformed` | Frame could not be parsed |

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
- **`active`**: recording. `world` frames are honoured.
- **`paused`**: not recording. Reached by auto-pause, by the rider's own pause button, by a
  `setState` frame, or by the foreground service forcing one. The existing rule that a
  still-rolling simulated ride counts as active applies unchanged, using the companion's speed.
  The phone collapses its own richer set of states - paused, stopped pedalling, counting down -
  into this one, because the only fact a game can act on is that the recording is not
  advancing.
- **`finished`**: the ride is saved. The socket closes with `4005`.

Every transition between these is broadcast as a `stateChanged` event, whoever caused it. A
companion that only reacted to the pauses it asked for would carry on rolling its world while
the recording sat still.

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
// <- { "t": "event", "event": "stateChanged", "state": "finished" }
// <- close 4005 "ride ended"
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


## 10. Known gaps

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
