#!/usr/bin/env python3
#
# Copyright (C) 2026 Snapp Technology Ltd
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, version 3 of the License.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
"""A companion, in one file, for exercising the Companion API against a phone.

The protocol has unit tests and they are fast and thorough, but they prove the
state machine agrees with itself. They cannot tell you that a `controls` frame
puts buttons on a tablet, that `setState: active` starts a ride with no
countdown, or that a `drivetrain` frame changes the ladder the rider is
shifting through. Only a real socket to a real phone does that, and until this
existed the only way to open one was to build a game.

So: no dependencies, no build step, no engine. Standard library only, including
a hand-rolled WebSocket client, because pulling in `websockets` would make the
one tool for reproducing a bug the one tool nobody has installed.

    python scripts/companion_probe.py --discover
    python scripts/companion_probe.py --host 192.168.86.23 --port 45809
    python scripts/companion_probe.py --host ... --scenario controls

Scenarios are in SCENARIOS at the bottom. Add one rather than editing another:
the value here is that a scenario you ran last month still runs the same way.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import socket
import struct
import zlib
import sys
import threading
import time
import uuid

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
DISCOVERY_PORT = 47601
DISCOVERY_MAGIC = "indoorbike.companion"
PATH = "/companion"

# Debug builds pin the pairing code so nobody has to squint at a tablet.
DEBUG_CODE = "AAAAAA"

# Permanent pairing. Kept in the user's home rather than beside the script, so
# a checkout is never a place where somebody's trainer key is sitting.
STATE_PATH = os.path.expanduser("~/.indoorbike_companion_probe.json")
PROOF_PREFIX = "indoorbike-companion-v1"


def load_state():
    try:
        with open(STATE_PATH, encoding="utf-8") as f:
            state = json.load(f)
    except (IOError, OSError, ValueError):
        state = {}
    # One identity, generated once and kept forever. It is not a credential:
    # it says which pairing to look up and the proof decides the rest.
    if not state.get("deviceId"):
        state["deviceId"] = str(uuid.uuid4())
        save_state(state)
    state.setdefault("pairings", {})
    return state


def save_state(state):
    try:
        with open(STATE_PATH, "w", encoding="utf-8") as f:
            json.dump(state, f, indent=2)
        # Best effort: on a POSIX box, keep the key out of other users' reach.
        try:
            os.chmod(STATE_PATH, 0o600)
        except OSError:
            pass
    except (IOError, OSError) as e:
        print("  ! could not save pairing state: %s" % e)


def pair_proof(key_hex, role, app_nonce, my_nonce):
    """hex(HMAC_SHA256(keyBytes, "prefix:role:appNonce:myNonce")).

    The key is the raw bytes the stored hex decodes to; everything else is the
    hex strings exactly as they crossed the wire.
    """
    message = "%s:%s:%s:%s" % (PROOF_PREFIX, role, app_nonce, my_nonce)
    return hmac.new(
        bytes.fromhex(key_hex), message.encode(), hashlib.sha256
    ).hexdigest()


# ── WebSocket, the little of it a companion needs ─────────────────────────────

class WebSocket:
    """Text frames over RFC 6455. Client-to-server frames are masked, as the
    spec requires and as Ktor enforces; server-to-server frames are not."""

    def __init__(self, host, port, path=PATH, timeout=10.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self._buf = b""
        self._handshake(host, port, path)

    def _handshake(self, host, port, path):
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            "GET %s HTTP/1.1\r\n"
            "Host: %s:%d\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n" % (path, host, port, key)
        )
        self.sock.sendall(request.encode())

        while b"\r\n\r\n" not in self._buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("socket closed during the handshake")
            self._buf += chunk
        head, self._buf = self._buf.split(b"\r\n\r\n", 1)
        headers = head.decode("latin-1").split("\r\n")
        if "101" not in headers[0]:
            raise ConnectionError("upgrade refused: " + headers[0])

        expected = base64.b64encode(
            hashlib.sha1((key + WS_GUID).encode()).digest()
        ).decode()
        got = next(
            (h.split(":", 1)[1].strip() for h in headers
             if h.lower().startswith("sec-websocket-accept")), None
        )
        if got != expected:
            raise ConnectionError("bad Sec-WebSocket-Accept; not a WebSocket peer")

    def _recv_exactly(self, n):
        while len(self._buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("socket closed")
            self._buf += chunk
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def send_text(self, text):
        payload = text.encode()
        header = bytearray([0x81])  # FIN + text
        mask = os.urandom(4)
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def recv(self):
        """One frame. Returns (kind, value): ("text", str) or ("close", reason)."""
        b0, b1 = self._recv_exactly(2)
        opcode = b0 & 0x0F
        length = b1 & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._recv_exactly(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._recv_exactly(8))[0]
        masked = bool(b1 & 0x80)
        mask = self._recv_exactly(4) if masked else b""
        payload = self._recv_exactly(length) if length else b""
        if masked:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))

        if opcode == 0x1:
            return "text", payload.decode("utf-8", "replace")
        if opcode == 0x8:
            code = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else 0
            return "close", "%d %s" % (code, payload[2:].decode("utf-8", "replace"))
        if opcode == 0x9:  # ping
            self.sock.sendall(b"\x8a\x80" + os.urandom(4))
            return "ping", ""
        return "other", ""

    def close(self):
        try:
            self.sock.sendall(b"\x88\x80" + os.urandom(4))
        except OSError:
            pass
        self.sock.close()


# ── Discovery ─────────────────────────────────────────────────────────────────

def discover(timeout=8.0):
    """Wait for the phone's UDP beacon. Its source address is the one to use:
    the payload deliberately does not name an address, because the datagram
    already arrived from one that is reachable."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", DISCOVERY_PORT))
    sock.settimeout(timeout)
    deadline = time.time() + timeout
    try:
        while time.time() < deadline:
            try:
                data, addr = sock.recvfrom(2048)
            except socket.timeout:
                break
            try:
                frame = json.loads(data.decode())
            except ValueError:
                continue
            if frame.get("t") == DISCOVERY_MAGIC:
                return addr[0], int(frame["port"]), frame
    finally:
        sock.close()
    return None, None, None


# ── The companion ─────────────────────────────────────────────────────────────

def godot_spelling(value):
    """Every whole number spelled the way Godot writes it: 921 becomes 921.0.

    Godot's JSON.parse_string makes every number a float and JSON.stringify
    writes a whole float with its `.0`, so a game that loads anything from JSON
    sends integers like this without meaning to. Indoor Bike 2.2 refused them
    where an integer belongs, and a `finish` spelled this way never ended the
    ride.
    """
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return float(value)
    if isinstance(value, dict):
        return {k: godot_spelling(v) for k, v in value.items()}
    if isinstance(value, list):
        return [godot_spelling(v) for v in value]
    return value


class Probe:
    def __init__(self, ws, verbose=True, state=None, godot_numbers=False):
        self.ws = ws
        self.verbose = verbose
        self.godot_numbers = godot_numbers
        self.state = state if state is not None else {"deviceId": "probe", "pairings": {}}
        self.seq = 0
        self.paired_ok = None      # True/False once a challenge has been answered
        self.pair_result = None    # "paired" / "declined" once asked
        self.welcome = None
        self.telemetry = None
        self.events = []
        self.warnings = []
        self.errors = []
        self.gears = []
        self._stop = threading.Event()
        self._reader = threading.Thread(target=self._read_loop, daemon=True)

    def start(self):
        self._reader.start()

    def stop(self):
        self._stop.set()

    def _read_loop(self):
        while not self._stop.is_set():
            try:
                kind, value = self.ws.recv()
            except Exception:
                return
            if kind == "close":
                print("  <- CLOSE %s" % value)
                self._stop.set()
                return
            if kind != "text":
                continue
            try:
                frame = json.loads(value)
            except ValueError:
                continue
            self._on_frame(frame)

    def _on_frame(self, frame):
        t = frame.get("t")
        if t == "welcome":
            self.welcome = frame
            self.gears = frame.get("gears", [])
            self._check_app_proof(frame)
            print("  <- welcome proto=%s app=%s role=%s" % (
                frame.get("proto"), frame.get("app"), frame.get("role")))
            print("     capabilities: %s" % ", ".join(frame.get("capabilities", [])))
            print("     %d gears, currently %d" % (len(self.gears), frame.get("gearIndex", -1)))
        elif t == "challenge":
            self._answer_challenge(frame)
        elif t == "paired":
            self.state["pairings"][frame["deviceId"]] = frame["key"]
            save_state(self.state)
            self.pair_result = "paired"
            print("  <- PAIRED with %s. Key stored; no code needed next time." % frame.get("name"))
        elif t == "pairDeclined":
            self.pair_result = "declined"
            print("  <- pairing declined: %s" % frame.get("reason"))
        elif t == "telemetry":
            self.telemetry = frame
        elif t == "event":
            self.events.append(frame)
            extra = {k: v for k, v in frame.items()
                     if k not in ("t", "event", "gears")}
            if "gears" in frame and frame["gears"]:
                self.gears = frame["gears"]
                extra["gears"] = "%d gears" % len(frame["gears"])
            print("  <- EVENT %s %s" % (frame.get("event"), extra))
        elif t == "warning":
            self.warnings.append(frame)
            print("  <- WARNING [%s] %s: %s" % (
                frame.get("code"), frame.get("field"), frame.get("message", "")))
        elif t == "error":
            self.errors.append(frame)
            print("  <- ERROR [%s] %s" % (frame.get("code"), frame.get("message", "")))

    def _answer_challenge(self, frame):
        app_device = frame.get("deviceId", "")
        key = self.state["pairings"].get(app_device)
        if not key:
            print("  <- CHALLENGE from %s but no key stored for it" % app_device)
            self.paired_ok = False
            return
        # A fresh nonce every connection. Caching this would be signing
        # something only the app chose, which is most of the way back to a
        # recording being useful.
        my_nonce = os.urandom(16).hex()
        self._challenge = (key, frame["nonce"], my_nonce)
        print("  <- challenge from a phone we know; proving it")
        self.send({
            "t": "pairProof",
            "nonce": my_nonce,
            "proof": pair_proof(key, "companion", frame["nonce"], my_nonce),
        })

    def _check_app_proof(self, frame):
        """The half a companion is most likely to skip, and must not."""
        pending = getattr(self, "_challenge", None)
        if not pending:
            return
        key, app_nonce, my_nonce = pending
        self._challenge = None
        want = pair_proof(key, "app", app_nonce, my_nonce)
        if hmac.compare_digest(want, frame.get("proof", "")):
            self.paired_ok = True
            print("  <- the phone proved itself too. Connected with no code.")
        else:
            self.paired_ok = False
            print("  !! the phone could NOT prove it holds the pairing key")

    def send(self, frame):
        if self.godot_numbers:
            frame = godot_spelling(frame)
        if self.verbose:
            print("  -> %s" % json.dumps(frame))
        self.ws.send_text(json.dumps(frame))

    def hello(self, code, name="companion_probe"):
        # Both the token and the deviceId, always. If the phone knows us it
        # challenges and ignores the code; if it does not, the code gets us in.
        # Sending both means never having to guess which phone this is.
        self.send({
            "t": "hello",
            "protoMin": "0.1", "protoMax": "0.8",
            "token": code,
            "deviceId": self.state["deviceId"],
            "client": {"name": name, "version": "1.0", "platform": sys.platform},
            "role": "controller",
            "capabilities": [
                "summary", "course", "setState", "controls", "drivetrain", "pair", "brand",
            ],
        })
        for _ in range(50):
            if self.welcome or self.errors:
                return self.welcome is not None
            time.sleep(0.1)
        return False

    def world(self, gradient, speed_mps, distance_m=None, ascent_m=None):
        self.seq += 1
        frame = {"t": "world", "seq": self.seq,
                 "gradient": round(gradient, 4), "speedMps": round(speed_mps, 3)}
        if distance_m is not None:
            frame["distanceM"] = round(distance_m, 1)
        if ascent_m is not None:
            frame["ascentM"] = round(ascent_m, 1)
        if self.godot_numbers:
            frame = godot_spelling(frame)
        # Too chatty to echo at 10 Hz; the ride loop prints a summary instead.
        self.ws.send_text(json.dumps(frame))

    def drive(self, seconds, gradient=0.0, hz=10.0, distance_m=0.0):
        """Pump world frames, taking speed from telemetry the way the docs say
        to: the drivetrain's own speed is known on the first turn of the crank,
        and integrating one out of power is what makes speed sit at zero."""
        end = time.time() + seconds
        distance = distance_m
        last_print = 0.0
        while time.time() < end and not self._stop.is_set():
            speed = 0.0
            if self.telemetry and self.telemetry.get("speedMps") is not None:
                speed = float(self.telemetry["speedMps"])
            distance += speed / hz
            self.world(gradient, speed, distance_m=distance)
            if time.time() - last_print > 1.0:
                last_print = time.time()
                t = self.telemetry or {}
                print("     ride: %s W, %s rpm, drivetrain %s m/s, gear %s, %s N" % (
                    t.get("power"), t.get("cadence"), t.get("speedMps"),
                    t.get("gearIndex"), t.get("trainerForceN")))
            time.sleep(1.0 / hz)
        return distance


# ── Scenarios ─────────────────────────────────────────────────────────────────

def scenario_smoke(p, args):
    """Everything, in the order a real companion would do it."""
    print("\n[1] the bike the game says the rider is on")
    p.send({"t": "drivetrain", "name": "Probe bike",
            "chainrings": [50, 34],
            "sprockets": [11, 12, 13, 14, 15, 17, 19, 21, 23, 25, 28, 32],
            "wheelCircumferenceMm": 2105})
    time.sleep(1.0)

    print("\n[2] a bike that cannot exist, which must be refused by name")
    p.send({"t": "drivetrain", "chainrings": [50], "sprockets": [0, 11]})
    time.sleep(1.0)

    print("\n[3] name the ride")
    p.send({"t": "course", "name": "Probe lap", "world": "Test harness",
            "distanceM": 5000, "ascentM": 60})
    time.sleep(0.5)

    # The road has to come first. The phone hands off from pairing to the
    # HUD on the *first world frame*, and the control pad lives on the HUD,
    # so a menu sent before this one would have nowhere to appear.
    print("\n[4] rolling, but not recording yet")
    p.drive(3.0, gradient=0.0)
    state = (p.telemetry or {}).get("state")
    print("     state before the start frame: %s" % state)

    print("\n[5] a menu, with only some of the pad live")
    p.send({"t": "controls", "visible": True, "title": "Probe menu",
            "hint": "Only up, down and enter should be pressable",
            "enabled": ["up", "down", "enter"]})
    print("     look at the phone: left, right and back should be visibly dead")
    # Still pumping the road. A menu does not stop the deadman, and a rider
    # can still be pedalling while they read one.
    p.drive(args.menu_seconds, gradient=0.0)
    p.send({"t": "controls", "visible": False})

    print("\n[6] start the ride. No countdown.")
    p.send({"t": "setState", "state": "active"})
    time.sleep(1.5)
    state = (p.telemetry or {}).get("state")
    print("     state after: %s" % state)
    if state != "active":
        print("     (nobody is pedalling, so the ride auto-paused straight back to")
        print("      standby. That is the stop-pedalling watchdog working, not a")
        print("      protocol failure. Put a rider on the trainer to see it hold.)")

    print("\n[7] ride, with a hill in it")
    distance = p.drive(args.ride_seconds / 2, gradient=0.0)
    print("     ... onto a 6 %% climb")
    distance = p.drive(args.ride_seconds / 2, gradient=0.06, distance_m=distance)

    print("\n[8] shift down twice")
    p.send({"t": "requestShift", "steps": -1})
    time.sleep(0.4)
    p.send({"t": "requestShift", "steps": -1})
    time.sleep(1.0)

    print("\n[9] pause, then resume")
    p.send({"t": "setState", "state": "paused"})
    p.drive(2.0, gradient=0.0)
    p.send({"t": "setState", "state": "active"})
    time.sleep(1.0)

    if args.finish:
        print("\n[10] finish, handing over the shape of the road")
        p.send({"t": "finish", "summary": {
            "shape": "_p~iF~ps|U_ulLnnqC", "profile": [10, 24, 41, 18]}})
        time.sleep(1.5)
    else:
        print("\n[10] leaving the ride running (--finish to end it)")


def scenario_world(p, args):
    """Just drive the trainer. For feeling a gradient with your legs."""
    print("\npumping world frames at %.0f %% for %ds" % (
        args.gradient * 100, args.ride_seconds))
    p.send({"t": "setState", "state": "active"})
    p.drive(args.ride_seconds, gradient=args.gradient)


def make_icon_png(side=128):
    """A square PNG built with the standard library, so the probe needs no images.

    A warm gradient with a white mountain on it: enough shape to see that the
    phone drew the picture it was sent rather than its monogram fallback.
    """
    rows = []
    for y in range(side):
        row = bytearray([0])  # filter byte: none
        for x in range(side):
            t = y / (side - 1)
            r, g, b = int(228 - 60 * t), int(87 + 30 * t), int(46 + 70 * t)
            # A mountain: inside the triangle with its peak at the top centre.
            peak_y, base_y = side * 0.28, side * 0.78
            half = (y - peak_y) / (base_y - peak_y) * side * 0.38
            inside = peak_y <= y <= base_y and abs(x - side / 2) <= half
            row += bytes((255, 255, 255, 255)) if inside else bytes((r, g, b, 255))
        rows.append(bytes(row))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", side, side, 8, 6, 0, 0, 0)  # 8-bit RGBA
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
            chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))


def scenario_brand(p, args):
    """Put the companion's look on the phone, then drive a road under it.

    Also sends one colour the phone cannot draw legibly, so the `adjusted`
    warning can be seen, and then puts the real one back.
    """
    icon = base64.b64encode(make_icon_png()).decode()
    print("\n[1] name, icon (%d base64 chars) and colours" % len(icon))
    p.send({"t": "brand", "name": "Probe Peaks", "icon": icon,
            "colors": {"accent": "#E4572E", "header": "#3B1F2B"}})
    p.send({"t": "course", "name": "Probe loop", "world": "Test harness"})
    p.drive(2.0, gradient=0.0)

    print("\n[2] an accent too dark for a dark screen: expect an 'adjusted' warning")
    p.send({"t": "brand", "colors": {"accent": "#1B2A5A"}})
    p.drive(2.0, gradient=0.0)

    print("\n[3] the real colours back, icon untouched (fields merge)")
    p.send({"t": "brand", "colors": {"accent": "#E4572E", "header": "#3B1F2B"}})

    print("\n[4] riding for %ds under the brand; look at the phone" % args.ride_seconds)
    p.send({"t": "setState", "state": "active"})
    p.drive(args.ride_seconds, gradient=0.03)


def scenario_controls(p, args):
    """Put a menu up and report what the rider presses."""
    enabled = args.enabled.split(",") if args.enabled else ["up", "down", "enter", "back"]
    print("\nmenu up with %s enabled; press them on the phone" % enabled)
    p.send({"t": "controls", "visible": True, "title": "Probe menu",
            "hint": "Press anything", "enabled": enabled})
    seen_before = len(p.events)
    # Pumping the road throughout, for the same reason the smoke scenario does.
    p.drive(args.menu_seconds, gradient=0.0)
    p.send({"t": "controls", "visible": False})
    presses = [e for e in p.events[seen_before:] if e.get("event") == "controlPressed"]
    print("\n%d press(es): %s" % (len(presses), [e.get("control") for e in presses]))


def ride_once(p, args, lap, distance_m=0.0, late_course=False):
    """The shortest thing that counts as a ride: name it, start it, pedal, end it.

    `distance_m` carries on from the previous lap on purpose. A game is allowed
    to count distance for the whole session, and the phone has to record each
    ride as its own, not as everything since the session began.
    """
    name = "Probe lap %d" % lap
    if not late_course:
        p.send({"t": "course", "name": name, "world": "Test harness"})
    print("  [lap %d] starting the ride" % lap)
    seen = len(p.events)
    p.send({"t": "setState", "state": "active"})
    time.sleep(1.0)
    distance = p.drive(args.ride_seconds, gradient=0.02, distance_m=distance_m)
    print("  [lap %d] finishing at %.0f m on the game's counter" % (lap, distance))
    summary = {"shape": "_p~iF~ps|U_ulLnnqC", "profile": [10, 24, 41]}
    p.send({"t": "finish", "summary": summary})

    finished = wait_for_state(p, "finished", seen, 6.0)
    print("  [lap %d] phone said the ride finished: %s" % (lap, "yes" if finished else "NO"))
    if not p._stop.is_set():
        print("  [lap %d] socket still open after the finish: yes" % lap)
    else:
        print("  [lap %d] SOCKET CLOSED. 0.7 keeps it open across rides." % lap)
        return False, distance

    if late_course:
        # The half a game with a free-roaming world actually does: the polyline
        # is only known once the rider has stopped drawing it. The phone should
        # fold it into the ride it has already written.
        print("  [lap %d] sending the course AFTER the finish" % lap)
        p.send({"t": "course", "name": name, "world": "Test harness",
                "shape": "_p~iF~ps|U_ulLnnqC", "profile": [10, 24, 41, 52]})
        time.sleep(1.5)

    # Back to a state that can start another ride.
    ready = wait_for_state(p, "paused", seen, 6.0)
    print("  [lap %d] phone is ready for another ride: %s" % (lap, "yes" if ready else "NO"))
    return finished, distance


def wait_for_state(p, state, since, seconds):
    """True once a stateChanged for `state` shows up after index `since`."""
    deadline = time.time() + seconds
    while time.time() < deadline and not p._stop.is_set():
        for e in p.events[since:]:
            if e.get("event") == "stateChanged" and e.get("state") == state:
                return True
        time.sleep(0.2)
    return False


def scenario_pair(p, args):
    """Ask to be remembered, then prove it worked."""
    if p.welcome is not None and p.paired_ok:
        print("\nalready paired: this connection needed no code at all.")
        return

    print("\nasking to be remembered; accept the prompt on the phone")
    p.send({"t": "pairRequest", "name": "companion_probe"})
    deadline = time.time() + args.menu_seconds
    while time.time() < deadline and p.pair_result is None and not p._stop.is_set():
        # Keep the road coming: a dialog does not stop the deadman.
        p.drive(0.5, gradient=0.0)
    if p.pair_result == "paired":
        print("\nrun again without --scenario pair to connect with no code.")
    elif p.pair_result is None:
        print("\nnobody answered the prompt.")


SCENARIOS = {
    "brand": scenario_brand,
    "pair": scenario_pair,
    "smoke": scenario_smoke,
    "world": scenario_world,
    "controls": scenario_controls,
}


def main():
    ap = argparse.ArgumentParser(description="Drive the Indoor Bike Companion API.")
    ap.add_argument("--host")
    ap.add_argument("--port", type=int)
    ap.add_argument("--code", default=DEBUG_CODE,
                    help="pairing code (debug builds pin it to %s)" % DEBUG_CODE)
    ap.add_argument("--discover", action="store_true",
                    help="find the phone via its UDP beacon on %d" % DISCOVERY_PORT)
    ap.add_argument("--scenario", default="smoke",
                    choices=sorted(list(SCENARIOS) + ["twice", "drop"]))
    ap.add_argument("--ride-seconds", type=float, default=10.0)
    ap.add_argument("--menu-seconds", type=float, default=8.0)
    ap.add_argument("--gradient", type=float, default=0.0)
    ap.add_argument("--enabled", help="comma-separated controls for the controls scenario")
    ap.add_argument("--finish", action="store_true", help="end the ride at the end")
    ap.add_argument("--godot-numbers", action="store_true",
                    help="spell every whole number as Godot does (921.0), in every frame")
    args = ap.parse_args()

    host, port = args.host, args.port
    if args.discover or not (host and port):
        print("listening for the beacon on UDP %d ..." % DISCOVERY_PORT)
        host, port, beacon = discover()
        if not host:
            print("no beacon. Is a companion ride prepared on the phone, and are "
                  "you on the same network? Pass --host and --port to skip this.")
            return 2
        print("found %s:%d (%s, proto %s)" % (
            host, port, beacon.get("app"), beacon.get("proto")))

    state = load_state()
    print("this probe's deviceId: %s" % state["deviceId"])
    if state["pairings"]:
        print("paired with %d phone(s) already" % len(state["pairings"]))

    if args.scenario == "twice":
        return run_twice(args, host, port, state)
    if args.scenario == "drop":
        return run_drop(args, host, port, state)

    probe, ws = connect(host, port, args, state)
    if probe is None:
        return 1
    try:
        SCENARIOS[args.scenario](probe, args)
        time.sleep(0.5)
    finally:
        probe.stop()
        ws.close()

    print("\n%d warning(s), %d error(s)" % (len(probe.warnings), len(probe.errors)))
    return 1 if probe.errors else 0


def connect(host, port, args, state):
    """Open the socket and get through the handshake. (None, None) if refused."""
    print("\nconnecting to ws://%s:%d%s" % (host, port, PATH))
    ws = WebSocket(host, port)
    probe = Probe(ws, state=state, godot_numbers=args.godot_numbers)
    probe.start()
    if not probe.hello(args.code):
        print("handshake refused. Wrong pairing code, or a pairing the phone has forgotten?")
        probe.stop()
        ws.close()
        return None, None
    if probe.paired_ok:
        print("connected by pairing, no code involved")
    return probe, ws


def run_drop(args, host, port, state):
    """The game vanishes mid-ride without `finish`, then comes back.

    What the phone should do, and what this checks: the ride pauses the moment
    the socket goes, with no countdown to an ending; it stays paused for as long
    as the game is away (`--menu-seconds`, so try a long one); and when the
    same game reconnects the ride carries on by itself, as the same ride, with
    a `stateChanged: active` to say so. Then it finishes properly.
    """
    probe, ws = connect(host, port, args, state)
    if probe is None:
        return 1
    probe.send({"t": "course", "name": "Probe drop", "world": "Test harness"})
    probe.send({"t": "setState", "state": "active"})
    ridden = probe.drive(args.ride_seconds / 2, gradient=0.02)
    print("\n[1] dropping the socket mid-ride, no finish, after %.0f m" % ridden)
    probe.stop()
    ws.close()

    print("[2] away for %ds. The phone should say 'Ride paused' and not end anything" % args.menu_seconds)
    time.sleep(args.menu_seconds)

    print("[3] the same game reconnects")
    probe, ws = connect(host, port, args, state)
    if probe is None:
        return 1
    ok = True
    try:
        resumed = wait_for_state(probe, "active", 0, 6.0)
        print("     ride carried on by itself: %s" % ("yes" if resumed else "NO"))
        ok = ok and resumed
        probe.drive(args.ride_seconds / 2, gradient=0.02, distance_m=ridden)
        seen = len(probe.events)
        probe.send({"t": "finish"})
        finished = wait_for_state(probe, "finished", seen, 6.0)
        print("[4] finished properly: %s" % ("yes" if finished else "NO"))
        ok = ok and finished
    finally:
        probe.stop()
        ws.close()
    print("\n%s. %d warning(s), %d error(s)" % (
        "the ride survived the drop" if ok else "FAILED", len(probe.warnings), len(probe.errors)))
    return 0 if ok and not probe.errors else 1


def run_twice(args, host, port, state):
    """A session, which is what an evening actually is: several rides, one socket.

    Since 0.7 a `finish` ends the ride and nothing else. The phone writes the
    ride to history on the spot, with nobody tapping anything, and stays
    connected so the next one can start. That is the whole point: a rider on a
    bike does not get off it to confirm a lap.

    So this rides twice down one connection and checks the three things that
    were each broken at some point: that the ride is written without a tap,
    that the socket survives the finish, and that the second lap is recorded as
    its own distance rather than everything since the session began. The second
    lap sends its course *after* the finish, which the specification allows and
    which a free-roaming game has no way to avoid.
    """
    probe, ws = connect(host, port, args, state)
    if probe is None:
        return 1
    ok = True
    distance = 0.0
    try:
        for lap in (1, 2):
            print("\n" + "-" * 62)
            finished, distance = ride_once(
                probe, args, lap, distance_m=distance, late_course=(lap == 2)
            )
            if not finished:
                ok = False
                break
    finally:
        probe.stop()
        ws.close()

    print("\n" + "=" * 62)
    if ok:
        print("two rides, one connection, nothing tapped on the phone.")
        print("check History: two rides, each with its own distance.")
    else:
        print("FAILED: the session could not run two rides.")
    print("\n%d warning(s), %d error(s)" % (len(probe.warnings), len(probe.errors)))
    return 0 if ok and not probe.errors else 1


if __name__ == "__main__":
    sys.exit(main())
