# Companion API - implementation reference

Exhaustive, machine-readable reference for the Indoor Bike Companion API as **implemented on
the app side**. Written to be read by a model or a developer building the other end.

- Protocol version: **0.1**
- Transport: WebSocket, JSON text frames, UTF-8, local network only
- Path: `/companion`
- Status: app side implemented; see [Not implemented](#16-not-implemented) for the gaps

**This is a portable copy.** The source of truth is `docs/COMPANION_API_REFERENCE.md` in the
Indoor Bike repository, alongside `docs/COMPANION_API.md` (the prose specification and
rationale) and `docs/LIVE_API.md` (the original design argument). If this copy and the app
disagree, the app is right; re-copy from the repository.

**This document is normative for behaviour**: where prose and this disagree, the app does what
is written here.

Terminology: **app** is Indoor Bike on the phone (the WebSocket *server*). **Companion** is
your program (the WebSocket *client*).

---

## 1. Ownership, stated as rules

| Owned by the companion | Owned by the app |
|---|---|
| The world and the rider's position in it | The BLE link and FTMS quirks |
| Virtual speed | Virtual gears and all shifting logic |
| Gradient under the wheels | The force commanded to the trainer |
| Rolling resistance, drag, wind, draft, surfaces | The safety envelope |
| When the ride ends | Session state, ride record, FIT/TCX export |

Three consequences that are easy to get wrong:

1. **You must send `speedMps` on every `world` frame.** The commanded force contains
   `0.5 * rho * CdA * v^2`. Omitting speed is not "use a default"; the field is required and a
   frame without it is `malformed`.
2. **You cannot shift.** You may *request* a shift. The app owns the drivetrain, clamps the
   index and reports the result.
3. **The app never learns position.** `distanceM` and `ascentM` are cumulative totals for the
   ride record. There is no latitude, longitude or altitude anywhere in the protocol during a
   ride.

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

### 3.1 mDNS / DNS-SD (preferred)

- Service type: `_indoorbike._tcp`
- Service name: `Indoor Bike` (the OS appends a suffix if it collides)
- Port: **ephemeral**, published in the SRV record. Never hardcode a port.

TXT records:

| Key | Example | Meaning |
|---|---|---|
| `proto` | `0.1-0.1` | Supported version range, `min-max` |
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
  "protoMax": "0.1",
  "token": "8F3K2M",
  "client": { "name": "Alpine Climb", "version": "0.3.1", "platform": "windows" },
  "role": "controller",
  "capabilities": ["summary"]
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
| `capabilities` | string[] | no | `summary` is the only one the app reads |

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
  "proto": "0.1",
  "app": "2.0.2",
  "sessionId": "3f9a1c",
  "tickMs": 250,
  "honours": ["gradient", "speedMps", "distanceM", "ascentM"],
  "capabilities": ["requestShift", "label", "summary"],
  "role": "controller",
  "rider": { "massKg": 78.0, "ftpWatts": 250 },
  "bike": { "massKg": 9.5, "drivetrain": "road-double" },
  "gears": [
    { "i": 0, "ratio": 1.21, "label": "34 x 28", "loadFactor": 0.72 },
    { "i": 1, "ratio": 1.36, "label": "34 x 25", "loadFactor": 0.79 }
  ],
  "gearIndex": 6,
  "trainer": { "name": "KICKR CORE", "connected": true, "control": "sim" },
  "limits": { "gradient": [-0.3, 0.3], "speedMps": [0.0, 30.0] },
  "resume": null
}
```

- `honours` is what **this build acts on**. Check it. A field not listed is ignored and warned
  about. It is not the same as what the protocol defines.
- `gears` is the full drivetrain in index order. `label` is real teeth (`"34 x 28"`).
  `loadFactor` is the multiplier applied to the road force in that gear.
- `limits` are the clamps. Values outside them are clamped, not rejected.
- `resume` is `null` in the current build. Reserved for crash recovery.
- `tickMs` is the telemetry period. Do not hardcode 250; read it.

### 4.3 Version negotiation

The app picks the highest version both ends list. Both ends must check: the app may choose a
version you listed but have since dropped, so verify `welcome.proto` against your own range
and fail loudly if it is not one you speak.

Rules from 1.0 onward (0.x may break on a minor bump):

- **Minor bump: additive only.** A 1.0 implementation must keep working against a 1.4 peer.
- **Major bump: everything else**, including a changed unit or default.

Version and capability are separate mechanisms. Version answers "same language"; `honours`
answers "what does this build do". Gaining a road parameter is a capability change, not a
version bump.

### 4.4 Handshake failures

| Condition | Frame | Close |
|---|---|---|
| No overlapping version | `error` `protoUnsupported`, with `serverRange` and `clientRange` | 4001 |
| Unparseable `protoMin`/`protoMax` | `error` `protoUnsupported` | 4001 |
| `protoMin` above `protoMax` | `error` `protoUnsupported` | 4001 |
| Wrong or missing token | `error` `unauthorized` | 4002 |
| A controller already holds the ride | `error` `controllerBusy` | 4003 |
| First frame is not `hello` | `error` `unauthorized` | 4002 |

The app always sends the `error` frame **and then** closes with a code and a reason string.
Handle both: you may close the socket before you read the frame, and then the close reason is
all you get. Error messages name both version ranges and which end to update.

---

## 5. `world` (companion -> app)

The road. Send at 4-10 Hz. Faster buys nothing: the app applies the newest frame on its next
250 ms tick and rate-limits its own BLE writes below that.

```json
{ "t": "world", "seq": 412, "gradient": 0.062, "speedMps": 7.42, "distanceM": 8412.5, "ascentM": 214.0 }
```

| Field | Type | Required | Range | Notes |
|---|---|---|---|---|
| `seq` | integer | yes | monotonic | Frames with `seq <= last` are **dropped silently** |
| `gradient` | number | yes | clamped to [-0.30, 0.30] | Rise over run. `0.06` = 6% climb |
| `speedMps` | number | yes | clamped to [0.0, 30.0] | Along the **road surface**, not horizontal |
| `distanceM` | number | no | monotonic | Cumulative along-road distance this ride |
| `ascentM` | number | no | monotonic | Cumulative metres climbed this ride |

Behaviour:

- **Clamping** raises a `warning` with `code: "clamped"`, `field`, `sent`, `applied`. The
  clamped value is applied; the frame is not rejected.
- **`NaN` gradient becomes `0.0`** rather than propagating into the physics.
- **`distanceM` / `ascentM` never decrease.** If you send a smaller value than before, the
  larger one is kept. Send cumulative totals, not deltas.
- Omitting `distanceM` / `ascentM` leaves the previously banked totals untouched.
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
- Crossing into stale emits `event` `worldTimeout` with `fallback: "flat"`, once.
- The next `world` frame emits `event` `worldResumed`, once.

Reserved but **not honoured** in 0.1: `crr`, `cda`, `airDensity`, `headwindMps`. They are
unnecessary while you own speed - drafting, surfaces and wind are your arithmetic and never
cross the socket. Sending them earns a `fieldNotHonoured` warning explaining exactly that.

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
optional. In the current build the label is shown in the app's companion status; it is not yet
persisted onto the saved session (see [Not implemented](#16-not-implemented)).

---

## 8. `finish` (companion -> app)

```json
{ "t": "finish", "summary": { "shape": "_p~iF~ps|U", "profile": [412, 418, 431] } }
```

Ends the ride. The app calls its "end and save" path: a ride that got past the initial standby
is saved to history and exported like any other; one that never started is discarded.

`summary` is optional and requires the `summary` capability in your `hello`.

| Field | Type | Notes |
|---|---|---|
| `shape` | string | Precision-5 encoded polyline (the Google polyline algorithm) |
| `profile` | int[] | 64 elevation samples in metres |

**This is the only frame that may carry geography, and it arrives after the ride is over.**
A companion that would rather not disclose even this omits `summary`; the ride still saves with
its numbers.

In the current build the summary is accepted and logged but not stored.

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
| `gearIndex` | integer | yes | Index into `welcome.gears` |
| `loadFactor` | number | yes | Multiplier for the current gear |
| `appliedGradient` | number | yes | **After clamping and the deadman ramp** |
| `trainerForceN` | number | yes | Newtons at the wheel, after gear and the soft ceiling |
| `trainerGradePercent` | number | yes | The grade actually written over FTMS |
| `elapsedS` | integer s | yes | Ride elapsed time |

**Absent means absent.** A missing `hr` means no strap, not a heart rate of zero. Do not
default missing numeric fields to `0`; carry them as null/optional.

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
| `finished` | Ride complete |

A newly connected controller will typically see `paused` until the rider starts pedalling,
then `active`. This is normal: the app requires pedalling to begin a ride.

### 10.2 Wire precision

Values are rounded on encode: `appliedGradient` 4 dp, `trainerForceN` and
`trainerGradePercent` 1 dp, `loadFactor` and gear `ratio` 2 dp, masses 1 dp. Non-finite values
encode as `0.0` rather than emitting invalid JSON.

---

## 11. `event` (app -> companion)

```json
{ "t": "event", "event": "gearChanged", "index": 12, "changed": true, "source": "zwiftClick" }
{ "t": "event", "event": "stateChanged", "state": "finished" }
{ "t": "event", "event": "worldTimeout", "fallback": "flat" }
{ "t": "event", "event": "worldResumed" }
{ "t": "event", "event": "controlRevoked", "by": "rider" }
```

| `event` | Fields | When |
|---|---|---|
| `gearChanged` | `index`, `changed`, `source` | Every shift, including one that changed nothing |
| `worldTimeout` | `fallback` | The lease expired; the road is ramping to flat |
| `worldResumed` | | A `world` frame arrived after a timeout |
| `stateChanged` | `state` | Ride state transitions the app broadcasts |
| `controlRevoked` | `by` | The rider took back control |

`source` values: `screen`, `zwiftClick`, `companion`. Emitted to **all** connections, so an
observer sees shifts too.

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
| `malformed` | 4008 | Frame could not be parsed |

### 13.1 Close codes

| Code | Meaning |
|---|---|
| 4001 | Protocol version mismatch |
| 4002 | Bad or missing token |
| 4003 | A controller is already connected |
| 4004 | Control revoked by the rider |
| 4005 | Ride ended |
| 4008 | Malformed frame |

---

## 14. Tolerance rules (both directions, mandatory)

1. **Ignore unknown fields.** Never an error.
2. **Ignore unknown frame types.** The app returns nothing and keeps the socket open. Do the
   same.
3. **Tolerate unknown enum values** (`state`, `event`, `source`, `code`). Fall back to a
   documented default.
4. **Never silently drop something meaningful.** The app warns once per unhonoured field. A
   companion should do the equivalent.

These four are what let a 0.1 peer share a socket with a later one. Breaking them is the most
common way a companion becomes fragile.

---

## 15. Roles and concurrency

- **One controller, many observers.** A second `hello` with `role: "controller"` while one is
  connected is refused with 4003.
- Observers receive `telemetry`, `event` and `warning`. They may send `ping`.
- Observers sending `world`, `requestShift`, `label` or `finish` get `error` `notController`
  and stay connected.
- When the controller disconnects, the deadman runs: the road ramps to flat over 2 seconds and
  the ride continues recording. A new controller may then connect.

---

## 16. Not implemented

Present in the protocol, absent from this build. Do not design around them.

| Gap | Detail |
|---|---|
| `crr`, `cda`, `airDensity`, `headwindMps` | Reserved names, not honoured. Warned about |
| `resume` | Always `null`. Crash-recovery handoff is not wired |
| Ride label persistence | `label` reaches the app's status but is not stored on the saved session |
| Ride summary persistence | `finish.summary` is accepted and logged, not stored or drawn |
| `riderInput` | Zwift Click presses are consumed as shifts; unconsumed presses are not forwarded |
| `trainerLost`, `noTrainer` | Defined, never emitted |
| Consent scopes | Pairing is one decision; there is no separate grant for biometrics |
| Trainer control tiers | `sim` only. No resistance or power-only fallback |
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
{"t":"welcome","proto":"0.1","app":"2.0.2","sessionId":"3f9a1c","tickMs":250,
 "honours":["gradient","speedMps","distanceM","ascentM"],
 "capabilities":["requestShift","label","summary"],"role":"controller",
 "rider":{"massKg":78.0,"ftpWatts":250},"bike":{"massKg":9.5,"drivetrain":"road-double"},
 "gears":[{"i":0,"ratio":1.21,"label":"34 x 28","loadFactor":0.72}],
 "gearIndex":6,"trainer":{"name":"KICKR CORE","connected":true,"control":"sim"},
 "limits":{"gradient":[-0.3,0.3],"speedMps":[0.0,30.0]}}

{"t":"label","name":"Stelvio, north side","world":"Alpine Tour"}

// -> steady state at 10 Hz
{"t":"world","seq":1,"gradient":0.062,"speedMps":7.42,"distanceM":0.0,"ascentM":0.0}

// <- rider has not pedalled yet
{"t":"telemetry","seq":0,"tMono":250,"state":"paused","gearIndex":6,"loadFactor":1.0,
 "appliedGradient":0.062,"trainerForceN":48.2,"trainerGradePercent":5.9,"elapsedS":0}

// <- rider starts pedalling; the app counts down and begins recording
{"t":"telemetry","seq":24,"tMono":6250,"state":"active","power":243,"cadence":88,"hr":152,
 "gearIndex":6,"loadFactor":1.0,"appliedGradient":0.062,
 "trainerForceN":49.1,"trainerGradePercent":6.0,"elapsedS":1}

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

// -> the game freezes for two seconds
// <- {"t":"event","event":"worldTimeout","fallback":"flat"}
// <- telemetry appliedGradient decays: 0.3 -> 0.15 -> 0.0
// -> recovered
// <- {"t":"event","event":"worldResumed"}

// -> summit
{"t":"finish","summary":{"shape":"_p~iF~ps|U","profile":[412,418]}}
// <- {"t":"event","event":"stateChanged","state":"finished"}
// <- close 4005 "ride ended"
```

---

## 18. Companion implementation checklist

- [ ] Discover via mDNS `_indoorbike._tcp`, **and** offer QR scan / manual host+port+code
- [ ] Read the port from SRV or the QR payload; never hardcode
- [ ] Send `hello` as the very first frame, with `protoMin`/`protoMax` and the token
- [ ] Verify `welcome.proto` against your own range; fail with a message naming both
- [ ] Read `welcome.honours` and warn if you rely on something not listed
- [ ] Read `welcome.tickMs`; do not hardcode 250
- [ ] Build the gear display from `welcome.gears`, not from an index
- [ ] Send `world` at 4-10 Hz with a strictly increasing `seq`
- [ ] Always include `gradient` **and** `speedMps`
- [ ] Send `distanceM`/`ascentM` as cumulative totals, monotonic
- [ ] Interpolate telemetry for rendering; do not ask for a faster stream
- [ ] Treat missing `power`/`cadence`/`hr` as absent, not zero
- [ ] Handle `warning` frames visibly in development builds
- [ ] Handle `worldTimeout` / `worldResumed` (pause your world, show a reconnect state)
- [ ] Handle `gearChanged` with `changed: false` as "no gears left"
- [ ] Ignore unknown fields, frame types and enum values
- [ ] Handle every close code, and read the close reason
- [ ] Send `finish` when your ride ends

## 19. Common mistakes

| Mistake | Result |
|---|---|
| Omitting `speedMps` | Frame is `malformed`; the socket closes with 4008 |
| Sending `seq` that does not increase | Frames dropped silently; the road appears frozen |
| Sending deltas in `distanceM` | Distance stalls, because smaller values are ignored |
| Hardcoding the port | Fails on every ride but the one you tested |
| Assuming `role` defaults to observer | It defaults to controller |
| Sending `role: "Controller"` | Case-sensitive; you get observer and cannot steer |
| Treating `trainerGradePercent` as a gradient | It is a force expressed against 84 kg |
| Defaulting missing `hr` to 0 | Displays a heart rate of zero instead of "no strap" |
| Sending `cda` expecting drafting | Not honoured; you own drag, apply it to your own speed |
| Expecting the app to end the ride | Only `finish` or the rider ends it |
