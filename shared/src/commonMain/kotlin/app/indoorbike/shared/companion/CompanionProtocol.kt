/*
 * Copyright (C) 2026 Snapp Technology Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.indoorbike.shared.companion

/**
 * A protocol version, `major.minor`.
 *
 * While [major] is 0 a minor bump may break compatibility. That is ordinary
 * semver 0.x, and it is the honest description of a protocol being designed
 * alongside its first client. From 1.0 a minor bump is additive only: an
 * implementation of 1.0 must keep working against a 1.4 peer.
 */
data class ProtocolVersion(val major: Int, val minor: Int) : Comparable<ProtocolVersion> {

    override fun compareTo(other: ProtocolVersion): Int =
        if (major != other.major) major.compareTo(other.major) else minor.compareTo(other.minor)

    override fun toString(): String = "$major.$minor"

    companion object {
        /** Returns null rather than throwing: this parses data off a socket. */
        fun parse(text: String): ProtocolVersion? {
            val parts = text.trim().split('.')
            if (parts.size != 2) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            if (major < 0 || minor < 0) return null
            return ProtocolVersion(major, minor)
        }
    }
}

/**
 * An inclusive range of protocol versions an implementation can speak.
 *
 * Deliberately not validated in the constructor: a client can declare a `min`
 * above its `max`, and that is a negotiation failure with a useful message
 * rather than an exception thrown while parsing a frame.
 */
data class VersionRange(val min: ProtocolVersion, val max: ProtocolVersion) {
    val isEmpty: Boolean get() = min > max
    override fun toString(): String = "$min-$max"
}

/** The outcome of [CompanionProtocol.negotiate]. */
sealed interface Negotiation {
    data class Agreed(val version: ProtocolVersion) : Negotiation

    /** [message] is written to be shown to a human. See [CompanionProtocol.mismatchMessage]. */
    data class Rejected(val message: String) : Negotiation
}

/**
 * Constants and version negotiation for the Companion API.
 *
 * The published specification is [docs/COMPANION_API.md]; the reasoning behind
 * the split it describes is in `docs/LIVE_API.md`. The short version: in
 * Companion mode the companion owns the world, the rider's position and their
 * virtual speed, and this app owns the trainer, the gears and the recording.
 */
object CompanionProtocol {

    /**
     * What this build speaks.
     *
     * 0.7 made `finish` end the ride and nothing else. The socket stays open,
     * the app writes the ride to history on the spot, and the companion may
     * start another whenever it likes with `setState: active`. Before this the
     * app closed with 4005 on every finish, which made an evening of laps a
     * sequence of reconnections, each on a new port. 4005 now means the app has
     * left Companion mode, which is the only thing that actually ends a
     * session. **Not purely additive**: a companion that treated the close as
     * its cue to stop will simply never be told to stop, and one that waits for
     * a socket to shut before starting the next ride waits forever.
     *
     * 0.6 added permanent pairing: `hello.deviceId`, the `pairRequest` /
     * `paired` ceremony and the `challenge` / `pairProof` handshake, so two
     * devices that have met once recognise each other afterwards without a
     * code and without anybody tapping anything.
     *
     * 0.5 added the `drivetrain` frame, so a game can put the rider on the
     * bike the game thinks they are riding. Optional: leaving it out keeps the
     * drivetrain the rider chose in settings.
     *
     * 0.4 moved the moment a ride starts. The app now records nothing until
     * the companion sends `setState: active`, and there is no countdown when
     * it does. **This is the one change so far that is not purely additive**: a
     * companion that never sends it will connect, stream telemetry and feel the
     * road, and never start a ride. See `setState` in docs/COMPANION_API.md.
     *
     * 0.3 added the `controls` frame and the `controlPressed` event, so a
     * companion showing a menu on a screen across the room can borrow the
     * phone as a remote for it.
     *
     * 0.2 added three things, all of them additions rather than changes:
     * `telemetry.speedMps` (the drivetrain's own speed, so a companion no
     * longer has to integrate one out of power), the `course` frame (so a
     * companion ride is recorded like a route ride), and the `setState` frame
     * (so a game's own pause reaches the recording).
     *
     * [SUPPORTED] therefore still reaches back to 0.1. Nothing a 0.1 companion
     * sends has changed meaning, and everything 0.2 adds is optional in both
     * directions, so an older companion connects and behaves exactly as it
     * always did. A companion learns which of the new frames this build will
     * act on from `welcome.capabilities`, not from the version number.
     */
    val VERSION = ProtocolVersion(0, 7)
    val SUPPORTED = VersionRange(ProtocolVersion(0, 1), VERSION)

    /**
     * Frames beyond the base protocol that this build acts on, published in
     * `welcome.capabilities`.
     *
     * The version says what the wire format is; this says what the app will
     * actually do with it. A companion should branch on this rather than on
     * the negotiated version, for the same reason it should read
     * [HONOURED_WORLD_FIELDS] rather than assume.
     */
    val CAPABILITIES =
        listOf(
            "requestShift", "label", "summary", "course", "setState", "controls", "drivetrain",
            "pair",
        )

    /**
     * Longest encoded polyline a `course` frame may carry.
     *
     * Roughly a four-thousand-point road, which is a great deal more detail
     * than any card or share image can draw. An over-long shape is refused
     * whole rather than truncated: half a polyline is not a shorter road, it is
     * a corrupt one, and it would decode into a line running off to somewhere
     * the rider has never been.
     */
    const val MAX_COURSE_SHAPE_CHARS = 32_768

    /** Elevation samples a `course` frame may carry. The app's own listings use 64. */
    const val MAX_COURSE_PROFILE_SAMPLES = 1_024

    /** Longest course or ride name that will be stored, in characters. */
    const val MAX_COURSE_NAME_CHARS = 120

    /**
     * Longest title or hint on a control pad, in characters.
     *
     * Shorter than a course name because it is read at arm's length, over a
     * handlebar, by somebody pedalling. Anything that does not fit on one line
     * of a phone is not going to be read at all.
     */
    const val MAX_CONTROL_LABEL_CHARS = 48

    /** WebSocket path. The version is negotiated in-band, not in the path. */
    const val PATH = "/companion"

    /** DNS-SD service type. The port is ephemeral and lives in the SRV record. */
    const val SERVICE_TYPE = "_indoorbike._tcp"

    /**
     * Where the phone broadcasts [CompanionAnnouncement] datagrams, and so the
     * one port in this protocol that is fixed.
     *
     * It has to be: a listener cannot discover the port it should be listening
     * on. Everything else stays ephemeral, and the WebSocket port travels
     * inside the announcement.
     */
    const val DISCOVERY_PORT = 47601

    /** Marks a datagram as ours, so a listener can ignore whatever else shares the port. */
    const val DISCOVERY_MAGIC = "indoorbike.companion"

    /** How often the beacon goes out while nothing is driving the ride. */
    const val DISCOVERY_INTERVAL_MS = 1_000L

    /**
     * How often it goes out once a controller has connected.
     *
     * Slower rather than silent: an observer may still want to find the ride,
     * but nobody is hunting for a phone that is already busy, and a beacon
     * every second for the length of a ride is noise on somebody's network.
     */
    const val DISCOVERY_INTERVAL_PAIRED_MS = 5_000L

    /**
     * Telemetry rate, matched to the simulation engine's own tick. Anything
     * faster would be inventing data: the physics only moves four times a
     * second, and a companion is expected to interpolate for rendering.
     */
    const val TICK_MS = 250L

    /** Silence after which the road is no longer trusted. */
    const val WORLD_TIMEOUT_MS = 1_500L

    /**
     * How long the gradient takes to reach flat once the lease has expired.
     *
     * A ramp rather than a step, because dropping off a 12 % climb in a single
     * tick is a jolt the rider feels as the pedals falling away. Speed is
     * *held* rather than zeroed, so what they land on is an ordinary flat road
     * at their current pace rather than nothing at all.
     */
    const val WORLD_RAMP_MS = 2_000L

    /**
     * Longest close reason a WebSocket frame can carry, in bytes.
     *
     * A close frame's payload is capped at 125 bytes, two of which are the
     * code. Overrun it and the frame is invalid: the peer never sees a close at
     * all, the connection is torn down, and the companion is left with a bare
     * 1006 - losing exactly the code and reason the protocol went to the
     * trouble of sending. The full explanation belongs in the [ErrorFrame],
     * which has no such limit; the close reason is a summary.
     */
    const val MAX_CLOSE_REASON_BYTES = 123

    /**
     * Fit [text] inside [MAX_CLOSE_REASON_BYTES], trimming on a character
     * boundary so a multi-byte character is never cut in half.
     */
    fun closeReason(text: String): String {
        if (text.encodeToByteArray().size <= MAX_CLOSE_REASON_BYTES) return text
        val ellipsis = "..."
        val budget = MAX_CLOSE_REASON_BYTES - ellipsis.encodeToByteArray().size
        var end = text.length
        while (end > 0 && text.substring(0, end).encodeToByteArray().size > budget) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }

    /** Shift requests are coalesced above this. A held gamepad button is not an error. */
    const val MAX_SHIFT_REQUESTS_PER_SECOND = 10

    /** Matches the clamp route mode already applies to a GPX-derived gradient. */
    const val MIN_GRADIENT = -0.30
    const val MAX_GRADIENT = 0.30

    /**
     * Sanity bounds only. The real protection against a companion sending
     * nonsense is the force ceiling in the simulation engine, which compresses
     * rather than clips and so cannot take the rider's gears away.
     */
    const val MIN_SPEED_MPS = 0.0
    const val MAX_SPEED_MPS = 30.0

    /** World-frame fields this build acts on. Published in `welcome.honours`. */
    val HONOURED_WORLD_FIELDS = setOf("gradient", "speedMps", "distanceM", "ascentM")

    /** Structural, never warned about. */
    val WORLD_ENVELOPE_FIELDS = setOf("t", "seq")

    /**
     * Names the protocol reserves but this build does not honour.
     *
     * They are unnecessary while the companion owns speed: drafting, surfaces
     * and wind are all its own arithmetic, and none of them has to cross the
     * socket to be felt. They would only become meaningful if a future mode
     * handed speed back to the phone. Reserved rather than simply unknown so
     * that nobody uses `cda` to mean something else in the meantime.
     */
    val RESERVED_WORLD_FIELDS = setOf("crr", "cda", "airDensity", "headwindMps")

    /**
     * The gradient to ride given one that is [ageMs] old.
     *
     * Inside the lease this is [gradient] unchanged. Past it the value decays
     * linearly to flat over [WORLD_RAMP_MS] and stays there. Shared by
     * [CompanionRoad], which reports it, and [CompanionEngine], which rides it,
     * so a deadman with two formulas cannot drift into two behaviours.
     */
    fun rampedGradient(gradient: Double, ageMs: Long): Double {
        if (ageMs <= WORLD_TIMEOUT_MS) return gradient
        val into = ageMs - WORLD_TIMEOUT_MS
        if (into >= WORLD_RAMP_MS) return 0.0
        return gradient * (1.0 - into.toDouble() / WORLD_RAMP_MS)
    }

    /**
     * Pick the highest version both ends understand.
     *
     * Rejection carries a message naming both ranges and which end to update,
     * because "incompatible version" tells a rider nothing they can act on.
     */
    fun negotiate(
        server: VersionRange,
        client: VersionRange,
        clientName: String,
    ): Negotiation {
        if (client.isEmpty) {
            return Negotiation.Rejected(
                "Companion '" + clientName + "' declared an impossible protocol range (min " +
                    client.min + " is above max " + client.max + "). This build of Indoor Bike " +
                    "speaks " + server + "."
            )
        }
        val low = maxOf(server.min, client.min)
        val high = minOf(server.max, client.max)
        if (low > high) return Negotiation.Rejected(mismatchMessage(clientName, client, server))
        return Negotiation.Agreed(high)
    }

    /**
     * The human half of a version failure.
     *
     * The rule, in both directions: say what each end speaks and which end to
     * update. A rider staring at a game that will not connect needs to know
     * which of the two things they own is the old one, and no amount of
     * "incompatible version" will tell them.
     */
    fun mismatchMessage(
        clientName: String,
        client: VersionRange,
        server: VersionRange,
    ): String {
        val advice = if (client.min > server.max) {
            "Update Indoor Bike, or run a companion build that supports " + server.max + "."
        } else {
            "Update the companion, or run an Indoor Bike build that supports " + client.max + "."
        }
        return "Companion '" + clientName + "' speaks protocol " + client +
            "; this build of Indoor Bike speaks " + server + ". " + advice
    }

    /**
     * The same failure phrased for a companion that is rejecting *us*.
     *
     * Both ends must check: a server may legitimately choose a version the
     * client listed but has since deprecated, and a client that trusts
     * `welcome` blindly will then fail somewhere far less legible.
     */
    fun clientSideMismatchMessage(
        serverVersion: ProtocolVersion,
        clientRange: VersionRange,
    ): String {
        val advice = if (serverVersion > clientRange.max) {
            "Update this companion, or run an Indoor Bike build that supports " +
                clientRange.max + "."
        } else {
            "Update Indoor Bike, or run a companion build that supports " + serverVersion + "."
        }
        return "Indoor Bike chose protocol " + serverVersion + "; this companion speaks " +
            clientRange + ". " + advice
    }
}

/**
 * WebSocket close codes, all inside the application range.
 *
 * A fatal failure sends an [ErrorFrame] *and* closes with one of these plus a
 * reason string. Both are required: a client that fails negotiation may close
 * the socket before it processes the frame, and then the close reason is the
 * only thing it ever sees.
 */
object CompanionClose {
    const val PROTOCOL_MISMATCH = 4001
    const val UNAUTHORIZED = 4002
    const val CONTROLLER_BUSY = 4003
    const val CONTROL_REVOKED = 4004

    /**
     * The app has left Companion mode.
     *
     * Named for what it used to mean. Since 0.7 a `finish` ends the ride and
     * leaves the socket open, so this is sent when the rider backs out of
     * the mode altogether: the last thing a companion hears, and the one
     * close that really is the end of the evening.
     */
    const val RIDE_ENDED = 4005
    const val MALFORMED = 4008
}

/** Error codes carried by [ErrorFrame]. */
object CompanionError {
    const val PROTO_UNSUPPORTED = "protoUnsupported"
    const val UNAUTHORIZED = "unauthorized"
    const val CONTROLLER_BUSY = "controllerBusy"
    const val NOT_CONTROLLER = "notController"
    const val NO_TRAINER = "noTrainer"
    const val MALFORMED = "malformed"
}

/** Warning codes carried by [WarningFrame]. All non-fatal. */
object CompanionWarning {
    const val FIELD_NOT_HONOURED = "fieldNotHonoured"
    const val CLAMPED = "clamped"
    const val RATE_LIMITED = "rateLimited"

    /**
     * A value was dropped rather than trimmed to fit.
     *
     * Distinct from [CLAMPED] on purpose. A gradient held at 30 % is still a
     * road; a polyline cut off at 32 768 characters is a road that leaves the
     * map. Where trimming would produce something worse than nothing, the
     * field is refused and this says so.
     */
    const val REJECTED = "rejected"
}
