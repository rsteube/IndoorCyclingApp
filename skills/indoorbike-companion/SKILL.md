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
3. **Send a flat road.** `world` at 10 Hz with `gradient: 0.0` and a speed you integrate from
   the reported power. The trainer should feel like flat ground. If it does not, stop and fix
   it here.
4. **Add terrain.** Now the gradient comes from your world.
5. **Add gears.** Draw the gear display from `welcome.gears`; wire `requestShift`; handle
   `gearChanged` including `changed: false`.
6. **Add discovery.** Bind UDP 47601 and read the beacon; see `references/godot.md`. The
   phone's address is the datagram's source, never a field in the payload.
7. **Add `label` and `finish`.**

Do not start at step 4. A companion that renders a mountain before it has proven a flat road
has two untested systems and no way to tell which is wrong.

## The ten rules that prevent most bugs

1. **Send `hello` first.** Any other first frame is `unauthorized` and a close with 4002.
2. **`role` defaults to `controller`**, and any value the phone does not recognise (including
   wrong case) silently grants **observer**, which cannot steer.
3. **Always send both `gradient` and `speedMps`.** `speedMps` is required. A `world` frame
   without it is `malformed` and closes the socket with 4008.
4. **`seq` must strictly increase.** Frames with `seq <= last` are dropped **silently**. This
   presents as "the road is frozen" with no error anywhere.
5. **`distanceM` and `ascentM` are cumulative totals, never deltas**, and never decrease. Send
   a smaller value and it is ignored.
6. **Never hardcode the WebSocket port.** It is ephemeral, different every ride. Read it from
   the UDP beacon, the mDNS SRV record, or the QR payload. The only fixed port in the protocol
   is **47601**, where discovery datagrams arrive - a listener cannot discover the port it
   should be listening on.
7. **Read `welcome.honours`.** It is what this build *acts on*, which is not what the protocol
   defines. `crr`, `cda`, `airDensity` and `headwindMps` are reserved and **not honoured**.
8. **Missing telemetry fields mean absent, not zero.** No `hr` means no strap. Defaulting to 0
   puts a heart rate of zero on your HUD.
9. **Surface `warning` frames in development.** `fieldNotHonoured` is the difference between
   "drafting doesn't work" and "this build ignores `cda`".
10. **Ignore unknown fields, frame types and enum values.** Never treat them as errors. This is
    what lets your build share a socket with a later one.

## Rates

| Thing | Rate | Note |
|---|---|---|
| `world` out | 4-10 Hz | Fixed timer, **not** per frame. A 144 Hz game must not send 144 Hz |
| `telemetry` in | `welcome.tickMs` (250 ms) | Interpolate for rendering; do not ask for faster |
| `requestShift` | max 10/s | Excess is warned and dropped, never an error |
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

Write a mock server before you have a trainer to hand. It needs to: accept a WebSocket on
`/companion`, answer `hello` with a `welcome`, push `telemetry` every 250 ms, and echo
`gearChanged` on `requestShift`. That covers every path except the trainer feel, and it lets
you develop on a plane.

Also test these, because riders hit all of them:

- The phone's Wi-Fi drops mid-ride (socket closes without a close frame)
- Your app is alt-tabbed for 5 seconds (deadman)
- The rider shifts on the handlebar (`gearChanged` with `source: "zwiftClick"`)
- The rider ends the ride on the phone (`stateChanged` `finished`, then close 4005)
- A second instance of your app connects (4003)

## Scope

Do not ask the phone for anything outside the protocol. It will not send position, it will not
accept a route, and it will not let you shift directly. If a feature seems to need one of
those, it belongs on your side of the socket.

The Indoor Bike app is GPLv3; a closed-source companion is fine, because a separate program
communicating over a network socket is not a derivative work. That holds only while the
boundary stays a socket: do not link the app's code into your process.
