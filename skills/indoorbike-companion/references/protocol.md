# Companion API - implementation reference

Exhaustive, machine-readable reference for the Indoor Bike Companion API as **implemented on
the app side**. Written to be read by a model or a developer building the other end.

- Protocol version: **0.11** (the app also accepts everything back to 0.1)
- Transport: WebSocket, JSON text frames, UTF-8, local network only
- Path: `/companion`
- Status: app side implemented; see [Not implemented](#16-not-implemented) for the gaps

Part of the `indoorbike-companion` skill, and the source of truth for companion-side
behaviour. In the Indoor Bike repository it sits at
`skills/indoorbike-companion/references/protocol.md`, beside `docs/COMPANION_API.md` (the prose
specification and rationale) and `docs/LIVE_API.md` (the original design argument).

**This document is normative for behaviour**: where prose and this disagree, the app does what
is written here. If this document and a running app disagree, the app is right - re-copy the
skill from the repository.

Terminology: **app** is Indoor Bike on the phone (the WebSocket *server*). **Companion** is
your program (the WebSocket *client*).

---

## 1. Ownership, stated as rules

| Owned by the companion | Owned by the app |
|---|---|
| The world and the rider's position in it | The BLE link and FTMS quirks |
| Virtual speed | Virtual gears and all shifting logic |
| Gradient under the wheels | The force commanded to the trainer |
| Drag, wind, draft, and what the surface is | The safety envelope, and what a surface costs |
| When the ride ends | Session state, ride record, FIT/TCX export |

Three consequences that are easy to get wrong:

1. **You must send `speedMps` on every `world` frame.** The commanded force contains
   `0.5 * rho * CdA * v^2`. Omitting speed is not "use a default"; the field is required and a
   frame without it is `malformed`.
2. **You cannot shift.** You may *request* a shift. The app owns the drivetrain, clamps the
   index and reports the result.
3. **The app knows where the rider is only if you say.** `distanceM` and `ascentM` are
   cumulative totals for the ride record. From 0.11 you may also send `lat`, `lon` and `altM` on
   the `world` frame, the rider's position on your course; the app records exactly that, second
   by second, into the ride's FIT and TCX files, and never works a position out for itself.

---

## 2. Connection lifecycle

```
discover (mDNS or QR)
  -> open ws://<host>:<port>/companion
  -> send hello                        [required first frame]
  <- welcome                           [or error + close]
  -> label                             [optional, any time]
  -> world  * N                        [4-10 Hz]
  <- telemetry * N                     [4 Hz, starts immediately after welcome]
  <- event * N                         [as they happen]
  <- warning * N                       [non-fatal]
  -> requestShift                      [optional, <=10/s]
  -> finish                            [ends the ride]
  <- event stateChanged finished
  <- close 4005
```

The app prepares the ride the moment a **controller** completes the handshake, not on the
first `world` frame. The pairing screen on the phone switches to the HUD on the first `world`
frame.

---

## 3. Discovery

The phone offers **three** ways to be found, and a companion should support at least the
UDP beacon and the manual path.

### 3.0 UDP broadcast beacon (easiest)

While the socket is open, the phone broadcasts a JSON datagram to UDP port **47601**. This is
the only fixed port in the protocol, and it has to be: a listener cannot discover the port it
should be listening on. Bind to it and read.

```json
{
  "t": "indoorbike.companion",
  "proto": "0.1-0.11",
  "path": "/companion",
  "port": 33077,
  "app": "2.0.2",
  "session": "8aada0",
  "name": "Indoor Bike",
  "paired": false
}
```

| Field | Meaning |
|---|---|
| `t` | Always `indoorbike.companion`. Ignore datagrams without it |
| `proto` | Supported range, so you can rule the phone out before connecting |
| `path` | WebSocket path |
| `port` | The WebSocket port. Ephemeral, which is why this beacon exists |
| `app`, `session` | App version, and an id stable for the life of one ride |
| `name` | What to show in a picker |
| `paired` | True when a controller already holds this ride. Grey it out |

Rules:

- **The phone's address is the datagram's source address.** It is not in the payload, on
  purpose: the phone usually has several and cannot know which one you can reach, whereas the
  source address of a packet that arrived is reachable by construction.
- **The pairing code is never broadcast.** Discovery answers "which phone"; pairing answers
  "may you drive it". The rider still types the six characters or scans the QR.
- Rate is **1 Hz while unpaired, every 5 s once a controller has connected**. Expire an entry
  you have not heard from in ~15 s.
- One datagram per network per tick. If you see doubles, dedupe on source address plus `port`.

### 3.1 mDNS / DNS-SD (standard)

- Service type: `_indoorbike._tcp`
- Service name: `Indoor Bike` (the OS appends a suffix if it collides)
- Port: **ephemeral**, published in the SRV record. Never hardcode a port.

TXT records:

| Key | Example | Meaning |
|---|---|---|
| `proto` | `0.1-0.11` | Supported version range, `min-max` |
| `path` | `/companion` | WebSocket path |
| `app` | `2.0.2` | App version, informational |
| `session` | `3f9a1c` | Stable id for this ride |
| `mode` | `companion` | Reserved for future modes |

Registration is best-effort. Many networks drop multicast, and on those the advertisement
never appears. **A companion must offer a manual path.**

### 3.2 QR payload

The phone renders this string as a QR code:

```
indoorbike://companion?host=192.168.1.42&port=47612&token=8F3K2M&session=3f9a1c
```

All four parameters are always present. Parse it as a URI; do not assume parameter order.

### 3.3 Manual

The pairing screen shows every non-loopback IPv4 address the phone has, each as a full
`ws://<addr>:<port>/companion` URL, plus the 6-character pairing code separately.

The pairing code alphabet is `ABCDEFGHJKLMNPQRTUVWXY2346789` (no `O`, `0`, `I`, `1`, `S`, `5`).
Codes are 6 characters, generated per ride, never stored, never reused.

---

## 4. Handshake

### 4.1 `hello` (companion -> app, required first frame)

```json
{
  "t": "hello",
  "protoMin": "0.1",
  "protoMax": "0.2",
  "token": "8F3K2M",
  "client": { "name": "Alpine Climb", "version": "0.3.1", "platform": "windows" },
  "role": "controller",
  "capabilities": ["summary", "course", "setState", "controls", "drivetrain", "pair"],
  "deviceId": "6f1c2b90-....."
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `protoMin` | string | yes | `major.minor` |
| `protoMax` | string | yes | `major.minor` |
| `token` | string | yes in practice | Compared against the pairing code |
| `client.name` | string | no | Defaults to `Unnamed companion`. Shown on the rider's HUD |
| `client.version` | string | no | |
| `client.platform` | string | no | |
| `role` | string | no | Absent defaults to `controller` |
| `capabilities` | string[] | no | Declares what you can do. The app reads `summary`; send `course`, `setState`, `controls`, `drivetrain`, `pair` and `brand` too if you use them |
| `deviceId` | string | no | Stable, opaque, yours forever. Enables permanent pairing. Not a credential |

**Role resolution, exactly:**

- field absent -> `controller`
- `"controller"` -> `controller`
- **anything else, including unrecognised values -> `observer`**

An explicit value the app cannot interpret must not be read as a request for control. If you
want control, send `"controller"` or omit the field.

### 4.2 `welcome` (app -> companion)

```json
{
  "t": "welcome",
  "proto": "0.2",
  "app": "2.1.0",
  "sessionId": "3f9a1c",
  "tickMs": 250,
  "honours": ["gradient", "speedMps", "distanceM", "ascentM", "crr"],
  "capabilities": ["requestShift", "label", "summary", "course", "setState", "controls",
                   "drivetrain"],
  "role": "controller",
  "rider": { "massKg": 78.0, "ftpWatts": 250 },
  "bike": { "massKg": 9.5, "drivetrain": "road-double" },
  "gears": [
    { "i": 0, "ratio": 1.21, "label": "34 x 28", "loadFactor": 0.72 },
    { "i": 1, "ratio": 1.36, "label": "34 x 25", "loadFactor": 0.79 }
  ],
  "gearIndex": 6,
  "trainer": { "name": "KICKR CORE", "connected": true, "control": "sim" },
  "limits": { "gradient": [-0.3, 0.3], "speedMps": [0.0, 30.0], "crr": [0.002, 0.03] },
  "resume": null
}
```

- `honours` is what **this build acts on** in a `world` frame. Check it. A field not listed is
  ignored and warned about. It is not the same as what the protocol defines.
- `capabilities` is the same idea one level up: which frames beyond the base protocol the app
  will act on. **Branch on this, never on the negotiated version number.**
- `gears` is the full drivetrain in index order. `label` is real teeth (`"34 x 28"`).
  `loadFactor` is the multiplier applied to the road force in that gear.
- `limits` are the clamps. Values outside them are clamped, not rejected.
- `resume` is `null` in the current build. Reserved for crash recovery.
- `tickMs` is the telemetry period. Do not hardcode 250; read it.
- `deviceId` is the *app's* identity. Store your pairing key against it.
- `proof` is present only on a connection let in by a pairing, and you must check it.

### 4.3 Version negotiation

The app picks the highest version both ends list. Both ends must check: the app may choose a
version you listed but have since dropped, so verify `welcome.proto` against your own range
and fail loudly if it is not one you speak.

Rules from 1.0 onward (0.x may break on a minor bump):

- **Minor bump: additive only.** A 1.0 implementation must keep working against a 1.4 peer.
- **Major bump: everything else**, including a changed unit or default.

Version and capability are separate mechanisms. Version answers "same language"; `honours` and
`capabilities` answer "what does this build do". Gaining a road parameter is a capability
change, not a version bump.

A companion that disconnects mid-ride without `finish` leaves the ride **paused**, with no
timeout. The same companion (same `hello.client.name`) reconnecting resumes it automatically and
receives `stateChanged: active` after `welcome`; a ride it had paused itself stays paused. The
rider can end a paused ride, which saves it as completed. This is app behaviour, not a protocol
version.

**0.11** added where the rider is: optional `lat`, `lon` and `altM` on the `world` frame
(section 7). The app records them second by second beside power, with `distanceM`, and writes
them into the ride's FIT and TCX files as its track. Nothing is worked out on the phone: a second
with no fresh report has no position. Additive: listed in `welcome.honours`, and a companion
that sends none rides exactly as before.

**0.10** added untracked riding (section 7b.1) and the `setPower` frame (section 7b.2): a ride
state where the rider works and nothing is written down, and a fixed wattage to work at inside
it. Additive, and gated on the `untracked` and `setPower` capabilities. A companion from before
0.10 is told `paused` for the new state and never sees the new frames.

**0.9** added screenshots (section 8a): the app sends `requestScreenshot` when the rider taps
the camera button, and a companion that listed `screenshot` in `hello` answers with a
`screenshot` frame carrying a JPEG or PNG, kept with the ride. Additive.

**0.8** added the `brand` frame (section 7d): a name, a square PNG icon and three colours for
the phone's ride header, and the `adjusted` warning code. Additive.

**0.7** made `finish` end the ride rather than the connection. The app writes the ride to
history on the spot and stays connected, so a session is many rides down one socket and `4005`
now means the rider left Companion mode. **Not purely additive**: a companion that waited for
the close before starting another ride will wait forever.

**0.6** added permanent pairing: `hello.deviceId`, the `pairRequest` / `paired` ceremony and
the `challenge` / `pairProof` handshake. Optional; ignore all of it and nothing changes.

**0.5** added the `drivetrain` frame. Optional; leaving it out keeps the rider's own setting.

**0.4** moved the moment a ride starts, and is the one change so far that is not purely
additive: nothing is recorded until you send `setState: "active"`, and there is no countdown.
A companion that never sends it never starts a ride.

**0.3** added the `controls` frame and the `controlPressed` event.

**0.2** added `telemetry.speedMps`, the `course` frame and the `setState` frame. All three are
additions, so a 0.1 companion negotiates down and behaves exactly as it always did. Adopt
`speedMps` first: it is why speed used to sit at zero for several seconds after the rider
started pedalling.

### 4.4 Handshake failures

| Condition | Frame | Close |
|---|---|---|
| No overlapping version | `error` `protoUnsupported`, with `serverRange` and `clientRange` | 4001 |
| Unparseable `protoMin`/`protoMax` | `error` `protoUnsupported` | 4001 |
| `protoMin` above `protoMax` | `error` `protoUnsupported` | 4001 |
| Wrong or missing token | `error` `unauthorized` | 4002 |
| A controller already holds the ride | `error` `controllerBusy` | 4003 |
| First frame is not `hello` | `error` `unauthorized` | 4002 |
| A proof that does not match the pairing on file | `error` `unauthorized` | 4002 |
| A nonce that is not 32 hex characters | `error` `unauthorized` | 4002 |
| Anything but `pairProof` while a challenge is outstanding | `error` `unauthorized` | 4002 |
| No completed handshake 10 s after connecting | `error` `unauthorized` | 4002 |
| Before the handshake: a binary frame, or a text frame over 16 KB | `error` `unauthorized` | 4002 |
| A frame nested deeper than 32 levels, at any time | `error` `malformed` | 4008 |
| Already 8 connections, or 4 still in their handshake | none | 1013 |
| An address still waiting after repeated failures | none | 4002, reason says how long |

**Wrong codes cost time.** From the third failed handshake, an address waits 1 s before it may
try again, doubling each time to at most 60 s. After 10 failures in all the pairing code is
replaced and the phone shows the new one. A paired companion never uses a code.

**Keep reading your socket.** The app pings every 5 s and closes a connection that has not
answered for 10 s; any WebSocket library answers pings by itself as long as something is
reading. Each companion has its own outgoing queue: telemetry is skipped while 4 frames are
already waiting, and a frame that cannot be written within 5 s closes the connection. After the
handshake a frame may be up to 8 MB (a `screenshot` needs it).

Names are shown with control characters and bidirectional-text overrides removed.

The app always sends the `error` frame **and then** closes with a code and a reason string.
Handle both: you may close the socket before you read the frame, and then the close reason is
all you get. Error messages name both version ranges and which end to update.

---

## 4.5 Permanent pairing

Optional. Requires the `pair` capability. Added in protocol 0.6.

The pairing code is per ride, so the code and the port are different every time and there is
nothing for you to remember. After one agreement on both devices, you connect straight into the
ride with no code and nothing for anyone to tap.

### 4.5.1 Pairing, once

1. Connect and hand over the code as usual, sending a `deviceId` in your `hello`.
2. **Ask your own user first.** Do not send this on your own initiative.
3. Send `{"t": "pairRequest", "name": "Alpine Climb"}`.
4. The rider is asked on the phone.
5. You receive:

```json
{ "t": "paired", "deviceId": "<the app's id>", "name": "Indoor Bike", "key": "<64 hex chars>" }
```

Store `key` against the app's `deviceId`. Or you receive `{"t": "pairDeclined", "reason": "..."}`,
which is **not an error**: the ride continues, and you should offer to ask again next time.

### 4.5.2 Reconnecting without a code

1. Send `hello` with your `deviceId` and **no token**.
2. The app answers instead of welcoming you:

```json
{ "t": "challenge", "deviceId": "<the app's id>", "nonce": "<32 hex chars>" }
```

3. Look up your key for that `deviceId`, generate a **fresh random 16-byte nonce**, and answer:

```json
{ "t": "pairProof", "nonce": "<your 32 hex chars>", "proof": "<64 hex chars>" }
```

```
proof = hex(HMAC_SHA256(keyBytes, "indoorbike-companion-v1:companion:" + appNonce + ":" + yourNonce))
```

`keyBytes` is the raw bytes the hex in `paired` decodes to. Everything else is the hex strings
exactly as they appear on the wire, joined with colons.

4. The ordinary `welcome` comes back with the app's own proof:

```
hex(HMAC_SHA256(keyBytes, "indoorbike-companion-v1:app:" + appNonce + ":" + yourNonce))
```

**Verify it before you trust the connection.** Skipping it leaves you trusting whatever answered
on the expected port, which is the position pairing exists to get out of.

### 4.5.3 Rules

- **A fresh random nonce every connection.** Both nonces go into both proofs; reusing yours is
  signing something only the app chose. The app refuses anything that is not 32 hex characters.
- **`deviceId` is not a credential.** It selects which pairing to look up; the proof decides.
  Generate one once, keep it forever, never share one between two companions.
- **Keep the key private and never log it.** It authenticates you to somebody's trainer.
- **Fall back, do not fail shut.** The rider can forget you from the paired-companions list on
  the companion screen, at any time and without confirming. Then your `hello` gets an ordinary
  `unauthorized` and the answer is to pair again with the code. A stored key is not forever.
- **Lowercase hex throughout.** No base64.

### 4.5.4 What it does not do

Encrypt anything. The socket is plaintext before and after pairing, so anything on the network
can read the rider's watts. Pairing answers *who*, not *who else can see*.

---

## 5. `world` (companion -> app)

The road. Send at 4-10 Hz. Faster buys nothing: the app applies the newest frame on its next
250 ms tick and rate-limits its own BLE writes below that.

```json
{ "t": "world", "seq": 412, "gradient": 0.062, "speedMps": 7.42, "distanceM": 8412.5, "ascentM": 214.0,
  "crr": 0.0122 }
```

| Field | Type | Required | Range | Notes |
|---|---|---|---|---|
| `seq` | integer | yes | monotonic | Frames with `seq <= last` are **dropped silently** |
| `gradient` | number | yes | clamped to [-0.30, 0.30] | Rise over run. `0.06` = 6% climb |
| `speedMps` | number | yes | clamped to [0.0, 30.0] | Along the **road surface**, not horizontal |
| `distanceM` | number | no | monotonic | Cumulative along-road distance this ride |
| `ascentM` | number | no | monotonic | Cumulative metres climbed this ride |
| `crr` | number | no | clamped to [0.002, 0.030] | Rolling resistance under the tyres. Absent means tarmac (0.005) |
| `lat` | number | no | [-90, 90], refused outside | 0.11. Rider's latitude on your course, WGS 84 degrees. Only with `lon` |
| `lon` | number | no | [-180, 180], refused outside | 0.11. Rider's longitude on your course, WGS 84 degrees. Only with `lat` |
| `altM` | number | no | [-500, 9000], refused outside | 0.11. Rider's altitude in metres above sea level. May come alone |

Behaviour:

- **Clamping** raises a `warning` with `code: "clamped"`, `field`, `sent`, `applied`. The
  clamped value is applied; the frame is not rejected.
- **`NaN` gradient becomes `0.0`** rather than propagating into the physics.
- **`distanceM` / `ascentM` never decrease.** If you send a smaller value than before, the
  larger one is kept. Send cumulative totals, not deltas.
- Omitting `distanceM` / `ascentM` leaves the previously banked totals untouched.
- **Omitting `crr` means tarmac**, not "as you were": a companion that stops describing the
  surface has stopped modelling one. Send it on every frame if you send it at all.
- **`lat` / `lon` / `altM` are the ride's track (0.11).** The app records the newest once a
  second while the ride is recorded, inside the 1500 ms lease: go quiet and those seconds have
  no position, a gap rather than a guess. Exported as FIT `position_lat`, `position_long`,
  `altitude`, `enhanced_altitude` and TCX `Position`, `AltitudeMeters`. `lat` without `lon` (or
  the reverse) and anything out of range is **refused, not clamped**, with a `rejected` warning
  once per field per connection; the rest of the frame still lands. Not recorded while
  untracked. Send them from the same tick as `speedMps` and `distanceM`.
- **`distanceM` is recorded every second too (0.11)**, so each second of the export covers the
  ground you counted, rather than the ride's total shared out by power.
- Sending a field not in `honours` raises `warning` `fieldNotHonoured` **once per field per
  connection**, and the frame is still applied.
- An observer sending `world` gets `error` `notController` and the socket **stays open**.

### 5.1 The deadman

A `world` frame carries an implicit lease of **1500 ms**.

- Age <= 1500 ms: gradient applied as sent.
- Age > 1500 ms: gradient decays **linearly to zero over the next 2000 ms**, then stays at
  zero. Formula: `gradient * (1 - (age - 1500) / 2000)`, clamped at 0.
- **`speedMps` is held, never ramped.** Ramping it too would collapse the drag term and leave
  the rider on a road with no hill and no air in it.
- **`crr` ramps back to tarmac** over the same 2000 ms, by the same formula. A companion that
  goes away mid-sector must not leave the rider grinding through virtual gravel, or drop them
  onto ice.
- Crossing into stale emits `event` `worldTimeout` with `fallback: "flat"`, once.
- The next `world` frame emits `event` `worldResumed`, once.

### 5.2 Surfaces

`crr` is the one road parameter this app acts on, and it is here because it is the one a
companion cannot deliver on its own. Drafting and wind change how fast the rider goes, and a
companion that owns speed can put them into the speed it reports. A gravel road changes how
hard the pedals are, and nothing the companion sends about speed will make a trainer push back
harder - so the surface has to cross the socket.

Figures for a gravel bike: tarmac 0.005, compacted gravel 0.009, mixed gravel 0.012, loose
gravel 0.017, grass 0.016; road tyres lose roughly half as much again on all of them. At 85 kg
and 25 km/h, tarmac to mixed gravel is about fifty watts.

The app applies it as rolling resistance in the same force the gradient goes into, at the
speed the rider's gear is actually turning out. It does **not** change the gradient shown on
the phone, which stays the road's own.

Reserved but **not honoured**: `cda`, `airDensity`, `headwindMps`. They are unnecessary while
you own speed - drafting and wind are your arithmetic and never cross the socket. Sending them
earns a `fieldNotHonoured` warning explaining exactly that.

---

## 6. `requestShift` (companion -> app)

```json
{ "t": "requestShift", "steps": -1 }
```

`steps` is signed; positive is a harder gear. The app clamps to the drivetrain and answers
with a `gearChanged` event **whether or not the index moved**, so you can cue "no gears left".

Rate limit: **10 requests per rolling second**. Excess is dropped with `warning`
`rateLimited`, never an error and never a close - a held gamepad button must not kill the
connection or dump the rider into the top gear.

Observers get `error` `notController`.

---

## 7. `label` (companion -> app)

```json
{ "t": "label", "name": "Stelvio, north side", "world": "Alpine Tour" }
```

Names the ride. May be sent at any time; the last value before `finish` wins. `world` is
optional. The name reaches the saved session, so a history of companion rides reads as a list
of courses rather than twenty rows called "Companion ride".

`course` sets the same name and more besides, so a companion that sends one does not need this.

---

## 7a. `course` (companion -> app)

Requires the `course` capability (`welcome.capabilities`). Added in protocol 0.2.

```json
{
  "t": "course",
  "name": "Volcano Circuit",
  "world": "Watopia",
  "distanceM": 12400,
  "ascentM": 180,
  "shape": "_p~iF~ps|U_ulLnnqC",
  "profile": [412, 418, 431, 447]
}
```

The road the rider is on, so the app can record a companion ride the way it records a route
ride. A route ride's record can be thin because its route is not: the session names a course in
the app's catalogue and the post-ride card, the share image and the history entry all fetch the
GPX. Your course exists nowhere the app can reach, so it travels with the ride.

| Field | Type | Notes |
|---|---|---|
| `name` | string | What to call the ride. Becomes the saved session's name, and the ride's `virtual_route` in its FIT file. Trimmed to 120 chars |
| `world` | string | The game or world, shown beside the name. Trimmed to 120 chars |
| `distanceM` | number | The **course's** length. Not how far the rider got - that is `world.distanceM` |
| `ascentM` | number | The course's own total climb |
| `shape` | string | Precision-5 encoded polyline (Google polyline algorithm). Max 32 768 chars |
| `profile` | int[] | Elevation in whole metres, evenly spaced along `shape`. Fractions are rounded. 64 is plenty. Max 1 024 |

**Every field is optional and fields merge.** A later frame that omits a field leaves whatever
was already known alone. The usual shape is:

```json
{ "t": "course", "name": "Volcano Circuit", "world": "Watopia" }
```

right after `welcome`, so the phone's HUD can show it during the ride, then

```json
{ "t": "course", "shape": "…", "profile": [ … ] }
```

at the end, which is the only order a free-roaming game can manage: you do not know what road
the rider took until they have taken it.

**Nothing here is a position.** It describes the course, never where on it the rider is, and
the app does not work that out from it. Where the rider is travels as the `world` frame's
`lat`, `lon` and `altM` (0.11), and only if you send them. A companion that would rather disclose nothing sends
no `course` at all and the ride saves exactly as before: real numbers, no shape.

An over-long `shape` or `profile` is **refused whole, not truncated**, with a `rejected`
warning. Half a polyline is not a shorter road, it is one that leaves the map. The rest of the
frame still lands, so an enormous track alongside a good name still gets you the name.

A field that cannot be read at all (`"distanceM": "3.4 km"`, a `null` in `profile`) is **left out
with a `rejected` warning naming it**, and the rest of the frame lands. Only `course` and
`finish` are taken in part; every other unreadable frame is `malformed` and closes with 4008.

---

## 7b. `setState` (companion -> app)

Requires the `setState` capability. Added in protocol 0.2.

```json
{ "t": "setState", "state": "paused" }
```

**Starts** the ride the first time, and resumes it after that. The app prepares the ride on
connect and streams telemetry straight away, but the clock does not run, nothing is written
down, and **the trainer is not driven at all** until `"active"` arrives - the engine that
applies the road starts with the ride. (Before 0.10 this section claimed the road was applied
from the moment of connection. It was not, and no companion relied on it. If you want the
rider to feel a road before the ride starts, ask for `"untracked"`.) There is no countdown: your game has its
own idea of when a ride begins and the app cannot see it.

`state` is `"active"`, `"paused"` or, with the `untracked` capability, `"untracked"`; anything
else is refused with a `rejected` warning rather than guessed at, because reading a misspelt
word as "paused" would silently stop recording a ride the rider is still doing.

At most two `setState` frames a second are acted on. Each one writes to the trainer, so a third
within the same second is dropped with a `rateLimited` warning.

Use it for your own pause: a menu, a loading screen, a rider who stopped. Without it the ride
record quietly accumulates minutes of zeroes nobody rode.

**It is a request and it can legitimately do nothing.** A ride that has not started cannot be
paused; one already paused cannot be paused again. The `stateChanged` event is the answer, and
it is broadcast for pauses you did not ask for as well - the rider has their own pause button,
the phone has a stop-pedalling watchdog, and its foreground service can force one. Follow
`stateChanged`; never assume your request took effect.

Note that `setState` does not stop the deadman. Keep sending `world` frames while you are
paused (see [5.1](#51-the-deadman)) or the gradient will ramp to flat under a rider who may
still be turning the pedals.

### 7b.1 `untracked`: riding that is not written down

Requires the `untracked` capability. Added in protocol 0.10.

```json
{ "t": "setState", "state": "untracked", "label": "Pushing the bike" }
```

**Active with the record switched off.** The session runs, telemetry streams, the engine
drives the trainer from your `world` frames exactly as it does while active, and the lease
runs. What stops is everything that writes the ride down. Use it when the rider is working
but the effort is not part of the activity: pushing the bike up a climb they stalled on,
warming up before your start line, spinning in your menus, cooling down after the finish.

| Field | Type | Required | Notes |
|---|---|---|---|
| `label` | string | no | What the rider is doing, shown on the phone beside "Not recording". Trimmed to 48 characters. Ignored with any other state |

What it means for the record:

- The clock stops. `telemetry.elapsedS` holds, and going back to `"active"` continues from
  there exactly as a resume does.
- No sample is kept: no power, cadence or heart rate reaches the ride or its exports.
- **`distanceM` and `ascentM` sent while untracked are not banked**, and do not raise the
  kept maximum either. **Keep untracked travel out of the totals you send afterwards**: send
  the totals you had when the rider went off the record.
- Exports show it the way they show a pause: the timer stopped and started again.
- The stop-pedalling watchdog does not run. Untracked never becomes a standby.
- A crash restores the ride from its last recorded moment, never untracked.
- `finish` while untracked saves what was recorded before it. On a ride that never started it
  saves nothing, like any ride that never started.

**It is allowed before the ride starts**, which is how a warm-up works: the engine starts
driving the trainer, `elapsedS` stays at 0, and the first `"active"` is still the start of the
ride. The ride the app prepares after a `finish` is in the same position, which is where a
cool-down goes.

Things the app does on its own, which you learn about from `stateChanged`:

| What happens | Result |
|---|---|
| The rider presses pause on the phone | `paused`, trainer at 0 W |
| The rider resumes that pause | back to `untracked`, with no power target |
| The controller disconnects, ride started | Held as `paused` at 0 W. The same companion reconnecting brings it back to `untracked` |
| The controller disconnects, ride never started | Nothing is held. The trainer is commanded 0 W and the ride goes back to not-started and paused. Ask for `untracked` again when you return |
| The rider ends the ride on the phone | `finished`, saved |

### 7b.2 `setPower` (companion -> app)

Requires the `setPower` capability. Added in protocol 0.10.

```json
{ "t": "setPower", "watts": 125 }
{ "t": "setPower", "watts": null }
```

Holds the trainer in ERG at a wattage, replacing the road for as long as it lasts. `null`
hands it back to the road in your latest `world` frame.

| Field | Type | Required | Range | Notes |
|---|---|---|---|---|
| `watts` | number or null | **yes** | clamped to `limits.powerWatts`, `[0, 1000]` | Rounded to the nearest watt. A frame with no `watts` at all is **malformed** and closes the socket |

- **Honoured only while the ride is `untracked`.** Anywhere else it is refused with a
  `rejected` warning naming `state`, and nothing changes. A power target may never shape a
  ride that is being recorded.
- Controller only. An observer gets `error notController` and stays connected.
- Out of range is clamped with a `clamped` warning, as a `world` field is.
- **The target is cleared whenever the ride leaves untracked**, for any reason, and every
  entry into untracked starts without one. Send `setPower` again after each
  `stateChanged: "untracked"`.
- Rate limit: 10 a second, as for `requestShift`. The surplus is dropped with `rateLimited`.
- A trainer that cannot take a power target, or no trainer at all, gets a `rejected` warning
  naming `watts`. Nothing is queued: ask again once one is connected.
- Virtual gears do not change an ERG load. `requestShift` is still accepted and still
  answered, so the rider rides on in the gear they left in.

**A new target opens gently.** The commanded wattage starts from what the rider is pushing at
the moment the target is set and reaches the asked-for number over about 2000 ms, because a
trainer handed 250 W while its rider is soft-pedalling at 80 does not negotiate. So for the
first couple of seconds `powerCommanded` reads below `powerTarget`, and that is the ramp rather
than a clamp or a partial refusal. Setting the same target again restarts it.

Two further rules protect the rider, and neither depends on your game behaving well:

- **The target rides on the `world` lease.** Stop sending frames and the commanded wattage
  decays to zero over the same ramp a gradient does, with `worldTimeout` as usual; frames
  returning ramp it back over 2000 ms with `worldResumed`. Keep sending `world` while you
  hold a target.
- **A rider who stops pedalling is let go.** ERG raises the brake as the cranks slow, so a
  stalled rider would be held against the target and unable to restart. Below 20 rpm for two
  seconds the app commands 0 W; past 30 rpm it ramps back to the target over three seconds.
  `powerTarget` stays set throughout, and only `powerCommanded` moves.

---

## 7bb. `drivetrain` (companion -> app)

Optional. Requires the `drivetrain` capability. Added in protocol 0.5.

```json
{
  "t": "drivetrain",
  "name": "Race bike",
  "chainrings": [50, 34],
  "sprockets": [11, 12, 13, 14, 15, 17, 19, 21, 23, 25, 28, 32],
  "wheelCircumferenceMm": 2105
}
```

Puts the rider on the bike your game thinks they are riding. Most companions never send it:
without it the rider keeps the drivetrain they chose in the app's settings.

| Field | Type | Required | Range | Notes |
|---|---|---|---|---|
| `chainrings` | int[] | yes | 1 to 3 entries, 20 to 70 teeth | The count is the 1x / 2x / 3x |
| `sprockets` | int[] | yes | 1 to 16 entries, 9 to 60 teeth | One sprocket is a single-speed, which is a real bike |
| `wheelCircumferenceMm` | int | no | 900 to 2500 | Absent means 2105 |
| `name` | string | no | 48 chars | Absent builds one from the teeth |

Order does not matter in either list; the app sorts and de-duplicates.

**There is no field saying 1x, 2x or 3x.** It is the length of `chainrings`. A separate field
could disagree with the list beside it.

**Invalid specs are refused whole, not repaired.** A drivetrain describes a physical object, so
a 0-tooth sprocket is a bug, not a preference. You get a `rejected` warning naming the offending
number and the rider keeps the bike they had.

**Read the ladder back from `drivetrainChanged`.** Combinations within 2.5 % of one already in
the ladder are dropped, so what comes out is shorter than what went in and only the app knows by
how much. The event carries the whole resulting ladder and the current gear index.

Send it during setup, before `setState: "active"`. Accepted later too: a rider whose bike
changes mid-ride is placed in the nearest gear by development rather than by index, so they keep
turning the ground over at about the rate they were.

Nothing is persisted. The rider's own setting comes back when the ride ends.

---

## 7c. `controls` (companion -> app)

Requires the `controls` capability. Added in protocol 0.3.

```json
{
  "t": "controls",
  "visible": true,
  "title": "Choose a route",
  "hint": "Pick a lap count",
  "enabled": ["up", "down", "enter", "back"]
}
```

Lends the rider's phone to a screen they cannot otherwise answer. While `visible` is true the
app draws a control pad; each press comes back as a
[`controlPressed`](#11-event-app---companion) event.

| Field | Type | Required | Notes |
|---|---|---|---|
| `visible` | bool | yes | When false, every other field is ignored |
| `title` | string | no | Shown above the pad. Trimmed to 48 characters |
| `hint` | string | no | Shown under the title. Same limit |
| `enabled` | string[] | no | Absent or empty means nothing is pressable, which is legitimate for a loading state |

Control names, and the only ones the app knows:

`up`, `down`, `left`, `right`, `enter`, `back`

**Controls you do not enable are drawn dead, not hidden.** The app cannot see your screen, so
it cannot guess what will answer. Hiding the rest would reshuffle the pad every time a menu
changed what was selectable, under a thumb already moving toward a button. Dead-but-present
also distinguishes "the app forgot it" from "the game does not want it".

An unrecognised name gets a `rejected` warning naming it, and the rest of the frame still
lands: a misspelt `entre` costs you the Enter button, not the whole pad.

Send a fresh frame whenever selectability changes; the last wins. Send `visible: false` on
dismissal. The pad is also taken away automatically if you disconnect, because a remote for a
program that is gone is worse than no remote.

Nothing here reaches the trainer, the physics or the ride record.

---

## 7d. `brand` (companion -> app)

Requires the `brand` capability. Added in protocol 0.8. Controller only.

```json
{ "t": "brand",
  "name": "Col de Berg",
  "icon": "iVBORw0KGgoAAAANSUhEUgAAAQAAAAEA...",
  "colors": { "accent": "#E4572E", "onAccent": "#FFFFFF", "header": "#3B1F2B" } }
```

| Field | Type | Rules |
|---|---|---|
| `name` | string | Header name, replaces `hello.client.name` on screen. Trimmed to 40 characters |
| `icon` | string | Base64 PNG. Square, 48 to 512 px, at most 96 000 base64 characters. PNG only |
| `colors.accent` | `#RRGGBB` | Required when `colors` is present |
| `colors.onAccent` | `#RRGGBB` | Optional. Text on the accent |
| `colors.header` | `#RRGGBB` | Optional. Header band; derived from the accent when absent |

**It also credits the ride.** The name is kept with every ride you drive, and written into the
ride's FIT file as the session developer field `virtual_platform` (and the course's `name` as
`virtual_route`), with `sub_sport` set to `virtual_activity`. Uploads to Intervals.icu are named
`"<name>: <course>"` and described as "Ridden in <name> on Indoor Bike". Services such as
VertMatch key their credit, link and logo on this exact string, so keep it stable across
versions. Without a `brand` frame the ride is credited to `hello.client.name`.

**Merge semantics.** Absent field: unchanged. Field sent as `null`: cleared. The merged result
is kept for the session, including while the companion is disconnected; a different companion
connecting (a different `hello.client.name`) clears it.

**Legibility, enforced by the app.** Checked against the app's surfaces:

| Colour | Must reach | Otherwise |
|---|---|---|
| `accent` | 2.5:1 against `#3A435E` | Lightened toward white until it does |
| `onAccent` | 4.5:1 against the accent | Replaced with `#F2F4F7` or `#14181F`, whichever reads better |
| `header` | 4.5:1 against the app's text `#F2F4F7` | Darkened toward black until it does |

Each change produces `{"t":"warning","code":"adjusted","field":"colors.accent","message":"... The phone is using #828AA4."}`.

**Refusals.** Invalid or oversized icon: `rejected`, field `icon`, the rest applies. `colors`
with no valid `accent`: `rejected`, field `colors.accent`, previous colours kept. An icon whose
header passes but whose image will not decode: `rejected`, field `icon`, sent after the fact.

**Where it shows.** Header band (`header`, `accent` bottom line), header icon or monogram,
`name` plus the course name and world, shift paddles and remote pad **while pressed**
(`accent`/`onAccent`), and the disconnected/session-ended panel. **Never**: power, heart-rate
and gradient colours, warnings and notices, the app's navigation, the pairing dialog.

Not persisted, not written to the ride record.

---

## 8. `finish` (companion -> app)

```json
{ "t": "finish", "summary": { "shape": "_p~iF~ps|U", "profile": [412, 418, 431] } }
```

Ends the ride. The app calls its "end and save" path: a ride that got past the initial standby
is saved to history and exported like any other; one that never started is discarded.

`summary` is optional and requires the `summary` capability in your `hello`. It is the short
form of [`course`](#7a-course-companion---app) and merges into exactly the same place, so the
two are interchangeable: use `summary` if the road is all you have to say and you only know it
at the end, `course` if you also want to name the ride.

| Field | Type | Notes |
|---|---|---|
| `shape` | string | Precision-5 encoded polyline (the Google polyline algorithm) |
| `profile` | int[] | 64 elevation samples in metres. Fractions are rounded |

**A `finish` always ends the ride.** A `summary`, or a field in it, that cannot be read is left
out with a `rejected` warning (`field`: `summary` or `summary.profile`) and the ride saves
without it. Builds up to 2.2 refused the whole frame: 4008, the ride never ended, and the next
ride was saved on top of it.

Geography arrives after the ride is over. A companion that would rather not disclose even this
omits `summary`; the ride still saves with its numbers.

The course reaches the ride record before the ride is ended, because ending it is what writes
the row. That ordering is the app's business, not yours - it holds whether the shape came in a
`course` frame ten minutes ago or in this `summary`.

---

## 8a. Screenshots: `requestScreenshot` (app -> companion) and `screenshot` (companion -> app)

Requires `screenshot` in **both** `hello.capabilities` (you answer) and `welcome.capabilities`
(this build asks). Added in protocol 0.9. Controller only.

```json
{ "t": "requestScreenshot", "id": 3, "maxEdgePx": 1920 }
{ "t": "screenshot", "id": 3, "image": "/9j/4AAQSkZJRgABAQAAAQABAAD..." }
{ "t": "screenshot", "id": 4, "error": "The game is on a loading screen" }
```

`requestScreenshot` is sent when the rider taps the camera button on the ride header, only while
a ride is underway, and one at a time.

| Field | Type | Rules |
|---|---|---|
| `requestScreenshot.id` | integer | Unique on the connection. Echo it |
| `requestScreenshot.maxEdgePx` | integer | Scale so the longest edge is at most this |
| `screenshot.id` | integer | Required. Must match an outstanding request |
| `screenshot.image` | string | Base64 JPEG or PNG, detected from the bytes. Each edge 16 to 4 096 px. At most 6 000 000 base64 characters |
| `screenshot.error` | string | Instead of `image`: why not. Shown to the rider. Trimmed to 200 characters |

| What you send | What happens |
|---|---|
| A valid image for an outstanding `id` | Kept on the phone with the ride that was underway when the rider asked. Shown on the ride summary, in History and on the session panel |
| `error` | The rider is told the reason |
| An `id` never asked for, or already answered | `rejected` warning, field `id`. Ignored |
| An image that is not JPEG/PNG, not base64, or out of range | `rejected` warning, field `image`. The rider is told it could not be kept |
| Nothing for 15 s | The rider is told no screenshot came. A later answer is still kept |

Size is read from the PNG `IHDR` or the JPEG start-of-frame header, never by decoding. Not
exported, uploaded or synced; deleting the ride deletes its screenshots.

**Large frames.** A screenshot is hundreds of kilobytes to megabytes in one text frame. Raise
your WebSocket client's outbound buffer before connecting (Godot's `WebSocketPeer` defaults to
65 535 bytes and refuses larger sends with `ERR_OUT_OF_MEMORY`). Keep sending `world` while you
capture and encode.

---

## 9. `ping` / `pong`

```json
{ "t": "ping", "id": 17 }
{ "t": "pong", "id": 17 }
```

Optional. The WebSocket's own ping/pong is usually enough. Observers may ping.

---

## 10. `telemetry` (app -> companion)

Pushed every `welcome.tickMs` (250 ms) to **every connection that has completed a handshake**,
controllers and observers alike. Starts immediately after `welcome`.

```json
{
  "t": "telemetry",
  "seq": 1043,
  "tMono": 128500,
  "state": "active",
  "power": 243,
  "cadence": 88,
  "hr": 152,
  "speedMps": 8.33,
  "gearIndex": 11,
  "loadFactor": 1.18,
  "appliedGradient": 0.062,
  "trainerForceN": 96.4,
  "trainerGradePercent": 11.7,
  "elapsedS": 512
}
```

| Field | Type | Always present | Notes |
|---|---|---|---|
| `seq` | integer | yes | Increments by 1. A gap means frames were lost |
| `tMono` | integer ms | yes | Since ride start, monotonic, immune to clock changes |
| `state` | string | yes | See below |
| `power` | integer W | **no** | Omitted when no trainer is connected |
| `cadence` | integer rpm | **no** | Omitted when no trainer is connected |
| `hr` | integer bpm | **no** | Omitted when there is no reading |
| `speedMps` | number m/s | **no** | **The bike's speed as the phone models it. Use it.** From the legs while pulling away, carried by the bike's mass after. Omitted until the trainer has reported cadence |
| `gearIndex` | integer | yes | Index into `welcome.gears` |
| `loadFactor` | number | yes | Multiplier for the current gear |
| `appliedGradient` | number | yes | **After clamping and the deadman ramp** |
| `trainerForceN` | number | yes | Newtons at the wheel, after gear and the soft ceiling |
| `trainerGradePercent` | number | yes | The grade actually written over FTMS |
| `elapsedS` | integer s | yes | Ride elapsed time. Holds while untracked |
| `powerTarget` | integer W | **no** | The wattage a `setPower` asked for, after clamping. Present only while one is held |
| `powerCommanded` | integer W | **no** | What the trainer is being told right now. Differs from `powerTarget` while the lease is decaying it or the rider has been let go. Present only while a target is held |

While a power target is held there is no road being applied, so `appliedGradient`,
`trainerForceN` and `trainerGradePercent` all report `0.0` and `speedMps` may be omitted.

**Absent means absent.** A missing `hr` means no strap, not a heart rate of zero, and a
missing `speedMps` means the trainer cannot count cadence, not that the rider has stopped. Do
not default missing numeric fields to `0`; carry them as null/optional.

### 10.0 `speedMps`: take it, do not compute it

This is the single biggest feel bug in a new companion, so it gets its own section.

`speedMps` is how fast the rider's legs and their chosen gear are turning the road out. A chain
does not slip, so `v = cadence x development` is not an estimate of the rider's speed, it *is*
their speed, and the phone knows it on the first turn of the crank.

If you integrate a speed out of power instead, you are solving `m . dv/dt = P/v - F_road` from
a standing start. That model is not wrong so much as answering a different question: it
describes a bicycle that is genuinely stationary, rather than one whose cranks are already
turning at 80 rpm. What the rider sees is watts and cadence arriving from the trainer while the
speed sits at zero for five to ten seconds - at the start of the course, and again every time
they stop and start.

You still own the world. Take `speedMps` as the drivetrain's output, apply whatever your game
adds - drafting, wind, a descent that carries the rider faster than their gear could turn -
and send the result back as `world.speedMps`. A surface is the exception, and goes in `crr`
instead (5.2): put it in the speed and the rider watches a number fall while the pedals stay
light. The two agree wherever the rider is
settled, because both ends are solving `P = F_road(v) . v`, and they are meant to disagree
everywhere else.

Only when the field is **absent** should you fall back to integrating from power. There is then
no drivetrain speed to be had, and the phone omits it rather than echoing your own number back
at you, which would look like agreement and mean nothing.

`appliedGradient` will differ from what you sent when clamping or the deadman is active. Trust
it over your own value for anything you display as "what the rider is feeling".

`trainerGradePercent` differs from `appliedGradient` because it encodes a *force* against an
assumed 84 kg trainer mass, after the gear multiplier and the soft force ceiling. It is
published so you can tell a protocol bug from a physics disagreement. Do not use it as a
gradient.

### 10.1 `state` values

| `state` | App's ride state |
|---|---|
| `listening` | Idle: no ride prepared |
| `paused` | Standby (waiting for the rider to pedal), Countdown, or Paused |
| `active` | Recording and riding |
| `untracked` | Riding, not recording ([7b.1](#7b1-untracked-riding-that-is-not-written-down)). Only on a connection that negotiated 0.10 or later; older ones are told `paused`, because nothing is being recorded, which is the one fact below they can act on |
| `finished` | Ride complete |

A newly connected controller will typically see `paused` until the rider starts pedalling,
then `active`. This is normal: the app requires pedalling to begin a ride.

The app has more ride states than the protocol does, and collapses them on purpose: a game
cannot act on the difference between a rider who pressed pause, one who stopped pedalling and
one counting down. In all three the recording is not advancing, which is the only fact you can
use.

Every transition is also broadcast as a `stateChanged` [event](#11-event-app---companion),
whoever caused it - your `setState`, the rider's own pause button, the stop-pedalling watchdog,
or the phone's foreground service. Follow those rather than polling `telemetry.state`, and
never assume a `setState` you sent took effect.

### 10.2 Wire precision

Values are rounded on encode: `appliedGradient` 4 dp, `trainerForceN` and
`trainerGradePercent` 1 dp, `loadFactor` and gear `ratio` 2 dp, masses 1 dp. Non-finite values
encode as `0.0` rather than emitting invalid JSON.

---

## 11. `event` (app -> companion)

```json
{ "t": "event", "event": "gearChanged", "index": 12, "changed": true, "source": "zwiftClick" }
{ "t": "event", "event": "controlPressed", "control": "enter" }
{ "t": "event", "event": "drivetrainChanged", "index": 6, "gears": [ ... ] }
{ "t": "event", "event": "stateChanged", "state": "finished" }
{ "t": "event", "event": "worldTimeout", "fallback": "flat" }
{ "t": "event", "event": "worldResumed" }
{ "t": "event", "event": "controlRevoked", "by": "rider" }
{ "t": "event", "event": "powerTargetChanged", "watts": 125, "source": "companion" }
{ "t": "event", "event": "powerTargetChanged", "source": "state" }
```

| `event` | Fields | When |
|---|---|---|
| `gearChanged` | `index`, `changed`, `source` | Every shift, including one that changed nothing |
| `worldTimeout` | `fallback` | The lease expired; the road is ramping to flat |
| `worldResumed` | | A `world` frame arrived after a timeout |
| `stateChanged` | `state` | Ride state transitions the app broadcasts |
| `powerTargetChanged` | `watts`, `source` | The requested power target changed |
| `controlRevoked` | `by` | The rider took back control |

`source` values: `screen`, `zwiftClick`, `companion`, and `state` for a power target the
ride's own state cleared. Emitted to **all** connections, so an observer sees shifts too.

On `powerTargetChanged`, **an absent `watts` means the target was released**: the app omits a
field it has no value for rather than writing `null`, here as everywhere else
([14](#14-tolerance-rules-both-directions-mandatory)). The decay and the release of a stopped
rider are not target changes and never appear here; watch `telemetry.powerCommanded` for
those.

Reserved, defined but not currently emitted: `riderInput`, `trainerLost`.

Unknown `event` values must be ignored, not treated as errors.

---

## 12. `warning` (app -> companion)

Non-fatal. Something was ignored, clamped or rate-limited.

```json
{ "t": "warning", "code": "fieldNotHonoured", "field": "cda", "message": "..." }
{ "t": "warning", "code": "clamped", "field": "gradient", "sent": 0.42, "applied": 0.3 }
{ "t": "warning", "code": "rateLimited", "frame": "requestShift", "message": "..." }
```

| `code` | Meaning |
|---|---|
| `fieldNotHonoured` | A `world` field this build ignores. Emitted once per field per connection |
| `clamped` | A value was outside `welcome.limits`. `sent` and `applied` both given |
| `rateLimited` | Too many frames of that kind. `frame` names which |
| `rejected` | A value was dropped rather than trimmed, or a `course`/`finish` field could not be read. `field` names it, dotted when nested. The frame's other fields still applied |
| `adjusted` | A value was changed to stay legible; the message names the value used (0.8) |

**Surface these in your development build.** `fieldNotHonoured` is the difference between
"drafting doesn't work" and "this build ignores `cda`".

---

## 13. `error` (app -> companion)

Fatal for the operation, and usually followed by a close.

| `code` | Close | Meaning |
|---|---|---|
| `protoUnsupported` | 4001 | No overlapping version. Carries `serverRange`, `clientRange` |
| `unauthorized` | 4002 | Bad or missing token, or a non-`hello` first frame |
| `controllerBusy` | 4003 | Another controller holds the ride |
| `notController` | **none** | An observer sent a controlling frame. Socket stays open |
| `noTrainer` | none | Defined, not currently emitted |
| `malformed` | 4008 | Frame could not be parsed. Mid-ride the ride pauses, as for a disconnect |

### 13.1 Close codes

| Code | Meaning |
|---|---|
| 4001 | Protocol version mismatch |
| 4002 | Bad or missing token |
| 4003 | A controller is already connected |
| 4004 | Control revoked by the rider |
| 4005 | The app left Companion mode. Not sent on a `finish` since 0.7 |
| 4008 | Malformed frame |

**Close reasons are capped at 123 bytes.** A WebSocket close frame carries at most 125 bytes,
two of which are the code. The app trims anything longer and appends `...`; the untruncated
explanation is in the `error` frame, which has no such limit. **Apply the same cap to your own
close reasons.** Overrun it and the frame is invalid, the peer never sees a close at all, and
what should have been a 4001 arrives as a bare 1006.

---

## 14. Tolerance rules (both directions, mandatory)

1. **Ignore unknown fields.** Never an error.
2. **Ignore unknown frame types.** The app returns nothing and keeps the socket open. Do the
   same.
3. **Tolerate unknown enum values** (`state`, `event`, `source`, `code`). Fall back to a
   documented default.
4. **Never silently drop something meaningful.** The app warns once per unhonoured field. A
   companion should do the equivalent.
5. **Read a number for its value, not its spelling.** The app takes `921.0` where an integer
   belongs (builds up to 2.2 did not). A number with a real fraction in a count, `steps: 1.5`,
   is still `malformed`. Send integers as integers regardless.

These five are what let a 0.1 peer share a socket with a later one. Breaking them is the most
common way a companion becomes fragile.

---

## 15. Roles and concurrency

- **One controller, many observers.** A second `hello` with `role: "controller"` while one is
  connected is refused with 4003.
- Observers receive `telemetry`, `event` and `warning`. They may send `ping`.
- Observers sending `world`, `requestShift`, `label`, `course`, `setState` or `finish` get
  `error` `notController` and stay connected.
- When the controller disconnects, the deadman runs: the road ramps to flat over 2 seconds and
  the ride continues recording. A new controller may then connect.

---

## 16. Not implemented

Present in the protocol, absent from this build. Do not design around them.

| Gap | Detail |
|---|---|
| `cda`, `airDensity`, `headwindMps` | Reserved names, not honoured. Warned about. `crr` is honoured; see 5.2 |
| `resume` | Always `null`. Crash-recovery handoff is not wired |
| The live road after a crash | A resumed ride sits on flat until you reconnect and send `world` again. The app has no idea what hill it was on. The `course` itself does survive |
| `riderInput` | Zwift Click presses are consumed as shifts; unconsumed presses are not forwarded |
| `trainerLost`, `noTrainer` | Defined, never emitted |
| Consent scopes | Pairing is one decision; there is no separate grant for biometrics |
| Trainer control tiers | `sim`, plus ERG for a `setPower` target while the ride is untracked (7b.2). No resistance tier, and no power target during a recorded ride |
| Steering | No frame |
| TLS | Plaintext on the LAN, authenticated by the pairing code |

---

## 17. Worked transcript

```jsonc
// -> connect ws://192.168.1.42:47612/companion
{"t":"hello","protoMin":"0.1","protoMax":"0.1","token":"8F3K2M",
 "client":{"name":"Alpine Climb","version":"0.3.1","platform":"windows"},
 "role":"controller","capabilities":["summary"]}

// <- handshake accepted, ride prepared, telemetry starts
{"t":"welcome","proto":"0.2","app":"2.1.0","sessionId":"3f9a1c","tickMs":250,
 "honours":["gradient","speedMps","distanceM","ascentM","crr"],
 "capabilities":["requestShift","label","summary","course","setState"],"role":"controller",
 "rider":{"massKg":78.0,"ftpWatts":250},"bike":{"massKg":9.5,"drivetrain":"road-double"},
 "gears":[{"i":0,"ratio":1.21,"label":"34 x 28","loadFactor":0.72}],
 "gearIndex":6,"trainer":{"name":"KICKR CORE","connected":true,"control":"sim"},
 "limits":{"gradient":[-0.3,0.3],"speedMps":[0.0,30.0],"crr":[0.002,0.03]}}

// -> name the ride now, hand over its shape at the end
{"t":"course","name":"Stelvio, north side","world":"Alpine Tour","distanceM":24800,"ascentM":1533}

// -> steady state at 10 Hz. Speed is 0 because the rider has not started.
{"t":"world","seq":1,"gradient":0.062,"speedMps":0.0,"distanceM":0.0,"ascentM":0.0}

// <- rider has not pedalled yet: no cadence, so no drivetrain speed, so no speedMps field
{"t":"telemetry","seq":0,"tMono":250,"state":"paused","gearIndex":6,"loadFactor":1.0,
 "appliedGradient":0.062,"trainerForceN":48.2,"trainerGradePercent":5.9,"elapsedS":0}

// <- first crank revolution. speedMps is there immediately: 88 rpm through this gear is
// <- 7.42 m/s, and there is no such thing as pedalling at 88 rpm while standing still.
{"t":"telemetry","seq":24,"tMono":6250,"state":"active","power":243,"cadence":88,"hr":152,
 "speedMps":7.42,"gearIndex":6,"loadFactor":1.0,"appliedGradient":0.062,
 "trainerForceN":49.1,"trainerGradePercent":6.0,"elapsedS":1}

// -> take it, apply the world's own effects, send it back
{"t":"world","seq":2,"gradient":0.062,"speedMps":7.42,"distanceM":1.9,"ascentM":0.1}

// -> a 14% ramp; the game asks for an easier gear
{"t":"requestShift","steps":-1}
// <- {"t":"event","event":"gearChanged","index":5,"changed":true,"source":"companion"}

// -> the rider uses the handlebar shifter instead
// <- {"t":"event","event":"gearChanged","index":4,"changed":true,"source":"zwiftClick"}

// -> a field this build does not act on
{"t":"world","seq":500,"gradient":0.08,"speedMps":4.1,"cda":0.24}
// <- {"t":"warning","code":"fieldNotHonoured","field":"cda","message":"This build ignores 'cda'..."}

// -> beyond the envelope
{"t":"world","seq":501,"gradient":0.42,"speedMps":4.0}
// <- {"t":"warning","code":"clamped","field":"gradient","sent":0.42,"applied":0.3}

// -> the rider opens the game's menu. Pause the recording, but keep the road coming:
// -> setState does not stop the deadman.
{"t":"setState","state":"paused"}
// <- {"t":"event","event":"stateChanged","state":"paused"}
{"t":"world","seq":502,"gradient":0.08,"speedMps":0.0}
// -> back to the ride
{"t":"setState","state":"active"}
// <- {"t":"event","event":"stateChanged","state":"active"}

// -> the game freezes for two seconds
// <- {"t":"event","event":"worldTimeout","fallback":"flat"}
// <- telemetry appliedGradient decays: 0.3 -> 0.15 -> 0.0
// -> recovered
// <- {"t":"event","event":"worldResumed"}

// -> summit. The shape is only known now, so it goes over as a course before the finish.
{"t":"course","shape":"_p~iF~ps|U_ulLnnqC","profile":[412,418,431,447]}
{"t":"finish"}
// <- {"t":"event","event":"stateChanged","state":"finished"}  // written to history
// <- {"t":"event","event":"stateChanged","state":"paused"}    // ready for another

// The socket is still open. Since 0.7 a finish ends the ride and nothing else,
// so the next lap costs a course and a start, not a reconnection.
{"t":"course","name":"Stelvio, south side"}
{"t":"setState","state":"active"}
```

---

## 18. Companion implementation checklist

- [ ] Discover via mDNS `_indoorbike._tcp`, **and** offer QR scan / manual host+port+code
- [ ] Read the port from SRV or the QR payload; never hardcode
- [ ] Send `hello` as the very first frame, with `protoMin`/`protoMax` and the token
- [ ] Send a stable `deviceId` if you want permanent pairing, and store the key you get back
- [ ] On reconnect, answer `challenge` with a **fresh** nonce, and verify `welcome.proof`
- [ ] Verify `welcome.proto` against your own range; fail with a message naming both
- [ ] Read `welcome.honours` and `welcome.capabilities`, and warn if you rely on something
      not listed. Branch on `capabilities`, never on the version number
- [ ] Read `welcome.tickMs`; do not hardcode 250
- [ ] Build the gear display from `welcome.gears`, and update it from
      `drivetrainChanged` if you send a `drivetrain`
- [ ] Send `world` at 4-10 Hz with a strictly increasing `seq`
- [ ] Always include `gradient` **and** `speedMps`
- [ ] Take the rider's speed from `telemetry.speedMps`; only integrate from power when the
      field is absent
- [ ] Send `distanceM`/`ascentM` as cumulative totals, monotonic
- [ ] Interpolate telemetry for rendering; do not ask for a faster stream
- [ ] Treat missing `power`/`cadence`/`hr`/`speedMps` as absent, not zero
- [ ] Send a `course` (name early, shape at the end) so the ride lands in history with a map
- [ ] Send `setState: "active"` when your world says the ride has begun. **Nothing is recorded
      until you do**
- [ ] Send `setState` on your own pause and resume, and keep sending `world` while paused
- [ ] If the rider works without it counting - pushing the bike, a warm-up, a cool-down - ask
      for `setState: "untracked"` with a `label`, and keep untracked travel out of the
      `distanceM`/`ascentM` totals you send afterwards
- [ ] Send `setPower` again after **every** `stateChanged: "untracked"`: the target never
      survives leaving the state
- [ ] Keep sending `world` while you hold a power target, or it decays to zero on the lease
- [ ] If you show menus, send `controls` with an accurate `enabled` list, and `visible: false`
      when the menu closes
- [ ] Follow `stateChanged`; never assume a `setState` took effect
- [ ] Handle `warning` frames visibly in development builds
- [ ] Handle `worldTimeout` / `worldResumed` (pause your world, show a reconnect state)
- [ ] Handle `gearChanged` with `changed: false` as "no gears left"
- [ ] Ignore unknown fields, frame types and enum values
- [ ] Handle every close code, and read the close reason
- [ ] Send `finish` when your ride ends
- [ ] After a `finish`, **stay connected** and send a new `course` plus `setState: active`
      for the next ride. The socket does not close and riders ride twice
- [ ] If `welcome.capabilities` has `brand`, send `brand` after `welcome`: a 256 px PNG
      icon, your name and an `accent`. Log `adjusted` warnings in development
- [ ] If you can capture your screen, list `screenshot` in `hello`, raise your WebSocket
      outbound buffer to a few MB, and answer each `requestScreenshot` with a JPEG echoing
      its `id` (or an `error`)

## 19. Common mistakes

| Mistake | Result |
|---|---|
| Omitting `speedMps` | Frame is `malformed`; the socket closes with 4008 |
| Starting the `world` pump on connect rather than on the `welcome` | On a remembered pairing the phone answers with a `challenge` first, and anything but `pairProof` while it is outstanding is `unauthorized`: 4002, mid-handshake |
| Sending integers as `921.0` (Godot: anything through `JSON.parse_string` then `JSON.stringify`) | Read by current builds. Builds up to 2.2 close with 4008, and a `finish` written that way never ends the ride |
| Sending `seq` that does not increase | Frames dropped silently; the road appears frozen |
| Sending deltas in `distanceM` | Distance stalls, because smaller values are ignored |
| Waiting for the socket to close after a `finish` | It does not close any more. You wait forever and the second ride never starts |
| Closing the socket instead of sending `finish` | The ride pauses and the rider is left on a "Ride paused" screen until you come back or they end it themselves |
| Assuming the ride is paused after you reconnect | It resumed when you reconnected. Read `stateChanged`, and send `setState: "paused"` if you came back into a menu |
| Sending a `course` without a `name` | The ride is listed in history as "Companion ride" |
| Sending the icon as a URL, a JPEG, or a non-square image | `rejected`; the header shows a monogram instead |
| Choosing a dark brand colour on a light desktop and never reading warnings | The phone lightens it and you never learn why it looks different |
| Reusing the previous ride's `course` | The new ride is recorded under the old ride's name and map |
| Reconnecting to a remembered `host:port` after a real 4005 | Connection refused; the port is ephemeral and new every time the mode is opened |
| Hardcoding the port | Fails on every ride but the one you tested |
| Sending a screenshot through Godot's default 64 KiB WebSocket buffer | `send_text` fails with `ERR_OUT_OF_MEMORY`; the phone tells the rider no screenshot came |
| Sending a screenshot nobody asked for, or not echoing `id` | `rejected` warning on `id`; nothing is kept |
| Assuming `role` defaults to observer | It defaults to controller |
| Sending `role: "Controller"` | Case-sensitive; you get observer and cannot steer |
| Treating `trainerGradePercent` as a gradient | It is a force expressed against 84 kg |
| Defaulting missing `hr` to 0 | Displays a heart rate of zero instead of "no strap" |
| Sending `cda` expecting drafting | Not honoured; you own drag, apply it to your own speed |
| A close reason over 123 bytes | Invalid frame; the peer sees 1006 and loses your close code |
| Expecting the app to end the ride | Only `finish` or the rider ends it |
| Integrating speed from power when `speedMps` is present | Speed sits at zero for 5-10 s every time the rider starts pedalling |
| Defaulting a missing `speedMps` to 0 | The rider is stopped dead on a trainer that cannot count cadence |
| Truncating a long `shape` to fit | Refused whole with a `rejected` warning; simplify the track instead |
| Reading `course.distanceM` as the rider's distance | It is the course's own length; the rider's is `world.distanceM` |
| Pausing with `setState` and stopping `world` frames | The deadman ramps the gradient to flat under a rider who may still be pedalling |
| Assuming the gear ladder matches the `drivetrain` you sent | Near-identical ratios are dropped. Read `drivetrainChanged` |
| Reusing one nonce for every `pairProof` | You are signing something only the app chose, which is most of the way back to a replay working |
| Not verifying `welcome.proof` | You trust whatever answered on the port, which is what pairing exists to prevent |
| Treating a stored key as permanent | The rider can forget you. Fall back to the code rather than failing shut |
| Logging the pairing key | It authenticates you to somebody's trainer |
| Never sending `setState: "active"` | The rider pedals, the road works, and nothing is ever recorded |
| Waiting for the app to count the rider in | It does not, in this mode. Your game owns the start |
| Leaving `controls` visible after a menu closes | The rider is left holding a pad wired to nothing |
| Listing a control your screen ignores | The rider presses a live-looking button and nothing happens, which is the exact failure `enabled` exists to prevent |
