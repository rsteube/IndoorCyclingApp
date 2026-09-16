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

import kotlinx.serialization.Serializable

/**
 * Every frame on the Companion socket, in both directions.
 *
 * ## Why these are plain classes rather than a polymorphic hierarchy
 *
 * kotlinx.serialization can dispatch on a discriminator for a sealed
 * hierarchy, but it *throws* on a discriminator it has never heard of. The
 * protocol requires the opposite: an unknown frame type must be ignored, never
 * fatal, because that is what lets a 0.1 peer sit on a socket with a 0.4 peer
 * that has learned to say something new. So [CompanionCodec] reads the `t`
 * field itself and dispatches, and each frame here is serialised on its own.
 *
 * The `t` field is a defaulted property rather than a computed one so that it
 * round-trips: it is written on encode and simply ignored on decode.
 *
 * Optional fields are nullable and default to null, and the codec is
 * configured not to write nulls. A field the sender has no value for is
 * **omitted, not zeroed** - a missing `hr` means no strap, not a heart rate of
 * zero. That is the same discipline the FIT writer uses with its invalid
 * sentinels, and for the same reason.
 */
sealed interface CompanionFrame

/** Frames a companion sends to the app. */
sealed interface ClientFrame : CompanionFrame

/** Frames the app sends to a companion. */
sealed interface ServerFrame : CompanionFrame

// Client to server ───────────────────────────────────────────────────────────

/** Who is connecting, and what they can speak. Always the first frame. */
@Serializable
data class HelloFrame(
    val protoMin: String,
    val protoMax: String,
    val token: String? = null,
    /**
     * Stable, opaque identity for this companion, if it has one.
     *
     * Generate it once, keep it forever, and never reuse one between two
     * companions. A UUID is the obvious choice. It is not a secret and not a
     * credential: it says which stored pairing to look up, and the proof that
     * follows is what actually decides anything.
     *
     * Present with no [token] and a pairing on file, the app answers with a
     * [ChallengeFrame] rather than a [WelcomeFrame], and the rider is never
     * asked for a code. Absent, or unknown, and the handshake is the one it
     * always was.
     */
    val deviceId: String? = null,
    val client: ClientInfo = ClientInfo(),
    /** `controller` (default) or `observer`. Unknown values fall back to observer. */
    val role: String = ROLE_CONTROLLER,
    val capabilities: List<String> = emptyList(),
    val t: String = "hello",
) : ClientFrame {
    companion object {
        const val ROLE_CONTROLLER = "controller"
        const val ROLE_OBSERVER = "observer"
    }
}

@Serializable
data class ClientInfo(
    val name: String = "Unnamed companion",
    val version: String? = null,
    val platform: String? = null,
)

/**
 * The road under the rider, and how fast they are covering it.
 *
 * [speedMps] is required and not an afterthought: the app computes the force to
 * command from the road force at this speed, because the drag term is
 * `0.5 * rho * CdA * v^2`. A companion that omits it leaves the trainer with a
 * road that has no air in it.
 *
 * [distanceM] and [ascentM] are cumulative rather than per-frame deltas, so a
 * dropped frame costs nothing and a reconnecting companion resynchronises by
 * stating the total. They are how a game ride gets a real distance and
 * elevation profile into the ride record instead of a flat-road estimate, and
 * they are not a position: how far and how much up, never where.
 */
@Serializable
data class WorldFrame(
    val seq: Long,
    val gradient: Double,
    val speedMps: Double,
    val distanceM: Double? = null,
    val ascentM: Double? = null,
    /**
     * Rolling resistance under the tyres, if the companion models surfaces.
     *
     * Absent means tarmac ([CompanionProtocol.DEFAULT_CRR]), which is what
     * every companion written before this field meant by saying nothing. A
     * gravel road is about 0.012 and a grass track 0.016; the bounds are in
     * `welcome.limits`.
     */
    val crr: Double? = null,
    val t: String = "world",
) : ClientFrame

/**
 * Ask the app to shift. [steps] is signed; positive is a harder gear.
 *
 * The companion has no shifting logic of its own. It does not know the
 * drivetrain's ratios or its limits, and it does not decide what a shift means:
 * it asks, and the app answers with a `gearChanged` event whether or not the
 * index actually moved.
 */
@Serializable
data class RequestShiftFrame(
    val steps: Int,
    val t: String = "requestShift",
) : ClientFrame

/**
 * The road the rider is on, so a companion ride can be recorded like a route
 * ride instead of like a column of numbers.
 *
 * A route ride's record is thin because its route is not: the session names a
 * course in the catalogue and the post-ride screen, the share image and the
 * history entry all fetch it. A companion ride has no catalogue entry, so the
 * course has to travel with it. That is all this is.
 *
 * **Send it whenever you like, as often as you like.** Fields merge: a frame
 * that omits one leaves whatever was already known alone, so the usual shape
 * is a `name` at the start (the ride is called something useful while it is
 * still happening) and a `shape` at the end (that is when a free-roaming game
 * knows what road the rider actually took). Every field is optional, and a
 * companion that sends none of this saves exactly as it did before.
 *
 * Nothing here is a position. It describes the course, never where on the
 * course the rider is, which is the same line the live protocol draws.
 *
 * @param name what to call the ride, e.g. "Watopia Volcano Circuit". Becomes
 *   the saved session's name, so it is also what [LabelFrame] sets - send
 *   either.
 * @param world the game or world it was in, shown beside the name.
 * @param distanceM the *course's* length, which is not how far the rider got.
 *   That is `world.distanceM`.
 * @param ascentM the course's own total climb, on the same footing.
 * @param shape the road as a precision-5 encoded polyline, the same format the
 *   app's own route listings use. Capped at
 *   [CompanionProtocol.MAX_COURSE_SHAPE_CHARS]; an over-long shape is refused
 *   with a warning rather than truncated, because half a polyline is not a
 *   shorter road, it is a corrupt one.
 * @param profile elevation in whole metres, evenly spaced along [shape]. 64
 *   samples is plenty and is what the app's own listings carry; anything past
 *   [CompanionProtocol.MAX_COURSE_PROFILE_SAMPLES] is refused.
 */
@Serializable
data class CourseFrame(
    val name: String? = null,
    val world: String? = null,
    val distanceM: Double? = null,
    val ascentM: Double? = null,
    val shape: String? = null,
    val profile: List<Int> = emptyList(),
    val t: String = "course",
) : ClientFrame

/**
 * Pause or resume the ride on the phone.
 *
 * A game that has its own pause - a menu, a loading screen, a rider who
 * stopped - can stop the recording with it, so the ride record does not
 * quietly accumulate several minutes of zeroes that the rider never rode. On
 * resume the timer picks up where it left off, exactly as if the rider had
 * used the phone's own pause button.
 *
 * [state] is [CompanionState.ACTIVE] or [CompanionState.PAUSED]. Anything else
 * is refused with a warning rather than guessed at: a misspelt state that
 * silently paused a ride would be far worse than one that did nothing.
 *
 * This is a request, not a command, and it can legitimately do nothing: a ride
 * that has not started yet cannot be paused, and one already paused cannot be
 * paused again. The `stateChanged` event that follows is the answer, and it is
 * broadcast for rider-initiated pauses too, so a companion can follow the ride
 * without polling.
 */
@Serializable
data class SetStateFrame(
    val state: String,
    val t: String = "setState",
) : ClientFrame

/**
 * Ask to be remembered.
 *
 * Sent once, after an ordinary code-authenticated handshake, by a companion
 * whose user has said they want this. The rider is then asked on the phone, and
 * agreeing on both devices is the whole ceremony: a shared secret comes back in
 * a [PairedFrame] and neither person ever types a code at each other again.
 *
 * Refused if a pairing already exists, which is not an error worth acting on:
 * it means the answer to "do these two know each other" is already yes.
 */
@Serializable
data class PairRequestFrame(
    /** What to call this companion in the rider's list of paired devices. */
    val name: String? = null,
    val t: String = "pairRequest",
) : ClientFrame

/**
 * Answer a [ChallengeFrame], proving the companion holds the pairing key.
 *
 * [nonce] is the companion's own, fresh for this connection, and it goes into
 * both proofs. That is what makes the exchange worthless to anyone who recorded
 * yesterday's: neither side is signing anything the other chose alone.
 *
 * See [CompanionSecurity.proof] for exactly what is under the tag. It is four
 * strings joined by colons and one HMAC, and it is written out there so a
 * companion author can reproduce it from the documentation alone.
 */
@Serializable
data class PairProofFrame(
    val nonce: String,
    val proof: String,
    val t: String = "pairProof",
) : ClientFrame

/**
 * The bike the rider is pretending to be on.
 *
 * Optional, and most companions will never send it: leaving it out gives the
 * rider the drivetrain they chose in the app's own settings, which is a
 * reasonable bike and the one they picked. Send it when the *game* has an
 * opinion - a mountain stage that hands out a 32-tooth granny ring, a fixed
 * gear track session, a bike the rider unlocked - and the ladder on the phone
 * becomes that bike for the length of the ride.
 *
 * **1x, 2x and 3x is the length of [chainrings].** There is no separate field
 * saying which, because a field like that can disagree with the list beside it
 * and then somebody has to decide which one is lying.
 *
 * Nothing here is persisted. The rider's own setting is untouched underneath
 * and comes back the moment the ride ends.
 *
 * Send it during setup, before `setState: "active"`. It is accepted later, but
 * a ladder that changes under a rider mid-effort is a surprise nobody asked
 * for; the app keeps them in the nearest gear it can find rather than throwing
 * them into a different one, and says what the new ladder is either way.
 *
 * @param chainrings teeth, largest first or smallest first, it does not matter.
 *   One to three of them, each 20 to 70 teeth.
 * @param sprockets teeth on the cassette, in any order. One (a single-speed is
 *   a real bike) to sixteen, each 9 to 60 teeth.
 * @param wheelCircumferenceMm rolling circumference with the tyre on. Absent
 *   means 2105, a 700x25, which is what the app's own road preset uses.
 * @param name what to call it on the phone. Absent gets a description built
 *   from the teeth, e.g. "2x, 50/34 with 11-32".
 */
/**
 * How the companion looks on the phone: a name, an icon and three colours.
 *
 * The rider has two screens in front of them, the game's and the phone's, and
 * this is what makes them read as one thing. The phone draws it on the ride
 * header and on the brief flash of a control being pressed, and nowhere that
 * carries meaning: zone colours, warnings and the app's own navigation stay the
 * app's. See [CompanionBrand].
 *
 * **Fields merge, like `course`.** Send the icon once and change the colours
 * later without sending the picture again. A field that is absent keeps what it
 * had; a field sent as `null` clears it.
 *
 * Optional, and controller only. A companion that never sends it gets a header
 * with its `hello` name and a monogram.
 *
 * @param name shown on the header in place of `hello.client.name`. Up to 40
 *   characters.
 * @param icon a square PNG, base64, 48 to 512 px, at most 96 000 characters.
 * @param colors see [BrandColorsFrame]. The app adjusts anything it cannot draw
 *   legibly and says what it used in an `adjusted` warning.
 */
@Serializable
data class BrandFrame(
    val name: String? = null,
    val icon: String? = null,
    val colors: BrandColorsFrame? = null,
    val t: String = "brand",
) : ClientFrame

/**
 * A companion's colours, each `#RRGGBB`.
 *
 * @param accent required when `colors` is sent: highlights, and the fill of a
 *   pressed paddle or remote button.
 * @param onAccent text and glyphs on the accent. Absent picks light or dark by contrast.
 * @param header the header band. Absent is derived from the accent.
 */
@Serializable
data class BrandColorsFrame(
    val accent: String? = null,
    val onAccent: String? = null,
    val header: String? = null,
)

@Serializable
data class DrivetrainFrame(
    val chainrings: List<Int> = emptyList(),
    val sprockets: List<Int> = emptyList(),
    val wheelCircumferenceMm: Int? = null,
    val name: String? = null,
    val t: String = "drivetrain",
) : ClientFrame

/**
 * A controllable screen is up, or has gone away.
 *
 * The app is driving a screen it cannot see, so this is how it learns there is
 * something to steer and what will answer. While [visible] is true the rider
 * gets a control pad on the phone; every press comes back as a
 * [EventFrame.CONTROL_PRESSED] event naming one of [CompanionControl.ALL].
 *
 * Send a fresh frame whenever what is selectable changes; the last one wins.
 * Send `visible: false` when the screen is dismissed, or the rider is left
 * holding a pad for a menu that is no longer there.
 *
 * @param visible whether a controllable screen is up. When false, every other
 *   field is ignored.
 * @param title what the screen is, e.g. "Choose a route". Shown above the pad.
 * @param hint one line of help, shown under the title.
 * @param enabled which controls will actually answer. **Anything not listed is
 *   drawn visibly dead**, which is the point: a rider must never press
 *   something that looks live and get nothing. An unrecognised name is refused
 *   with a warning rather than silently dropped.
 */
@Serializable
data class ControlsFrame(
    val visible: Boolean,
    val title: String? = null,
    val hint: String? = null,
    val enabled: List<String> = emptyList(),
    val t: String = "controls",
) : ClientFrame

/** Name the ride. The last value before [FinishFrame] wins. */
@Serializable
data class LabelFrame(
    val name: String,
    val world: String? = null,
    val t: String = "label",
) : ClientFrame

/**
 * End the ride. Companion mode has no route, so nothing else can know when it
 * is over.
 */
@Serializable
data class FinishFrame(
    val summary: RideSummary? = null,
    val t: String = "finish",
) : ClientFrame

/**
 * What the ride looked like, handed over once it is finished.
 *
 * The short form of [CourseFrame], kept because it was here first and because
 * a companion that only ever knows its road at the end has nothing to say
 * earlier. It is merged into the course exactly as a `course` frame would be,
 * so the two are interchangeable and a companion needs only one of them.
 *
 * Geography arrives *after* the ride rather than during it, so the app still
 * learns nothing about where the rider was while they were riding. A companion
 * that would rather not disclose even this omits it, and the ride saves with
 * its numbers but no shape.
 *
 * Both formats are the ones the app's own route listings already use, so the
 * post-ride card and the share image render this with no new drawing code:
 * [shape] is a precision-5 encoded polyline and [profile] is 64 elevation
 * samples in metres.
 */
@Serializable
data class RideSummary(
    val shape: String? = null,
    val profile: List<Int> = emptyList(),
)

/**
 * A picture of the companion's screen, in answer to a [ScreenshotRequestFrame].
 *
 * Exactly one of [image] and [error]. [image] is a PNG or a JPEG, base64, and
 * which of the two is read from the bytes: there is no format field to disagree
 * with them. [error] is the companion saying it could not take one, in words
 * the rider can be shown ("the game is loading"), and is better than silence,
 * which the phone can only report as no answer.
 *
 * @param id the request being answered. A screenshot nobody asked for is
 *   refused: the rider decides when a picture is taken, not the game.
 */
@Serializable
data class ScreenshotFrame(
    val id: Long? = null,
    val image: String? = null,
    val error: String? = null,
    val t: String = "screenshot",
) : ClientFrame

/** Liveness check. The WebSocket's own ping is usually enough. */
@Serializable
data class PingFrame(
    val id: Long? = null,
    val t: String = "ping",
) : ClientFrame

// Server to client ───────────────────────────────────────────────────────────

/**
 * Everything a companion needs to configure itself, sent once in answer to
 * [HelloFrame].
 *
 * Three fields carry more weight than they look:
 *
 * - [honours] is what this build actually acts on, as distinct from what the
 *   protocol defines. A companion must check it. A field absent from it will be
 *   ignored and warned about rather than quietly doing nothing.
 * - [gears] is the whole drivetrain with real teeth, so a companion can render
 *   a genuine gear display rather than an index out of twenty.
 * - [limits] publishes the clamps. An implementer should learn the envelope
 *   from the handshake rather than discovering it by surprise halfway down a
 *   descent.
 */
@Serializable
data class WelcomeFrame(
    val proto: String,
    val app: String,
    val sessionId: String,
    val tickMs: Long = CompanionProtocol.TICK_MS,
    val honours: List<String>,
    val capabilities: List<String>,
    val role: String,
    val rider: RiderInfo,
    val bike: BikeInfo,
    val gears: List<GearInfo>,
    val gearIndex: Int,
    val trainer: TrainerInfo,
    val limits: Limits,
    val resume: ResumeInfo? = null,
    /** The app's own identity, so a companion knows which phone it reached. */
    val deviceId: String? = null,
    /**
     * The app's half of the mutual proof, present only on a connection
     * authenticated by a pairing.
     *
     * **Check it.** Skipping it leaves a companion trusting any host that
     * answered on the expected port, which is precisely the position pairing
     * exists to get out of. A wrong or missing proof means the thing on the
     * other end does not hold the key, whatever else it claims.
     */
    val proof: String? = null,
    val t: String = "welcome",
) : ServerFrame

@Serializable
data class RiderInfo(val massKg: Double, val ftpWatts: Int)

@Serializable
data class BikeInfo(val massKg: Double, val drivetrain: String)

/** One virtual gear. [loadFactor] is the multiplier applied to the road force. */
@Serializable
data class GearInfo(
    val i: Int,
    val ratio: Double,
    val label: String,
    val loadFactor: Double,
)

@Serializable
data class TrainerInfo(
    val name: String? = null,
    val connected: Boolean = false,
    /** `sim` is the only tier this version supports. */
    val control: String = "sim",
)

/** Each entry is `[min, max]`, inclusive. */
@Serializable
data class Limits(
    val gradient: List<Double> = listOf(CompanionProtocol.MIN_GRADIENT, CompanionProtocol.MAX_GRADIENT),
    val speedMps: List<Double> = listOf(CompanionProtocol.MIN_SPEED_MPS, CompanionProtocol.MAX_SPEED_MPS),
    val crr: List<Double> = listOf(CompanionProtocol.MIN_CRR, CompanionProtocol.MAX_CRR),
)

/**
 * Non-null when a crashed ride is being recovered, telling the companion how
 * far the record already believes the rider has come so it can place them.
 */
@Serializable
data class ResumeInfo(val distanceM: Double, val ascentM: Double)

/**
 * Prove you are who you say you are.
 *
 * Sent instead of a [WelcomeFrame] when a companion presents a [HelloFrame]
 * with a `deviceId` the app has a pairing for. Answer with a [PairProofFrame];
 * a correct one is followed by the welcome, and the ride proceeds as though a
 * code had been typed.
 *
 * [deviceId] is the *app's* identity, which the companion needs in order to
 * look up which of its own stored keys to use. Sent here rather than broadcast
 * in the discovery beacon on purpose: a stable identifier on the wire once a
 * connection exists is a different thing from one shouted at the whole subnet
 * every second.
 */
@Serializable
data class ChallengeFrame(
    val deviceId: String,
    val nonce: String,
    val t: String = "challenge",
) : ServerFrame

/**
 * The rider agreed. Here is the secret the two devices will share from now on.
 *
 * Sent once, in answer to a [PairRequestFrame] the rider accepted. Store [key]
 * against [deviceId] and use it for every future connection; the app has
 * already stored its side.
 *
 * The key crosses the socket in the clear exactly once, here, and that is the
 * one window this scheme leaves open. It is closed for every connection
 * afterwards. See [CompanionSecurity] for why that trade was made rather than
 * a key agreement no game engine can implement.
 */
@Serializable
data class PairedFrame(
    /** The app's identity, to store the key against. */
    val deviceId: String,
    /** What the app calls itself, for the companion's own list. */
    val name: String,
    /** The shared secret, hex. [CompanionSecurity.KEY_BYTES] bytes. */
    val key: String,
    val t: String = "paired",
) : ServerFrame

/**
 * The rider said no, or there was nothing to agree to.
 *
 * Not an error and not fatal: the ride carries on exactly as it was, with the
 * pairing code having done its job. A companion should offer to ask again next
 * time rather than treating this as a failure.
 */
@Serializable
data class PairDeclinedFrame(
    val reason: String? = null,
    val t: String = "pairDeclined",
) : ServerFrame

/**
 * What the trainer is doing, at [CompanionProtocol.TICK_MS].
 *
 * [trainerForceN] and [trainerGradePercent] look like internals and are
 * published on purpose. They are literally what the machine was asked for, they
 * differ from [appliedGradient] because of the gear multiplier and the division
 * by an assumed trainer mass, and without them nobody implementing against this
 * can tell a protocol bug from an honest disagreement about physics.
 */
@Serializable
data class TelemetryFrame(
    val seq: Long,
    val tMono: Long,
    val state: String,
    val power: Int? = null,
    val cadence: Int? = null,
    val hr: Int? = null,
    /**
     * How fast the rider's legs and their chosen gear are turning the road
     * out, in metres per second. **Use this as the bike's speed.**
     *
     * A chain does not slip, so `v = cadence x development` is not an estimate
     * of the rider's speed, it *is* their speed, and it is known on the first
     * turn of the crank. A companion that integrates speed out of power
     * instead is solving `m . dv/dt = P/v - F_road` from a standing start,
     * which is why speed used to sit at zero for several seconds after the
     * rider began pedalling: the model was right, but it was answering a
     * question about a bicycle that was genuinely stationary rather than about
     * one whose cranks were already turning at 80 rpm.
     *
     * The companion still owns the world. Take this as the drivetrain's output
     * and apply whatever the game adds - drafting, surfaces, wind - before
     * sending the result back as `world.speedMps`.
     *
     * **Omitted when the trainer cannot report cadence**, which is the honest
     * answer rather than a zero: there is then no drivetrain speed to be had,
     * and a companion should fall back to its own model. Do not read an absent
     * field as a standstill.
     */
    val speedMps: Double? = null,
    val gearIndex: Int,
    val loadFactor: Double,
    val appliedGradient: Double,
    val trainerForceN: Double,
    val trainerGradePercent: Double,
    val elapsedS: Long,
    val t: String = "telemetry",
) : ServerFrame

/**
 * Something happened that a companion should react to at once rather than at
 * the next tick.
 *
 * A shift is the case that justifies the frame existing: telemetry already
 * carries the gear, but a shift sound played up to 250 ms after the rider
 * pressed the button reads as lag in the companion, not in the protocol.
 */
@Serializable
data class EventFrame(
    val event: String,
    val index: Int? = null,
    val changed: Boolean? = null,
    /** `zwiftClick`, `screen` or `companion`. */
    val source: String? = null,
    val input: String? = null,
    /** For [CONTROL_PRESSED]: which of [CompanionControl.ALL] the rider pressed. */
    val control: String? = null,
    /**
     * For [DRIVETRAIN_CHANGED]: the whole new ladder, in index order.
     *
     * Sent rather than left to be inferred, because it is not what the
     * companion asked for. Near-identical ratios are dropped, so a 2x with
     * fifty-odd combinations comes back as twenty distinct gears, and only the
     * app knows which twenty.
     */
    val gears: List<GearInfo>? = null,
    val state: String? = null,
    val reason: String? = null,
    val fallback: String? = null,
    val by: String? = null,
    val t: String = "event",
) : ServerFrame {
    companion object {
        const val GEAR_CHANGED = "gearChanged"

        /**
         * The rider pressed one of the controls a `controls` frame offered.
         * Carries [EventFrame.control]. Only ever sent while a controllable
         * screen is up and only for a control that was listed as enabled.
         */
        const val CONTROL_PRESSED = "controlPressed"

        /**
         * The drivetrain changed, carrying [EventFrame.gears] and, in
         * [EventFrame.index], the gear the rider is now in.
         */
        const val DRIVETRAIN_CHANGED = "drivetrainChanged"
        const val RIDER_INPUT = "riderInput"
        const val STATE_CHANGED = "stateChanged"
        const val WORLD_TIMEOUT = "worldTimeout"
        const val WORLD_RESUMED = "worldResumed"
        const val TRAINER_LOST = "trainerLost"
        const val CONTROL_REVOKED = "controlRevoked"

        const val SOURCE_CLICK = "zwiftClick"
        const val SOURCE_SCREEN = "screen"
        const val SOURCE_COMPANION = "companion"
    }
}

/**
 * Non-fatal: something was ignored, clamped or rate-limited, and the sender
 * should know.
 *
 * [CompanionWarning.FIELD_NOT_HONOURED] is the one that earns its keep while
 * the protocol is still moving. It is the difference between an implementer
 * seeing "drafting does not work" and seeing "this build ignores cda".
 */
@Serializable
data class WarningFrame(
    val code: String,
    val field: String? = null,
    val frame: String? = null,
    val sent: Double? = null,
    val applied: Double? = null,
    val message: String? = null,
    val t: String = "warning",
) : ServerFrame

/** Fatal for the current operation, and usually followed by a close. */
@Serializable
data class ErrorFrame(
    val code: String,
    val message: String,
    val serverRange: String? = null,
    val clientRange: String? = null,
    val t: String = "error",
) : ServerFrame

@Serializable
data class PongFrame(
    val id: Long? = null,
    val t: String = "pong",
) : ServerFrame

/**
 * The rider wants a picture of the game's screen, kept with the ride.
 *
 * Sent only to a controller whose `hello` listed the `screenshot` capability,
 * and only while a ride is underway, so there is always a ride to keep it with.
 * Answered with a [ScreenshotFrame] carrying the same [id].
 *
 * @param id unique on this connection. Echo it back.
 * @param maxEdgePx scale the picture so its longest edge is at most this. The
 *   phone accepts larger, up to [ScreenshotImage.MAX_EDGE_PX], but a picture
 *   bigger than the screen it is looked at on is bytes the rider pays for.
 */
@Serializable
data class ScreenshotRequestFrame(
    val id: Long,
    val maxEdgePx: Int = ScreenshotImage.REQUESTED_EDGE_PX,
    val t: String = "requestScreenshot",
) : ServerFrame

/** Ride states, as they appear in [TelemetryFrame.state]. */
object CompanionState {
    const val LISTENING = "listening"
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val FINISHED = "finished"
}
