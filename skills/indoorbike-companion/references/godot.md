# Godot 4 companion client

Concrete guidance for implementing the Companion API in Godot 4. Read `protocol.md` for the
frame reference; this covers only what is Godot-specific.

## Use `WebSocketPeer`, not the Godot 3 classes

`WebSocketClient` and `WebSocketServer` were removed in Godot 4. Everything goes through
`WebSocketPeer`, which you poll yourself.

```gdscript
extends Node
class_name CompanionLink

signal telemetry_received(frame: Dictionary)
signal event_received(frame: Dictionary)
signal warning_received(frame: Dictionary)
signal welcomed(frame: Dictionary)
signal link_closed(code: int, reason: String)

const PROTO_MIN := "0.1"
const PROTO_MAX := "0.2"
const WORLD_HZ := 10.0

var _socket := WebSocketPeer.new()
var _seq := 0
var _world_accum := 0.0
var _handshaked := false
var _token := ""

# Set by your world each frame.
var gradient := 0.0
# Set from telemetry.speedMps and then adjusted by whatever your world adds.
# Do not integrate it out of power: see "Speed comes off the telemetry".
var speed_mps := 0.0
var distance_m := 0.0
var ascent_m := 0.0


func connect_to_phone(host: String, port: int, token: String) -> Error:
    _token = token
    _handshaked = false
    _seq = 0
    var url := "ws://%s:%d/companion" % [host, port]
    return _socket.connect_to_url(url)


func _process(delta: float) -> void:
    _socket.poll()
    match _socket.get_ready_state():
        WebSocketPeer.STATE_OPEN:
            if not _handshaked:
                _send_hello()
            _drain_incoming()
            _pump_world(delta)
        WebSocketPeer.STATE_CLOSED:
            var code := _socket.get_close_code()
            var reason := _socket.get_close_reason()
            # code is -1 when the socket died without a close frame, which is
            # what a phone dropping off Wi-Fi looks like. Handle it as a
            # disconnect, not as a protocol error.
            link_closed.emit(code, reason)
            set_process(false)
```

`get_ready_state()` also returns `STATE_CONNECTING` and `STATE_CLOSING`; both mean "do nothing
this frame".

## Send `hello` exactly once, on the first open frame

`connect_to_url` returns before the socket is open, so the handshake cannot be sent from it.
Sending it on every open frame would spam the phone.

```gdscript
func _send_hello() -> void:
    _handshaked = true
    _send({
        "t": "hello",
        "protoMin": PROTO_MIN,
        "protoMax": PROTO_MAX,
        "token": _token,
        "client": {
            "name": "Alpine Climb",
            "version": "0.3.1",
            "platform": OS.get_name(),
        },
        "role": "controller",       # exact string; any other value grants observer
        "capabilities": ["summary", "course", "setState"],
    })


func _send(frame: Dictionary) -> void:
    _socket.send_text(JSON.stringify(frame))
```

## Read frames, and tolerate what you do not know

```gdscript
func _drain_incoming() -> void:
    while _socket.get_available_packet_count() > 0:
        var text := _socket.get_packet().get_string_from_utf8()
        var parsed: Variant = JSON.parse_string(text)
        if typeof(parsed) != TYPE_DICTIONARY:
            push_warning("Companion: unparseable frame")
            continue
        var frame: Dictionary = parsed
        match frame.get("t", ""):
            "welcome":   _on_welcome(frame)
            "telemetry": telemetry_received.emit(frame)
            "event":     event_received.emit(frame)
            "warning":
                # Surface these loudly in a dev build. fieldNotHonoured is the
                # difference between "drafting doesn't work" and "this build
                # ignores cda".
                push_warning("Companion warning: %s" % text)
                warning_received.emit(frame)
            "error":     push_error("Companion error: %s" % frame.get("message", ""))
            "pong":      pass
            _:
                # Unknown frame types are ignored, never fatal. This is what
                # lets you share a socket with a newer phone build.
                pass
```

## Verify the negotiated version yourself

Both ends must check. The phone may pick a version you listed but have since dropped.

```gdscript
func _on_welcome(frame: Dictionary) -> void:
    var proto: String = frame.get("proto", "")
    if proto != PROTO_MIN:
        push_error("Indoor Bike chose protocol %s; this companion speaks %s-%s. \
Update this companion, or run an Indoor Bike build that supports %s."
            % [proto, PROTO_MIN, PROTO_MAX, PROTO_MAX])
        _socket.close(4001, "protocol mismatch")
        return
    if frame.get("role", "") != "controller":
        push_error("Granted observer, not controller: cannot drive the ride.")
    # Read these rather than hardcoding them.
    var tick_ms: int = frame.get("tickMs", 250)
    var honours: Array = frame.get("honours", [])
    var gears: Array = frame.get("gears", [])
    welcomed.emit(frame)
```

## Send `world` on a fixed timer, never per frame

A game at 144 fps that sends one `world` per frame sends 144 Hz into a socket whose consumer
ticks at 4 Hz. Accumulate instead.

```gdscript
func _pump_world(delta: float) -> void:
    _world_accum += delta
    var interval := 1.0 / WORLD_HZ
    if _world_accum < interval:
        return
    _world_accum = fmod(_world_accum, interval)
    _seq += 1
    _send({
        "t": "world",
        "seq": _seq,                 # must strictly increase, or frames are dropped silently
        "gradient": gradient,        # rise over run, clamped by the phone to +/-0.30
        "speedMps": speed_mps,       # REQUIRED. Omitting it closes the socket with 4008
        "distanceM": distance_m,     # cumulative total, never a delta, never decreasing
        "ascentM": ascent_m,
    })
```

**Keep pumping while your world is paused.** A loading screen, a menu or an alt-tab that stops
this loop for 1.5 s triggers the phone's deadman and the hill ramps away under the rider. Send
the current gradient with `speed_mps = 0.0` rather than sending nothing.

## Speed comes off the telemetry

`telemetry.speedMps` is how fast the rider's legs and their chosen gear are turning the road
out, and it is there on the first turn of the crank. Take it, apply your world's own effects,
and send the result back as `world.speedMps`.

Do not integrate a speed out of power instead. That models a bicycle accelerating from a
standstill, and what the rider sees is watts and cadence arriving from the trainer while the
speed sits at zero for five to ten seconds - at the start of a course, and again every time
they stop and start.

```gdscript
    # In your telemetry handler. Absent means the trainer cannot count cadence,
    # not that the rider has stopped, so only then fall back to your own model.
    if frame.has("speedMps"):
        speed_mps = float(frame["speedMps"]) * _draft_factor() * _surface_factor()
    else:
        speed_mps = _integrate_from_power(frame.get("power", 0), delta)
```

## Interpolate telemetry

`telemetry` arrives at 4 Hz. Rendering a power number that steps four times a second looks
broken even though the data is correct. Lerp toward the last received value.

```gdscript
var _power_display := 0.0
var _power_target := 0.0

func _on_telemetry(frame: Dictionary) -> void:
    # Absent means absent. `has()`, not `get(..., 0)`: a missing hr means no
    # strap, and defaulting to 0 puts a heart rate of zero on the HUD.
    if frame.has("power"):
        _power_target = float(frame["power"])
    _hr_visible = frame.has("hr")
    # What the rider is actually feeling, after clamping and the deadman.
    # Display this, not the gradient you sent.
    _applied_gradient = float(frame.get("appliedGradient", 0.0))

func _process(delta: float) -> void:
    _power_display = lerp(_power_display, _power_target, 1.0 - pow(0.001, delta))
```

## Gears

Build the display from `welcome.gears`, which carries real teeth.

```gdscript
# gears: [{ "i": 0, "ratio": 1.21, "label": "34 x 28", "loadFactor": 0.72 }, ...]
func gear_label(index: int) -> String:
    for g in _gears:
        if int(g.get("i", -1)) == index:
            return str(g.get("label", index))
    return str(index)


func request_shift(steps: int) -> void:
    _send({ "t": "requestShift", "steps": steps })   # max 10/s


func _on_event(frame: Dictionary) -> void:
    match frame.get("event", ""):
        "gearChanged":
            var index := int(frame.get("index", 0))
            var changed := bool(frame.get("changed", true))
            if not changed:
                _play_no_more_gears_cue()      # already at the limit
            else:
                _set_gear_display(index, frame.get("source", "screen"))
        "worldTimeout":
            _show_reconnecting()               # your own loop stalled
        "worldResumed":
            _hide_reconnecting()
        "controlRevoked":
            _show_rider_took_control()
        _:
            pass
```

`source` is `screen`, `zwiftClick` or `companion`. A shift you did not request means the rider
reached for the handlebar; animate it the same way.

## Discovery

**Godot has no built-in mDNS / DNS-SD**, which is exactly why the phone also broadcasts a UDP
beacon. `PacketPeerUDP` is in core, so discovery costs you no dependency at all: bind port
47601 and read.

```gdscript
extends Node
class_name CompanionFinder

const DISCOVERY_PORT := 47601
const STALE_AFTER := 15.0   # paired phones only announce every 5 s

signal phones_changed(phones: Array)

var _udp := PacketPeerUDP.new()
var _found := {}            # "ip:port" -> { ip, port, path, name, paired, proto, seen }


func _ready() -> void:
    # Binding to "*" is the default and is what lets broadcasts in.
    var err := _udp.bind(DISCOVERY_PORT)
    if err != OK:
        push_warning("Companion discovery unavailable (port %d busy). \
Use the QR or type the address." % DISCOVERY_PORT)


func _process(_delta: float) -> void:
    while _udp.get_available_packet_count() > 0:
        var text := _udp.get_packet().get_string_from_utf8()
        # The phone's address is where the packet came FROM. It is deliberately
        # not in the payload: the phone has several addresses and cannot know
        # which one you can reach, but the source of a packet that arrived is
        # reachable by construction.
        var ip := _udp.get_packet_ip()
        var parsed: Variant = JSON.parse_string(text)
        if typeof(parsed) != TYPE_DICTIONARY:
            continue
        var a: Dictionary = parsed
        if a.get("t", "") != "indoorbike.companion":
            continue                      # stray traffic on a shared port
        var key := "%s:%s" % [ip, a.get("port", 0)]
        _found[key] = {
            "ip": ip,
            "port": int(a.get("port", 0)),
            "path": String(a.get("path", "/companion")),
            "name": String(a.get("name", "Indoor Bike")),
            "proto": String(a.get("proto", "")),
            "paired": bool(a.get("paired", false)),
            "seen": Time.get_ticks_msec(),
        }
        phones_changed.emit(phones())


func phones() -> Array:
    var now := Time.get_ticks_msec()
    for key in _found.keys():
        if now - int(_found[key]["seen"]) > int(STALE_AFTER * 1000.0):
            _found.erase(key)
    return _found.values()


func url_for(phone: Dictionary) -> String:
    return "ws://%s:%d%s" % [phone["ip"], phone["port"], phone["path"]]
```

Show `phones()` in a picker, let the rider tap one, and ask them only for the six-character
pairing code. **The code is never broadcast** - discovery answers "which phone", pairing
answers "may you drive it" - so it stays a deliberate act.

`paired: true` means a controller already holds that ride. Show it, greyed, rather than hiding
it: a rider whose own game crashed and reconnected wants to see the phone is still there.

| Approach | Verdict |
|---|---|
| **UDP beacon (above)** | Ship this. Core Godot, no dependency, works on most home networks |
| Manual host + port + code entry | Always keep it. Some networks drop broadcast entirely |
| Parse the QR payload from a pasted string | Cheap. `indoorbike://companion?host=..&port=..&token=..&session=..` |
| Camera QR scan | Needs a GDExtension or a platform plugin |
| mDNS browse | Needs a GDExtension. The phone advertises `_indoorbike._tcp` for clients that can |

Parse the deep link rather than asking for four fields:

```gdscript
func parse_pairing_url(raw: String) -> Dictionary:
    # indoorbike://companion?host=192.168.1.42&port=47612&token=8F3K2M&session=3f9a1c
    var q := raw.get_slice("?", 1)
    var out := {}
    for pair in q.split("&", false):
        var kv := pair.split("=", true, 1)
        if kv.size() == 2:
            out[kv[0]] = kv[1].uri_decode()
    return out
```

Never hardcode the port: it is ephemeral and different every ride.

## Platform notes

- **Web export**: a page served over HTTPS cannot open a `ws://` socket. Companion mode is
  LAN-only plaintext, so a browser build needs to be served over plain HTTP or not at all.
- **Android/iOS export**: local network access needs the platform permission
  (`NSLocalNetworkUsageDescription` on iOS) or the socket fails with no useful error.
- **Threading**: `WebSocketPeer` is not thread-safe. Poll it from one node's `_process` and
  fan out with signals rather than touching it from a worker thread.
- **`OS.get_name()`** is a reasonable `client.platform`; it is only informational.

## Mock the phone while you build

Nothing in the protocol needs a trainer except the feel. A ~60-line mock server (Python
`websockets`, Node `ws`, whatever is quickest) that answers `hello` with a `welcome`, pushes
`telemetry` every 250 ms and echoes `gearChanged` on `requestShift` covers every code path you
will write. Build against that first, then plug in a real phone.
