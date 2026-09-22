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
const PROTO_MAX := "0.6"
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
            # Nothing but the handshake until the welcome lands. A phone that
            # remembers you answers `hello` with a `challenge`, and any other
            # frame while that proof is outstanding is refused with 4002. A
            # pump at 10 Hz beats a challenge answered in more than 100 ms.
            if _welcomed:
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
        "capabilities": ["summary", "course", "setState", "controls", "drivetrain", "pair"],
        "deviceId": _device_id,
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

## Integers: `JSON.parse_string` hands you floats

`JSON.parse_string` returns every number as a `float`, and `JSON.stringify` writes a whole float
with its `.0`. So anything you loaded from JSON and send on unchanged goes out as `921.0` where
the protocol says integer. The usual victim is a baked route's elevation profile:

```gdscript
# The pack stores [921, 919, ...]; after JSON.parse_string they are floats,
# and JSON.stringify would send [921.0, 919.0, ...]. Convert on load.
var metres: Array[int] = []
for sample: Variant in pack["summary"]["profile"]:
    metres.append(roundi(float(sample)))
pack["summary"]["profile"] = metres
```

Current phone builds read `921.0`. Builds up to 2.2 refused the frame as `malformed`, and when
the frame was the `finish` the ride never ended: the socket closed, your game reconnected, and
the rider's next ride was saved on top of the last one. Integers you make in GDScript
(`roundi()`, `int()`, `_seq += 1`) stringify as integers and are fine. To check, print
`JSON.stringify(frame)` once and look for `.0` in an integer field.

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

## Pairing: HMACContext, and a nonce you must not reuse

Godot has everything this needs. `Crypto.generate_random_bytes` is the CSPRNG and `HMACContext`
does the tag; there is no curve to find and no library to add.

```gdscript
var _crypto := Crypto.new()
var _device_id := ""      # generated once, saved to user://, kept forever
var _pair_key := PackedByteArray()   # raw bytes, from the "paired" frame

func _hex(bytes: PackedByteArray) -> String:
    var out := ""
    for b in bytes:
        out += "%02x" % b
    return out

func _proof(role: String, app_nonce: String, my_nonce: String) -> String:
    var msg := "indoorbike-companion-v1:%s:%s:%s" % [role, app_nonce, my_nonce]
    var ctx := HMACContext.new()
    ctx.start(HashingContext.HASH_SHA256, _pair_key)
    ctx.update(msg.to_utf8_buffer())
    return _hex(ctx.finish())

func _on_challenge(frame: Dictionary) -> void:
    # A NEW nonce every connection. Never cache this.
    var my_nonce := _hex(_crypto.generate_random_bytes(16))
    _pending_app_nonce = frame["nonce"]
    _pending_my_nonce = my_nonce
    _send({
        "t": "pairProof",
        "nonce": my_nonce,
        "proof": _proof("companion", frame["nonce"], my_nonce),
    })

func _on_welcome(frame: Dictionary) -> void:
    if _pending_app_nonce != "":
        var want := _proof("app", _pending_app_nonce, _pending_my_nonce)
        if frame.get("proof", "") != want:
            _fail("The phone could not prove it holds the pairing key.")
            return
    _pending_app_nonce = ""
```

Store the key with `FileAccess` under `user://`, not in a `.tres` you might ship, and keep it
out of every `print()` you add while debugging. If the rider forgets you, the handshake comes
back `unauthorized`: drop the stored key and offer the pairing code again.

## Speed comes off the telemetry

`telemetry.speedMps` is the phone's model of the bike's speed: the rider's legs while pulling
away, so it is there on the first turn of the crank, and the bike's mass after, so a shift
cannot make it jump. Take it, apply your world's own effects, and send the result back as
`world.speedMps`.

It used to be the legs' speed all the way up, `cadence x development`. Right after an upshift
that is the old cadence times the new gear, a speed the bike does not have, and echoed back it
made every upshift a wall. If your world adds speed of its own, integrate it with mass for the
same reason.

Do not integrate a speed out of power instead. That models a bicycle accelerating from a
standstill, and what the rider sees is watts and cadence arriving from the trainer while the
speed sits at zero for five to ten seconds - at the start of a course, and again every time
they stop and start.

```gdscript
    # In your telemetry handler. Absent means the trainer cannot count cadence,
    # not that the rider has stopped, so only then fall back to your own model.
    if frame.has("speedMps"):
        speed_mps = float(frame["speedMps"]) * _draft_factor()
    else:
        speed_mps = _integrate_from_power(frame.get("power", 0), delta)
```

A surface is the exception to owning speed, and does not belong in that line. Slowing the
avatar on gravel while the pedals stay light is the thing every virtual ride gets wrong: the
rider sees a number fall and feels nothing. Send the rolling resistance instead, and the
trainer pushes back with it -

```gdscript
    var road := {
        "t": "world", "seq": seq, "gradient": gradient,
        "speedMps": speed_mps, "distanceM": distance_m, "ascentM": ascent_m,
    }
    # Only when the app says it acts on it; an unhonoured field earns a warning.
    if "crr" in welcome.get("honours", []):
        road["crr"] = _rolling_resistance_under_the_tyres()
    _send(road)
```

## Menus: hand the rider a remote

Godot menus are the case `controls` exists for. The rider is on the bike and your menu is on a
television; without this they cannot answer it.

Tell the app what is up and what will answer, and act on the presses that come back. The
`enabled` list should be built from the same state your own focus handling uses, so the two can
never disagree.

```gdscript
func show_menu(title: String, can_move: bool, can_confirm: bool) -> void:
    var enabled := PackedStringArray()
    if can_move:
        enabled.append_array(["up", "down"])
    if can_confirm:
        enabled.append("enter")
    enabled.append("back")
    _send({
        "t": "controls",
        "visible": true,
        "title": title,
        "enabled": Array(enabled),
    })

func hide_menu() -> void:
    # Never skip this. A pad left up is a remote wired to nothing.
    _send({"t": "controls", "visible": false})
```

Presses arrive as events, so feed them into the same input path the keyboard uses rather than a
second one that will drift:

```gdscript
func _on_event(frame: Dictionary) -> void:
    match frame.get("event", ""):
        "controlPressed":
            match frame.get("control", ""):
                "up":    _menu.focus_previous()
                "down":  _menu.focus_next()
                "left":  _menu.adjust(-1)
                "right": _menu.adjust(1)
                "enter": _menu.activate_focused()
                "back":  hide_menu()
```

Call `hide_menu()` from the menu's own close path, not from the `back` handler alone: the rider
can also close it on the television, and the pad has to go away either way.

## Brand: your icon and colours on the phone

Send it once, straight after a `welcome` that lists `brand` in its capabilities. Godot has
everything this needs built in: `FileAccess` for the bytes and `Marshalls` for base64.

```gdscript
const BRAND_ICON := "res://brand/icon_256.png"  # square PNG, 48 to 512 px

func _send_brand() -> void:
    if not "brand" in _welcome.get("capabilities", []):
        return
    var png := FileAccess.get_file_as_bytes(BRAND_ICON)
    var frame := {
        "t": "brand",
        "name": "Col de Berg",
        "icon": Marshalls.raw_to_base64(png),
        "colors": {"accent": "#E4572E", "header": "#3B1F2B"},
    }
    _socket.send_text(JSON.stringify(frame))
```

Export the icon as a **PNG** file, not a texture resource: `res://` PNGs are imported into
`.ctex` on export, and `get_file_as_bytes` on an exported build then finds nothing. Either keep
the PNG under a folder listed in the export's "Filters to export non-resource files", or build
the bytes at runtime with `Image.save_png_to_buffer()`:

```gdscript
var png := (preload("res://brand/icon_256.png") as Texture2D).get_image().save_png_to_buffer()
```

Change colours later without resending the picture, because fields merge:

```gdscript
_socket.send_text(JSON.stringify({"t": "brand", "colors": {"accent": "#3DA5D9"}}))
```

Route `warning` frames with code `adjusted` to your log while you design the palette. The
phone lightens an accent that disappears on its dark screen and says which colour it drew.

## Screenshots: capture, scale, encode

List `"screenshot"` in your `hello` capabilities and the phone sends `requestScreenshot` whenever
the rider taps its camera button. Two things matter in Godot.

**First, the send buffer.** `WebSocketPeer.outbound_buffer_size` defaults to 65 535 bytes, and a
single `send_text` larger than that fails with `ERR_OUT_OF_MEMORY`. A screenshot always is. Set it
on every new peer, before `connect_to_url`:

```gdscript
_socket = WebSocketPeer.new()
# A screenshot is hundreds of kB. The default 64 KiB refuses it outright.
_socket.outbound_buffer_size = 8 * 1024 * 1024
_socket.connect_to_url(url)
```

The same limit catches a large `brand` icon (up to 96 000 base64 characters), so set it whether
or not you do screenshots.

**Then the picture.** Wait for the frame to finish drawing, take the viewport's image, scale it to
`maxEdgePx`, and send it as JPEG:

```gdscript
func _on_request_screenshot(frame: Dictionary) -> void:
    # int(): JSON.parse_string made it a float, and 3.0 is not the id the phone sent.
    var id := int(frame.get("id", 0))
    await RenderingServer.frame_post_draw
    var image := get_viewport().get_texture().get_image()
    var edge := int(frame.get("maxEdgePx", 1920))
    var longest := maxi(image.get_width(), image.get_height())
    if longest > edge:
        var scale := float(edge) / longest
        image.resize(roundi(image.get_width() * scale), roundi(image.get_height() * scale),
            Image.INTERPOLATE_BILINEAR)
    var jpeg := image.save_jpg_to_buffer(0.85)
    _socket.send_text(JSON.stringify({
        "t": "screenshot", "id": id, "image": Marshalls.raw_to_base64(jpeg),
    }))
```

Route `requestScreenshot` to it from `_drain_incoming`. If the game cannot take one right now, a
loading screen say, answer `{"t": "screenshot", "id": id, "error": "Loading"}` rather than
nothing. `get_image()` and the JPEG encode run on the main thread and take tens of milliseconds
for a 1080p frame; your `world` pump is on its own timer, so it keeps the deadman fed, but do not
capture on a frame that is already late.

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
