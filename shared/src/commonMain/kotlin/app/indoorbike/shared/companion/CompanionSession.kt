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

import app.indoorbike.shared.simulation.Drivetrain
import kotlin.math.roundToInt

/**
 * Something the host should do as a result of a frame.
 *
 * The session decides *what* should happen and never does any of it, which is
 * what keeps it free of sockets, coroutines and Android. The transport turns
 * [Send] and [Close] into WebSocket writes; the ride turns [ApplyRoad],
 * [Shift], [SetLabel], [ReportProgress] and [Finish] into calls on the
 * simulation engine and the session manager.
 */
sealed interface CompanionEffect {
    data class Send(val frame: ServerFrame) : CompanionEffect
    data class Close(val code: Int, val reason: String) : CompanionEffect

    /** A new road, already clamped. */
    data class ApplyRoad(
        val gradient: Double,
        val speedMps: Double,
        val crr: Double = CompanionProtocol.DEFAULT_CRR,
    ) : CompanionEffect

    /** Signed steps; positive is a harder gear. The drivetrain still clamps. */
    data class Shift(val steps: Int) : CompanionEffect

    data class SetLabel(val name: String, val world: String?) : CompanionEffect

    /**
     * The road this ride is on, merged into whatever was already known. The
     * course, never a position on it.
     *
     * [replace] is a `course` that carried a `mode` (0.12): it is the whole
     * description of the ride under way or the next one, not an update to
     * merge, and it never belongs to a ride already finished.
     */
    data class SetCourse(val course: CompanionCourse, val replace: Boolean = false) : CompanionEffect

    /**
     * The junction card to show, or null to clear it. Only ever what the game
     * last said; a tap does not change it.
     */
    data class SetJunction(val junction: RouteJunction?) : CompanionEffect

    /** Where the game is taking the rider, or null for nowhere. */
    data class SetNavigation(val navigation: RouteNavigation?) : CompanionEffect

    /** The rider has reached [name]. Said once per destination. */
    data class Arrived(val name: String) : CompanionEffect

    /** The answer to the search last asked for. Stale answers never get this far. */
    data class SetPlaces(val requestId: String, val places: List<RoutePlace>) : CompanionEffect

    /**
     * Record, stop recording, or ride without being recorded. A request: it can
     * legitimately do nothing.
     *
     * [state] is one of [CompanionState.ACTIVE], [CompanionState.PAUSED] or
     * [CompanionState.UNTRACKED]. [label] says what the rider is doing and is
     * only ever set with the last of those.
     */
    data class SetRideState(val state: String, val label: String? = null) : CompanionEffect {
        val paused: Boolean get() = state == CompanionState.PAUSED
    }

    /**
     * Hold the trainer at a wattage, or hand it back to the road with null.
     * Already clamped, and only ever emitted while the ride is untracked.
     */
    data class SetPowerTarget(val watts: Int?) : CompanionEffect

    /**
     * A companion has asked to be remembered. The host asks the rider, then
     * answers with [CompanionSession.acceptPairing] or
     * [CompanionSession.declinePairing]. Nothing happens until it does.
     */
    data class RequestPairing(val deviceId: String, val name: String) : CompanionEffect

    /** The rider agreed. Write this down before the key goes out on the wire. */
    data class SavePairing(val companion: PairedCompanion) : CompanionEffect

    /** A paired companion proved itself. Worth a timestamp, nothing more. */
    data class PairingProved(val deviceId: String) : CompanionEffect

    /**
     * Put the rider on a different bike. The ride's drivetrain only; the
     * rider's own setting is untouched and comes back when the ride ends.
     */
    data class SetDrivetrain(val drivetrain: Drivetrain) : CompanionEffect

    /**
     * The companion's look, merged across every `brand` frame so far. An empty
     * brand means the companion cleared everything it had set.
     */
    data class SetBrand(val brand: CompanionBrand) : CompanionEffect

    /**
     * A controllable screen came up, or went away when null.
     *
     * Reaches the HUD and nothing else. It cannot touch the trainer, the
     * physics or the ride record, which is the property that makes it safe to
     * let a companion put buttons on the rider's phone at all.
     */
    data class SetControls(val controls: RemoteControls?) : CompanionEffect

    /** Cumulative totals for the ride record. */
    data class ReportProgress(val distanceM: Double?, val ascentM: Double?) : CompanionEffect

    /**
     * Where the companion says the rider is, for the ride record. Latitude and
     * longitude arrive together or not at all; altitude may come alone.
     */
    data class ReportPosition(val position: CompanionPosition) : CompanionEffect

    data class Finish(val summary: RideSummary?) : CompanionEffect

    /**
     * The screenshot asked for with [id] arrived and passed its checks. Keeping
     * it, and with which ride, is the host's: it knows which ride was underway
     * when the rider asked.
     */
    class SaveScreenshot(val id: Long, val image: ScreenshotImage) : CompanionEffect

    /** The screenshot asked for with [id] is not coming: the companion said why, or sent one that was refused. */
    data class ScreenshotFailed(val id: Long, val reason: String) : CompanionEffect
}

/** Static configuration for one listening socket. */
data class CompanionConfig(
    val appVersion: String,
    /**
     * This app's own stable identity, so a paired companion can tell which
     * phone it reached and which stored key to use. Not a secret.
     */
    val deviceId: String = "",
    /** What the app calls itself in a companion's list of paired devices. */
    val appName: String = "Indoor Bike",
    val sessionId: String,
    /** Shown on the phone and required in `hello`. Empty disables the check, for tests only. */
    val token: String,
    val supported: VersionRange = CompanionProtocol.SUPPORTED,
    val honours: Set<String> = CompanionProtocol.HONOURED_WORLD_FIELDS,
    val capabilities: List<String> = CompanionProtocol.CAPABILITIES,
)

/** The live facts a `welcome` needs, read at the moment a companion connects. */
data class CompanionSnapshot(
    val rider: RiderInfo,
    val bike: BikeInfo,
    val gears: List<GearInfo>,
    val gearIndex: Int,
    val trainer: TrainerInfo,
    val resume: ResumeInfo? = null,
)

/**
 * One companion connection, as a pure state machine.
 *
 * Frames go in with the current time, effects come out. Nothing here opens a
 * socket, touches BLE, or reads a clock, so the whole protocol - handshake,
 * version negotiation, clamping, the deadman, warn-once, rate limiting - is
 * testable with no device and no waiting. That is the same discipline the FIT
 * writer and the physics already follow, and it is why the deadman can be
 * tested at a hundred times real speed.
 *
 * @param controllerConnected whether some *other* connection already holds
 *   control. One controller, many observers.
 */
class CompanionSession(
    private val config: CompanionConfig,
    private val snapshot: () -> CompanionSnapshot,
    private val controllerConnected: () -> Boolean = { false },
    /**
     * The stored pairing key for a companion, or null if it is a stranger.
     *
     * A function rather than a store because this class does not get to touch
     * disk: it stays synchronous, clock-free and testable with no platform
     * under it, which is the property that lets the whole handshake be tested
     * at a hundred times real speed.
     */
    private val pairedKeyFor: (String) -> String? = { null },
    /**
     * What the ride is doing right now, as a [CompanionState] value.
     *
     * Read live rather than carried in the snapshot, because two rules turn on
     * it frame by frame: a power target is honoured only while the ride is
     * untracked, and distance reported while it is untracked is neither banked
     * nor allowed to raise this connection's kept maximum.
     */
    private val rideState: () -> String = { CompanionState.PAUSED },
    val road: CompanionRoad = CompanionRoad(),
) {

    private enum class Phase { AWAITING_HELLO, AWAITING_PROOF, READY, CLOSED }

    private var phase = Phase.AWAITING_HELLO

    /** The companion's stable identity, once it has claimed one. */
    var peerDeviceId: String? = null
        private set

    /** True when this connection was let in by a pairing rather than a code. */
    var authenticatedByPairing: Boolean = false
        private set

    /** Set while a pairing key is being proved. */
    private var pendingKey: String? = null
    private var challengeNonce: String? = null

    /** Set while the rider is being asked whether to remember this companion. */
    private var pairRequestPending = false

    /** The name the rider was asked to trust, which is the name a pairing is saved under. */
    private var pairingName: String? = null

    /** Null until the handshake succeeds. */
    var version: ProtocolVersion? = null
        private set

    /** The role the hello asked for and was given, before it is proved. */
    private var grantedRole: String? = null

    /**
     * The role this connection holds, or null until the handshake is complete.
     *
     * Complete means proved, for a companion that claimed a pairing. The role
     * used to be visible from the moment the hello was read, before the
     * challenge was answered, and the host keys everything off it: a hello
     * naming a paired device, sent by anyone who had seen that device's id go
     * past, took the controller slot, was streamed telemetry and resumed a held
     * ride, all without the key.
     */
    val role: String?
        get() = if (phase == Phase.READY) grantedRole else null

    /** The handshake is complete and the connection may be answered. */
    val isReady: Boolean get() = phase == Phase.READY

    var clientName: String = "Unnamed companion"
        private set

    val isController: Boolean get() = role == HelloFrame.ROLE_CONTROLLER

    /** What the companion said it can do, from its `hello`. */
    var clientCapabilities: Set<String> = emptySet()
        private set

    /** A controller that said it answers `requestScreenshot`. The only kind ever asked. */
    val answersScreenshots: Boolean
        get() = phase == Phase.READY && isController &&
            CompanionProtocol.SCREENSHOT_CAPABILITY in clientCapabilities

    /** Screenshots asked for and not yet answered, by id. */
    private val pendingScreenshots = mutableSetOf<Long>()

    /**
     * A controller that sends junctions to a phone that draws them. Both
     * halves, because the protocol is per build at each end: a game that never
     * said it acts on `turnChosen` is never sent one.
     */
    val choosesRoute: Boolean
        get() = phase == Phase.READY && isController &&
            CompanionProtocol.ROUTE_CHOICE_CAPABILITY in config.capabilities &&
            CompanionProtocol.ROUTE_CHOICE_CAPABILITY in clientCapabilities

    /** The same, for search and destinations. */
    val navigates: Boolean
        get() = phase == Phase.READY && isController &&
            CompanionProtocol.NAVIGATION_CAPABILITY in config.capabilities &&
            CompanionProtocol.NAVIGATION_CAPABILITY in clientCapabilities

    /** The card on screen, for checking a tap against. */
    var junction: RouteJunction? = null
        private set

    /** The highest `junction.seq` of this ride, or null before its first. */
    private var junctionSeq: Long? = null

    /** The destination an arrival was already announced for, so it is said once. */
    private var arrivedAt: String? = null

    /** The search last asked; an answer to any other is stale. */
    private var placesRequestId: String? = null
    private var nextPlacesRequest = 1L

    /** 0.12 fields already refused on this connection, so a bad one is said once. */
    private val warnedRouteFields = mutableSetOf<String>()

    /** Recent `turnChosen`, `placesQuery` and destination events, each limited on its own. */
    private val turnEvents = ArrayDeque<Long>()
    private val placesEvents = ArrayDeque<Long>()
    private val destinationEvents = ArrayDeque<Long>()

    /** Fields already complained about, so a warning is once per connection, not once per frame. */
    private val warnedFields = mutableSetOf<String>()

    /** Position fields already refused on this connection, so a bad one is said once. */
    private val warnedPositionFields = mutableSetOf<String>()

    /** Timestamps of recent shift requests, for the rate limiter. */
    private val shiftRequests = ArrayDeque<Long>()

    /** The same, for power targets. Kept apart so one cannot starve the other. */
    private val powerRequests = ArrayDeque<Long>()

    /** And for ride-state requests, each of which writes to the trainer. */
    private val stateRequests = ArrayDeque<Long>()

    /**
     * Handle one text frame.
     *
     * Never throws. A frame that cannot be understood produces an error and a
     * close; a frame from a future version produces nothing at all.
     */
    fun onFrame(text: String, nowMs: Long): List<CompanionEffect> {
        if (phase == Phase.CLOSED) return emptyList()

        return when (val decoded = CompanionCodec.decodeClientFrame(text)) {
            is Decoded.Malformed -> fail(
                CompanionError.MALFORMED,
                "Could not read a frame: " + decoded.reason,
                CompanionClose.MALFORMED,
            )

            // Ignored on purpose once the handshake is done. This is what lets a
            // companion built against a later version share a socket with this
            // build without either end having to know it happened. Before it,
            // a frame this build cannot read is not a later version talking:
            // every version's first frame is a hello. Ignoring those let a
            // socket that never said hello stay open for as long as it liked.
            is Decoded.Unknown -> if (phase == Phase.READY) {
                emptyList()
            } else {
                fail(
                    CompanionError.UNAUTHORIZED,
                    "The first frame on a Companion socket must be 'hello'.",
                    CompanionClose.UNAUTHORIZED,
                )
            }

            is Decoded.Ok -> onDecoded(decoded.frame, decoded.fields, decoded.unreadable, nowMs)
        }
    }

    private fun onDecoded(
        frame: ClientFrame,
        fields: Set<String>,
        unreadable: Map<String, String>,
        nowMs: Long,
    ): List<CompanionEffect> {
        if (phase == Phase.AWAITING_PROOF) {
            // Nothing else is worth reading from a connection that has claimed
            // an identity and not yet backed it up. Answering anything here
            // would be answering a stranger who named somebody they are not.
            return if (frame is PairProofFrame) {
                onPairProof(frame)
            } else {
                fail(
                    CompanionError.UNAUTHORIZED,
                    "Answer the challenge with 'pairProof' before anything else.",
                    CompanionClose.UNAUTHORIZED,
                )
            }
        }

        if (phase == Phase.AWAITING_HELLO) {
            return if (frame is HelloFrame) {
                onHello(frame)
            } else {
                // No hello means no token, which means unauthorised. Saying so
                // is better than ignoring it: a client that forgot the
                // handshake would otherwise sit there sending world frames into
                // a socket that will never answer.
                fail(
                    CompanionError.UNAUTHORIZED,
                    "The first frame on a Companion socket must be 'hello'.",
                    CompanionClose.UNAUTHORIZED,
                )
            }
        }

        return unreadableWarnings(frame, unreadable) + when (frame) {
            is HelloFrame -> emptyList() // a second hello is meaningless, not fatal
            is WorldFrame -> onWorld(frame, fields, nowMs)
            is RequestShiftFrame -> onRequestShift(frame, nowMs)
            is LabelFrame -> onLabel(frame)
            is CourseFrame -> onCourse(frame)
            is SetStateFrame -> onSetState(frame, nowMs)
            is SetPowerFrame -> onSetPower(frame, fields, nowMs)
            is ControlsFrame -> onControls(frame)
            is DrivetrainFrame -> onDrivetrain(frame)
            is BrandFrame -> onBrand(frame, fields)
            is PairRequestFrame -> onPairRequest(frame)
            // A proof outside the challenge is not an attack, just a companion
            // that lost track of where it was. Ignored rather than fatal.
            is PairProofFrame -> emptyList()
            is FinishFrame -> onFinish(frame)
            is ScreenshotFrame -> onScreenshot(frame)
            is JunctionFrame -> onJunction(frame)
            is NavigationFrame -> onNavigation(frame)
            is PlacesFrame -> onPlaces(frame)
            is PingFrame -> listOf(CompanionEffect.Send(PongFrame(id = frame.id)))
        }
    }

    /**
     * One `rejected` warning per field of a `course` or `finish` that could not
     * be read, sent before whatever the rest of the frame did.
     *
     * The frame was used without them, and without this nobody would know: the
     * ride ends, the name lands, and the map is simply missing from the saved
     * ride with nothing anywhere to say why.
     */
    private fun unreadableWarnings(frame: ClientFrame, unreadable: Map<String, String>): List<CompanionEffect> =
        if (!isController) {
            emptyList()
        } else {
            // A junction comes twice a second, so its warnings are once per
            // field per connection, like everything else said about one.
            unreadable.filterKeys { frame !is JunctionFrame || warnedRouteFields.add("junction.$it") }.map { (field, reason) ->
                CompanionEffect.Send(
                    WarningFrame(
                        code = CompanionWarning.REJECTED,
                        field = field,
                        message = "'" + field + "' could not be read, so it was left out and the " +
                            "rest of the frame was used: " + reason,
                    )
                )
            }
        }

    // Handshake ──────────────────────────────────────────────────────────────

    private fun onHello(frame: HelloFrame): List<CompanionEffect> {
        clientName = CompanionText.clean(frame.client.name) ?: clientName

        val min = ProtocolVersion.parse(frame.protoMin)
        val max = ProtocolVersion.parse(frame.protoMax)
        if (min == null || max == null) {
            return failVersion(
                "Companion '" + clientName + "' sent an unreadable protocol range ('" +
                    frame.protoMin + "' to '" + frame.protoMax + "'). Expected two " +
                    "'major.minor' versions. This build of Indoor Bike speaks " +
                    config.supported + ".",
                clientRange = frame.protoMin + "-" + frame.protoMax,
            )
        }

        val clientRange = VersionRange(min, max)
        val negotiated = when (
            val result = CompanionProtocol.negotiate(config.supported, clientRange, clientName)
        ) {
            is Negotiation.Rejected -> return failVersion(result.message, clientRange.toString())
            is Negotiation.Agreed -> result.version
        }

        // A companion the rider has already trusted skips the code entirely.
        // It has not proved anything yet: the challenge below is where that
        // happens, and the welcome waits until it has.
        val pairedKey = frame.deviceId?.takeIf { it.isNotBlank() }?.let(pairedKeyFor)
        if (pairedKey == null && config.token.isNotEmpty() && frame.token != config.token) {
            return fail(
                CompanionError.UNAUTHORIZED,
                "The pairing code did not match. Check the code shown on the phone.",
                CompanionClose.UNAUTHORIZED,
            )
        }

        // An absent role means "did not say", which defaults to controller. An
        // explicit role this build does not recognise means the opposite: a
        // value we cannot interpret must not be read as a request for control.
        val wantsControl = frame.role == HelloFrame.ROLE_CONTROLLER
        if (wantsControl && controllerConnected()) {
            return fail(
                CompanionError.CONTROLLER_BUSY,
                "Another companion is already driving this ride. Connect as an observer, " +
                    "or disconnect the other one first.",
                CompanionClose.CONTROLLER_BUSY,
            )
        }

        val granted = if (wantsControl) HelloFrame.ROLE_CONTROLLER else HelloFrame.ROLE_OBSERVER
        version = negotiated
        grantedRole = granted
        clientCapabilities = frame.capabilities.toSet()
        peerDeviceId = frame.deviceId?.takeIf { it.isNotBlank() }

        if (pairedKey != null) {
            // Prove it. The role is settled and the version agreed, so a
            // failure from here is about identity and nothing else.
            phase = Phase.AWAITING_PROOF
            pendingKey = pairedKey
            val nonce = CompanionSecurity.newNonceHex()
            challengeNonce = nonce
            return listOf(
                CompanionEffect.Send(
                    ChallengeFrame(deviceId = config.deviceId, nonce = nonce)
                )
            )
        }

        phase = Phase.READY
        return listOf(CompanionEffect.Send(welcomeFrame(negotiated, granted, proof = null)))
    }

    /**
     * Check the tag, then answer with one of our own.
     *
     * The comparison is constant-time and the companion's nonce is checked for
     * shape before any of it: a companion sending an empty or fixed nonce would
     * be signing something only this side chose, which is most of the way back
     * to a replay being useful.
     */
    private fun onPairProof(frame: PairProofFrame): List<CompanionEffect> {
        val key = pendingKey
        val nonceApp = challengeNonce
        val deviceId = peerDeviceId
        if (key == null || nonceApp == null || deviceId == null) {
            return fail(
                CompanionError.UNAUTHORIZED,
                "There is no challenge outstanding on this connection.",
                CompanionClose.UNAUTHORIZED,
            )
        }
        if (!CompanionSecurity.looksLikeNonce(frame.nonce)) {
            return fail(
                CompanionError.UNAUTHORIZED,
                "The nonce must be " + CompanionSecurity.NONCE_BYTES +
                    " bytes of lowercase hex, fresh for this connection.",
                CompanionClose.UNAUTHORIZED,
            )
        }
        // A stored key that is not a key proves nothing: see [CompanionSecurity.proof].
        val expected = if (CompanionSecurity.looksLikeKey(key)) {
            CompanionSecurity.proof(key, CompanionSecurity.ROLE_COMPANION, nonceApp, frame.nonce)
        } else {
            ""
        }
        if (!CompanionSecurity.proofMatches(expected, frame.proof)) {
            return fail(
                CompanionError.UNAUTHORIZED,
                "That proof does not match the pairing on file for this device. " +
                    "If the pairing was forgotten on one side, pair again with the code.",
                CompanionClose.UNAUTHORIZED,
            )
        }

        phase = Phase.READY
        authenticatedByPairing = true
        pendingKey = null
        val ourProof = CompanionSecurity.proof(
            key, CompanionSecurity.ROLE_APP, nonceApp, frame.nonce,
        )
        val negotiated = version ?: CompanionProtocol.VERSION
        val granted = grantedRole ?: HelloFrame.ROLE_CONTROLLER
        return listOf(
            CompanionEffect.PairingProved(deviceId),
            CompanionEffect.Send(welcomeFrame(negotiated, granted, proof = ourProof)),
        )
    }

    /** One welcome, however the connection was let in. */
    private fun welcomeFrame(
        negotiated: ProtocolVersion,
        granted: String,
        proof: String?,
    ): WelcomeFrame {
        val now = snapshot()
        return run {
            WelcomeFrame(
                    proto = negotiated.toString(),
                    app = config.appVersion,
                    sessionId = config.sessionId,
                    honours = config.honours.toList(),
                    capabilities = config.capabilities,
                    role = granted,
                    rider = now.rider,
                    bike = now.bike,
                    gears = now.gears,
                    gearIndex = now.gearIndex,
                    trainer = now.trainer,
                limits = Limits(),
                resume = now.resume,
                deviceId = config.deviceId.takeIf { it.isNotEmpty() },
                proof = proof,
            )
        }
    }

    // Pairing ────────────────────────────────────────────────────────────────

    /**
     * A companion asking to be remembered.
     *
     * Answered by the rider, not by this class, so all this does is decide
     * whether the question is worth asking. It is not worth asking twice, and
     * it is not worth asking on behalf of a companion that has no identity to
     * remember.
     */
    private fun onPairRequest(frame: PairRequestFrame): List<CompanionEffect> {
        if (!isController) return listOf(notController("pairRequest"))
        val deviceId = peerDeviceId
            ?: return listOf(
                CompanionEffect.Send(
                    PairDeclinedFrame(
                        reason = "Send a 'deviceId' in your hello first. Without a stable " +
                            "identity there is nothing to remember you by."
                    )
                )
            )
        if (pairedKeyFor(deviceId) != null) {
            return listOf(
                CompanionEffect.Send(PairDeclinedFrame(reason = "Already paired."))
            )
        }
        if (pairRequestPending) return emptyList()
        pairRequestPending = true
        val name = CompanionText.clean(frame.name) ?: clientName
        pairingName = name
        return listOf(CompanionEffect.RequestPairing(deviceId, name))
    }

    /**
     * The rider said yes.
     *
     * The key is generated here and written down before it is sent, so the
     * failure that matters cannot happen in the wrong order. If the send is
     * lost the app knows a pairing the companion does not, which resolves
     * itself: the companion reconnects, cannot answer the challenge, and is
     * told to pair again with the code.
     */
    fun acceptPairing(nowMs: Long): List<CompanionEffect> {
        if (phase != Phase.READY || !pairRequestPending) return emptyList()
        val deviceId = peerDeviceId ?: return emptyList()
        pairRequestPending = false
        val key = CompanionSecurity.newKeyHex()
        return listOf(
            CompanionEffect.SavePairing(
                PairedCompanion(
                    deviceId = deviceId,
                    // The name the rider said yes to. It used to be the hello's
                    // name, which the dialog never showed.
                    name = pairingName ?: clientName,
                    keyHex = key,
                    pairedAtEpochMs = nowMs,
                    lastSeenEpochMs = nowMs,
                )
            ),
            CompanionEffect.Send(
                PairedFrame(deviceId = config.deviceId, name = config.appName, key = key)
            ),
        )
    }

    /** The rider said no. The ride carries on; the code already did its job. */
    fun declinePairing(reason: String? = null): List<CompanionEffect> {
        if (!pairRequestPending) return emptyList()
        pairRequestPending = false
        return listOf(CompanionEffect.Send(PairDeclinedFrame(reason = reason)))
    }

    // Riding ─────────────────────────────────────────────────────────────────

    private fun onWorld(
        frame: WorldFrame,
        fields: Set<String>,
        nowMs: Long,
    ): List<CompanionEffect> {
        if (!isController) return listOf(notController("world"))

        val effects = mutableListOf<CompanionEffect>()
        effects += unhonouredWarnings(fields)

        // Ground covered while the ride is untracked is not this ride's, and
        // must not raise the kept maximum either: a total that grew during a
        // walk up a climb would otherwise land whole on the first frame after
        // the rider starts riding again.
        val untracked = rideState() == CompanionState.UNTRACKED
        when (val update = road.accept(frame, nowMs, bankProgress = !untracked)) {
            RoadUpdate.Stale -> return effects
            is RoadUpdate.Applied -> {
                update.clamps.forEach { clamp ->
                    effects += CompanionEffect.Send(
                        WarningFrame(
                            code = CompanionWarning.CLAMPED,
                            field = clamp.field,
                            sent = clamp.sent,
                            applied = clamp.applied,
                        )
                    )
                }
            }
        }

        effects += CompanionEffect.ApplyRoad(road.gradient, road.speedMps, road.crr)
        if (!untracked && (frame.distanceM != null || frame.ascentM != null)) {
            effects += CompanionEffect.ReportProgress(road.distanceM, road.ascentM)
        }
        // Where the rider is, for the record, so not while untracked either.
        if (!untracked) reportedPosition(frame, effects)?.let { effects += CompanionEffect.ReportPosition(it) }
        return effects
    }

    /**
     * The position [frame] reports, with anything that is not a position left
     * out and said so, once per field per connection rather than at frame rate.
     *
     * Refused rather than clamped: a latitude pulled back inside the range is a
     * place the rider never was, and the whole point of taking a position from
     * the companion is that nothing in it was made up.
     */
    private fun reportedPosition(frame: WorldFrame, effects: MutableList<CompanionEffect>): CompanionPosition? {
        fun refuse(field: String, message: String) {
            if (!warnedPositionFields.add(field)) return
            effects += CompanionEffect.Send(
                WarningFrame(code = CompanionWarning.REJECTED, field = field, frame = "world", message = message)
            )
        }

        val lat = frame.lat
        val lon = frame.lon
        var latitude: Double? = null
        var longitude: Double? = null
        when {
            lat == null && lon == null -> Unit
            lat == null || lon == null ->
                refuse(if (lat == null) "lon" else "lat", "lat and lon are one position and must be sent together; ignored.")
            !lat.isFinite() || lat < -90.0 || lat > 90.0 ->
                refuse("lat", "lat must be degrees between -90 and 90; this position was ignored.")
            !lon.isFinite() || lon < -180.0 || lon > 180.0 ->
                refuse("lon", "lon must be degrees between -180 and 180; this position was ignored.")
            else -> {
                latitude = lat
                longitude = lon
            }
        }

        val alt = frame.altM
        val altitude = when {
            alt == null -> null
            !alt.isFinite() || alt < CompanionProtocol.MIN_ALTITUDE_M || alt > CompanionProtocol.MAX_ALTITUDE_M -> {
                refuse(
                    "altM",
                    "altM must be metres above sea level between " + CompanionProtocol.MIN_ALTITUDE_M.toInt() +
                        " and " + CompanionProtocol.MAX_ALTITUDE_M.toInt() + "; ignored.",
                )
                null
            }
            else -> alt
        }

        if (latitude == null && altitude == null) return null
        return CompanionPosition(latitude = latitude, longitude = longitude, altitudeM = altitude)
    }

    /**
     * Complain, once, about anything sent that this build will not act on.
     *
     * This is the single most useful thing in the protocol while it is still
     * moving. Without it an implementer sees "drafting does not work" and has
     * nowhere to look; with it they see "this build ignores cda" and go read
     * `welcome.honours`.
     */
    private fun unhonouredWarnings(fields: Set<String>): List<CompanionEffect> =
        fields
            .asSequence()
            .filterNot { it in CompanionProtocol.WORLD_ENVELOPE_FIELDS }
            .filterNot { it in config.honours }
            .filterNot { it in warnedFields }
            .onEach { warnedFields += it }
            .map {
                CompanionEffect.Send(
                    WarningFrame(
                        code = CompanionWarning.FIELD_NOT_HONOURED,
                        field = it,
                        message = notHonouredMessage(it),
                    )
                )
            }
            .toList()

    private fun notHonouredMessage(field: String): String =
        if (field in CompanionProtocol.RESERVED_WORLD_FIELDS) {
            "This build ignores '" + field + "'. The companion owns speed, so drag, rolling " +
                "resistance and wind are its own arithmetic and never cross the socket. " +
                "See welcome.honours."
        } else {
            "Unknown field '" + field + "' in a world frame; ignored. See welcome.honours for " +
                "what this build acts on."
        }

    private fun onRequestShift(frame: RequestShiftFrame, nowMs: Long): List<CompanionEffect> {
        if (!isController) return listOf(notController("requestShift"))

        // A held gamepad button must not dump the rider into the top gear, and
        // must not kill the connection either. Refusing the surplus with a
        // warning is the only response that is both safe and survivable.
        while (shiftRequests.isNotEmpty() && nowMs - shiftRequests.first() >= 1_000L) {
            shiftRequests.removeFirst()
        }
        if (shiftRequests.size >= CompanionProtocol.MAX_SHIFT_REQUESTS_PER_SECOND) {
            return listOf(
                CompanionEffect.Send(
                    WarningFrame(
                        code = CompanionWarning.RATE_LIMITED,
                        frame = "requestShift",
                        message = "More than " + CompanionProtocol.MAX_SHIFT_REQUESTS_PER_SECOND +
                            " shift requests in a second; the surplus was dropped.",
                    )
                )
            )
        }
        shiftRequests.addLast(nowMs)
        return listOf(CompanionEffect.Shift(frame.steps))
    }

    private fun onLabel(frame: LabelFrame): List<CompanionEffect> {
        if (!isController) return listOf(notController("label"))
        return listOf(CompanionEffect.SetLabel(CompanionText.clean(frame.name).orEmpty(), CompanionText.clean(frame.world)))
    }

    /**
     * A course, whole or in pieces.
     *
     * The two size caps are enforced here rather than at the database, because
     * this is the last place that can still explain itself to the sender. A
     * shape too long to store is refused whole and warned about; the rest of
     * the frame is kept, so a companion that sends an enormous polyline
     * alongside a perfectly good name still gets the name.
     */
    private fun onCourse(frame: CourseFrame): List<CompanionEffect> {
        if (!isController) return listOf(notController("course"))

        val effects = mutableListOf<CompanionEffect>()
        val shape = keptShape(frame.shape, "shape", effects)
        val profile = keptProfile(frame.profile, "profile", effects)

        val course = CompanionCourse(
            name = frame.name?.take(CompanionProtocol.MAX_COURSE_NAME_CHARS)?.ifBlank { null },
            world = frame.world?.take(CompanionProtocol.MAX_COURSE_NAME_CHARS)?.ifBlank { null },
            distanceMeters = frame.distanceM,
            ascentMeters = frame.ascentM,
            shape = shape,
            profileMeters = profile,
            freeRide = frame.mode == CourseFrame.MODE_FREE_RIDE,
        )
        // A `mode` (0.12) makes this the whole description of a ride, sent at
        // its start: it replaces rather than merges, so a free ride after a
        // course does not inherit the course's length, and it is passed on
        // even when empty, because replacing with nothing is still replacing.
        // It is also where a ride's junctions start again from seq 0.
        if (frame.mode != null) {
            effects += startRoute()
            effects += CompanionEffect.SetCourse(course, replace = true)
        } else if (!course.isEmpty) {
            effects += CompanionEffect.SetCourse(course)
        }
        return effects
    }

    // Free ride (0.12) ───────────────────────────────────────────────────────

    /**
     * Forget the last ride's junctions and destination: a new ride starts its
     * `seq` again, and a card left over from the last one would be a choice
     * about a road nobody is on.
     */
    private fun startRoute(): List<CompanionEffect> {
        junctionSeq = null
        arrivedAt = null
        val effects = mutableListOf<CompanionEffect>()
        if (junction != null) {
            junction = null
            effects += CompanionEffect.SetJunction(null)
        }
        if (navigating) {
            navigating = false
            effects += CompanionEffect.SetNavigation(null)
        }
        return effects
    }

    /** Whether a destination is on screen, so clearing it is said only when it is. */
    private var navigating = false

    /**
     * The next junction, checked and drawn, or cleared once it is passed.
     *
     * Silently ignored where either end has not listed `routeChoice`: the game
     * should not have sent it, and a warning per frame at two a second would
     * bury everything else in the log.
     */
    private fun onJunction(frame: JunctionFrame): List<CompanionEffect> {
        if (!isController) return listOf(notController("junction"))
        if (!choosesRoute) return emptyList()
        val checked = when (val result = CompanionRoute.junction(frame)) {
            is CompanionRoute.Checked.Refused -> return refusedRoute("junction", result)
            is CompanionRoute.Checked.Ok -> result.value
        }
        val last = junctionSeq
        if (last != null && checked.seq < last) return emptyList()
        junctionSeq = checked.seq

        // Diagram points that could not be drawn are dropped, not refused: the
        // arrows still say everything. But the game should hear, once.
        val warnings = if (droppedPoints(frame, checked)) {
            refusedRoute(
                "junction",
                CompanionRoute.Checked.Refused(
                    "options.points",
                    "Diagram points must be [x, y] pairs in metres, at most " + CompanionRoute.MAX_ARM_POINTS +
                        " per arm and within " + CompanionRoute.MAX_ARM_METRES.toInt() +
                        " m of the junction; the arrows are drawn instead.",
                ),
                kept = "The card was shown without its diagram.",
            )
        } else {
            emptyList()
        }

        if (checked.phase == RouteJunction.Phase.PASSED) {
            // Only the card it describes is cleared. A late `passed` for the
            // junction before must not take down the one now coming up.
            if (junction?.junctionId != checked.junctionId) return emptyList()
            junction = null
            return listOf(CompanionEffect.SetJunction(null))
        }
        junction = checked
        return warnings + CompanionEffect.SetJunction(checked)
    }

    /** Points were sent for the diagram and some could not be used. */
    private fun droppedPoints(frame: JunctionFrame, checked: RouteJunction): Boolean {
        if (checked.phase == RouteJunction.Phase.PASSED) return false
        val approachLost = !frame.approachPoints.isNullOrEmpty() && checked.approach.isEmpty()
        val armLost = frame.options.zip(checked.options).any { (sent, kept) ->
            !sent.points.isNullOrEmpty() && kept.points.isEmpty()
        }
        return approachLost || armLost
    }

    private fun onNavigation(frame: NavigationFrame): List<CompanionEffect> {
        if (!isController) return listOf(notController("navigation"))
        if (!navigates) return emptyList()
        val shown = when (val result = CompanionRoute.navigation(frame)) {
            is CompanionRoute.Checked.Refused -> return refusedRoute("navigation", result)
            is CompanionRoute.Checked.Ok -> result.value
        }
        val effects = mutableListOf<CompanionEffect>()
        // Shown once per destination, however many frames carry it before the
        // game drops the destination.
        if (frame.arrived && shown != null && arrivedAt != shown.placeId) {
            arrivedAt = shown.placeId
            effects += CompanionEffect.Arrived(shown.name)
        }
        if (shown == null) arrivedAt = null
        navigating = shown != null
        effects += CompanionEffect.SetNavigation(shown)
        return effects
    }

    private fun onPlaces(frame: PlacesFrame): List<CompanionEffect> {
        if (!isController) return listOf(notController("places"))
        if (!navigates) return emptyList()
        val id = frame.requestId
        if (id == null || id != placesRequestId) return emptyList()
        return listOf(CompanionEffect.SetPlaces(id, CompanionRoute.places(frame)))
    }

    /** A free-ride frame or field refused, said once per field per connection. */
    private fun refusedRoute(
        frameType: String,
        refused: CompanionRoute.Checked.Refused,
        kept: String = "The frame was ignored and the last good one kept.",
    ): List<CompanionEffect> {
        if (!warnedRouteFields.add(frameType + "." + refused.field)) return emptyList()
        return listOf(
            CompanionEffect.Send(
                WarningFrame(
                    code = CompanionWarning.REJECTED,
                    field = refused.field,
                    frame = frameType,
                    message = refused.reason + " " + kept,
                )
            )
        )
    }

    /**
     * The rider tapped an option on the card.
     *
     * Sent only for the card on screen while it is open: a tap that lands
     * after the commit, or on a card that has just been replaced, would ask
     * the game for something it can no longer do. The card does not change
     * here; the game's answer is what selects the option.
     */
    fun chooseTurn(junctionId: String, optionId: String, nowMs: Long): List<CompanionEffect> {
        if (!choosesRoute) return emptyList()
        val shown = junction ?: return emptyList()
        if (!shown.isOpen || shown.junctionId != junctionId) return emptyList()
        if (shown.options.none { it.optionId == optionId }) return emptyList()
        if (!allow(turnEvents, nowMs)) return emptyList()
        return listOf(
            CompanionEffect.Send(
                EventFrame(event = EventFrame.TURN_CHOSEN, junctionId = junctionId, optionId = optionId)
            )
        )
    }

    /** A game that sorts its places into [PlaceCategory] and answers a `placesQuery.category`. */
    val sortsPlaces: Boolean
        get() = navigates && CompanionProtocol.PLACE_CATEGORIES_CAPABILITY in clientCapabilities

    /**
     * Ask the game for places to go, nearest first, in [category] or of every
     * kind. Nothing is typed: the query is always empty, which a game reads as
     * "places worth going to near the rider". The category goes only to a game
     * that sorts places; any other is asked for everything. Numbered, so only
     * the last answer is shown, and dropped past the rate.
     */
    fun browsePlaces(
        nowMs: Long,
        category: PlaceCategory? = null,
        limit: Int = CompanionRoute.MAX_PLACES_LIMIT,
    ): List<CompanionEffect> {
        if (!navigates) return emptyList()
        if (!allow(placesEvents, nowMs)) return emptyList()
        val id = "q" + nextPlacesRequest++
        placesRequestId = id
        return listOf(
            CompanionEffect.Send(
                EventFrame(
                    event = EventFrame.PLACES_QUERY,
                    requestId = id,
                    query = "",
                    limit = limit.coerceIn(1, CompanionRoute.MAX_PLACES_LIMIT),
                    category = category?.wire?.takeIf { sortsPlaces },
                )
            )
        )
    }

    /** Take the rider to [placeId]. Answered with a `navigation` frame. */
    fun chooseDestination(placeId: String, nowMs: Long): List<CompanionEffect> {
        if (!navigates || placeId.isBlank()) return emptyList()
        if (!allow(destinationEvents, nowMs)) return emptyList()
        return listOf(CompanionEffect.Send(EventFrame(event = EventFrame.DESTINATION_CHOSEN, placeId = placeId)))
    }

    /** Stop being taken anywhere. */
    fun clearDestination(nowMs: Long): List<CompanionEffect> {
        if (!navigates) return emptyList()
        if (!allow(destinationEvents, nowMs)) return emptyList()
        return listOf(CompanionEffect.Send(EventFrame(event = EventFrame.DESTINATION_CLEARED)))
    }

    /** True, and counted, when another event fits in this second's allowance. */
    private fun allow(recent: ArrayDeque<Long>, nowMs: Long): Boolean {
        while (recent.isNotEmpty() && nowMs - recent.first() >= 1_000L) recent.removeFirst()
        if (recent.size >= CompanionRoute.MAX_ROUTE_EVENTS_PER_SECOND) return false
        recent.addLast(nowMs)
        return true
    }

    /**
     * The bike the game says the rider is on.
     *
     * Refused whole rather than repaired. A drivetrain is a description of a
     * physical object, and a companion that sent a zero-tooth sprocket has a
     * bug rather than a preference: quietly rounding it to nine would hide the
     * bug and hand the rider a bike nobody designed. The rejection names the
     * offending number, and the drivetrain they already had stays.
     */
    private fun onDrivetrain(frame: DrivetrainFrame): List<CompanionEffect> {
        if (!isController) return listOf(notController("drivetrain"))

        val wheel = frame.wheelCircumferenceMm ?: Drivetrain.ROAD.wheelCircumferenceMm
        val reason = Drivetrain.rejectionReason(frame.chainrings, frame.sprockets, wheel)
        if (reason != null) {
            return listOf(
                CompanionEffect.Send(
                    WarningFrame(
                        code = CompanionWarning.REJECTED,
                        field = "drivetrain",
                        message = reason + " The drivetrain was left as it was.",
                    )
                )
            )
        }
        return listOf(
            CompanionEffect.SetDrivetrain(
                Drivetrain.custom(
                    name = frame.name?.take(CompanionProtocol.MAX_CONTROL_LABEL_CHARS),
                    chainrings = frame.chainrings,
                    sprockets = frame.sprockets,
                    wheelCircumferenceMm = wheel,
                )
            )
        )
    }

    /** The look this connection has asked for so far. Fields merge frame by frame. */
    private var brand = CompanionBrand()

    /**
     * How the companion wants to look on the phone.
     *
     * Merged rather than replaced, so a game can send its icon once and change
     * colours without sending the picture again, and `null` clears a field.
     * Whatever cannot be used is refused by name and the rest still lands: a bad
     * icon costs the icon, not the colours.
     */
    private fun onBrand(frame: BrandFrame, fields: Set<String>): List<CompanionEffect> {
        if (!isController) return listOf(notController("brand"))
        val effects = mutableListOf<CompanionEffect>()
        var next = brand

        if ("name" in fields) {
            next = next.copy(
                name = frame.name?.trim()?.take(CompanionProtocol.MAX_BRAND_NAME_CHARS)?.ifBlank { null },
            )
        }

        if ("icon" in fields) {
            val icon = frame.icon
            next = if (icon == null) {
                next.copy(iconPng = null)
            } else {
                when (val checked = BrandIcon.check(icon)) {
                    is BrandIcon.Checked.Ok -> next.copy(iconPng = checked.png)
                    is BrandIcon.Checked.Rejected -> {
                        effects += rejectedBrandField("icon", checked.reason)
                        next
                    }
                }
            }
        }

        if ("colors" in fields) {
            val colors = frame.colors
            next = if (colors == null) {
                next.copy(palette = null)
            } else {
                val (palette, adjustments) = BrandColors.resolve(colors.accent, colors.onAccent, colors.header)
                if (palette == null) {
                    effects += rejectedBrandField(
                        "colors.accent",
                        "'colors' needs an accent as #RRGGBB; got " +
                            (colors.accent?.let { "'$it'" } ?: "none") + ". The colours were left as they were.",
                    )
                    next
                } else {
                    adjustments.forEach { a ->
                        effects += CompanionEffect.Send(
                            WarningFrame(
                                code = CompanionWarning.ADJUSTED,
                                field = a.field,
                                message = a.requested + " was " + a.why + ". The phone is using " +
                                    a.used + ".",
                            )
                        )
                    }
                    next.copy(palette = palette)
                }
            }
        }

        brand = next
        effects += CompanionEffect.SetBrand(next)
        return effects
    }

    private fun rejectedBrandField(field: String, message: String) = CompanionEffect.Send(
        WarningFrame(code = CompanionWarning.REJECTED, field = field, message = message)
    )

    /**
     * A controllable screen, and what will answer on it.
     *
     * An unrecognised control name is warned about rather than dropped in
     * silence. The failure that prevents is an implementer sending "entre" and
     * spending an afternoon wondering why their Enter button never appeared on
     * the phone.
     */
    private fun onControls(frame: ControlsFrame): List<CompanionEffect> {
        if (!isController) return listOf(notController("controls"))
        if (!frame.visible) return listOf(CompanionEffect.SetControls(null))

        val effects = mutableListOf<CompanionEffect>()
        val unknown = frame.enabled.filterNot(CompanionControl::isKnown).distinct()
        if (unknown.isNotEmpty()) {
            effects += CompanionEffect.Send(
                WarningFrame(
                    code = CompanionWarning.REJECTED,
                    field = "enabled",
                    message = "Unknown control" + (if (unknown.size > 1) "s " else " ") +
                        unknown.joinToString(", ") { "'" + it + "'" } +
                        "; ignored. This build knows " +
                        CompanionControl.ALL.joinToString(", ") + ".",
                )
            )
        }

        val label = { text: String? ->
            text?.take(CompanionProtocol.MAX_CONTROL_LABEL_CHARS)?.ifBlank { null }
        }
        effects += CompanionEffect.SetControls(
            RemoteControls(
                title = label(frame.title),
                hint = label(frame.hint),
                enabled = frame.enabled.filter(CompanionControl::isKnown).toSet(),
            )
        )
        return effects
    }

    /**
     * Pause or resume the recording.
     *
     * An unrecognised state is refused rather than guessed at. Guessing here
     * has an asymmetric cost: reading a misspelt word as "paused" silently
     * stops recording a ride the rider is still doing, and they find out
     * afterwards.
     */
    private fun onSetState(frame: SetStateFrame, nowMs: Long): List<CompanionEffect> {
        if (!isController) return listOf(notController("setState"))
        // Every one of these writes to the trainer, outside the pacer the road
        // goes through: a pause zeroes it, a resume restates the road. A
        // companion toggling in a loop flooded the control point, which the
        // protocol promises a chatty companion cannot do.
        while (stateRequests.isNotEmpty() && nowMs - stateRequests.first() >= 1_000L) {
            stateRequests.removeFirst()
        }
        if (stateRequests.size >= MAX_STATE_REQUESTS_PER_SECOND) {
            return listOf(
                CompanionEffect.Send(
                    WarningFrame(
                        code = CompanionWarning.RATE_LIMITED,
                        frame = "setState",
                        message = "More than " + MAX_STATE_REQUESTS_PER_SECOND +
                            " setState requests in a second; the surplus was dropped.",
                    )
                )
            )
        }
        stateRequests.addLast(nowMs)
        return when (frame.state) {
            CompanionState.PAUSED, CompanionState.ACTIVE ->
                listOf(CompanionEffect.SetRideState(frame.state))

            CompanionState.UNTRACKED -> listOf(
                CompanionEffect.SetRideState(
                    state = CompanionState.UNTRACKED,
                    // A label is read at arm's length over a handlebar, so it
                    // gets the same ceiling a control pad's does.
                    label = frame.label
                        ?.trim()
                        ?.take(CompanionProtocol.MAX_CONTROL_LABEL_CHARS)
                        ?.takeIf { it.isNotEmpty() },
                )
            )

            else -> listOf(
                CompanionEffect.Send(
                    WarningFrame(
                        code = CompanionWarning.REJECTED,
                        field = "state",
                        message = "Unknown ride state '" + frame.state + "'; ignored. " +
                            "Expected '" + CompanionState.ACTIVE + "', '" +
                            CompanionState.PAUSED + "' or '" + CompanionState.UNTRACKED + "'.",
                    )
                )
            )
        }
    }

    /**
     * Hold the rider at a wattage, or let the road back.
     *
     * Refused outside untracked riding, and that refusal is the whole point of
     * the frame's shape: a power target that could reach a recorded ride would
     * make every companion ride's numbers negotiable. The rest is the same
     * treatment `world` gets - clamped with a warning rather than rejected,
     * rate-limited rather than fatal.
     */
    private fun onSetPower(
        frame: SetPowerFrame,
        fields: Set<String>,
        nowMs: Long,
    ): List<CompanionEffect> {
        if (!isController) return listOf(notController("setPower"))

        // The codec reads an absent field as "no value", which for every other
        // frame is what a sender means. Not here: `null` is a deliberate
        // release of the trainer, and a frame that says neither is a companion
        // bug rather than an intention, so it is malformed as the protocol
        // says rather than silently letting the road back.
        if ("watts" !in fields) {
            return fail(
                CompanionError.MALFORMED,
                "A 'setPower' must say how many watts; send null to hand the trainer back to the road.",
                CompanionClose.MALFORMED,
            )
        }

        if (rideState() != CompanionState.UNTRACKED) {
            return listOf(
                CompanionEffect.Send(
                    WarningFrame(
                        code = CompanionWarning.REJECTED,
                        field = "state",
                        message = "A power target is honoured only while the ride is '" +
                            CompanionState.UNTRACKED + "'; ignored. Ask for that state first.",
                    )
                )
            )
        }

        // Shares the shift limiter's shape and its reasoning: a companion
        // ramping a target in a tight loop is a bug in the game, and dropping
        // the surplus with a warning is the only answer that is both safe and
        // survivable.
        while (powerRequests.isNotEmpty() && nowMs - powerRequests.first() >= 1_000L) {
            powerRequests.removeFirst()
        }
        if (powerRequests.size >= CompanionProtocol.MAX_SHIFT_REQUESTS_PER_SECOND) {
            return listOf(
                CompanionEffect.Send(
                    WarningFrame(
                        code = CompanionWarning.RATE_LIMITED,
                        frame = "setPower",
                        message = "More than " + CompanionProtocol.MAX_SHIFT_REQUESTS_PER_SECOND +
                            " power targets in a second; the surplus was dropped.",
                    )
                )
            )
        }
        powerRequests.addLast(nowMs)

        val sent = frame.watts
            ?: return listOf(CompanionEffect.SetPowerTarget(null))

        val effects = mutableListOf<CompanionEffect>()
        // A wattage is a whole number of watts; a trainer cannot hold a
        // fraction of one and no rider could tell.
        val rounded = sent.roundToInt()
        val applied = rounded.coerceIn(
            CompanionProtocol.MIN_POWER_WATTS,
            CompanionProtocol.MAX_POWER_WATTS,
        )
        if (applied != rounded) {
            effects += CompanionEffect.Send(
                WarningFrame(
                    code = CompanionWarning.CLAMPED,
                    field = "watts",
                    sent = sent,
                    applied = applied.toDouble(),
                )
            )
        }
        effects += CompanionEffect.SetPowerTarget(applied)
        return effects
    }

    /**
     * The ride is over.
     *
     * A summary is the short form of a `course`, so it merges into the same
     * place, and the ordering matters: the course has to reach the ride record
     * before the ride is told to end, or the shape arrives after the row has
     * already been written.
     */
    private fun onFinish(frame: FinishFrame): List<CompanionEffect> {
        if (!isController) return listOf(notController("finish"))
        val effects = mutableListOf<CompanionEffect>()
        val summary = frame.summary
        val course = summary?.let {
            CompanionCourse(
                shape = keptShape(it.shape, "summary.shape", effects),
                profileMeters = keptProfile(it.profile, "summary.profile", effects),
            )
        }
        if (course != null && !course.isEmpty) effects += CompanionEffect.SetCourse(course)
        // The ride's junctions end with it, and the next ride's start at seq 0.
        effects += startRoute()
        effects += CompanionEffect.Finish(summary)
        return effects
    }

    /**
     * [shape], or empty with a warning when it is too long to keep.
     *
     * Dropped whole rather than truncated, because half a polyline decodes into
     * a road that leaves the map.
     */
    private fun keptShape(
        shape: String?,
        field: String,
        effects: MutableList<CompanionEffect>,
    ): String {
        val text = shape.orEmpty()
        if (text.length <= CompanionProtocol.MAX_COURSE_SHAPE_CHARS) return text
        effects += CompanionEffect.Send(
            WarningFrame(
                code = CompanionWarning.REJECTED,
                field = field,
                message = "The course shape is " + text.length +
                    " characters; the limit is " + CompanionProtocol.MAX_COURSE_SHAPE_CHARS +
                    ". It was dropped rather than truncated, because half a polyline " +
                    "decodes into a road that leaves the map. Simplify the track first.",
            )
        )
        return ""
    }

    /** [profile], or empty with a warning when it has too many samples to keep. */
    private fun keptProfile(
        profile: List<Int>,
        field: String,
        effects: MutableList<CompanionEffect>,
    ): List<Int> {
        if (profile.size <= CompanionProtocol.MAX_COURSE_PROFILE_SAMPLES) return profile
        effects += CompanionEffect.Send(
            WarningFrame(
                code = CompanionWarning.REJECTED,
                field = field,
                message = "The course profile has " + profile.size +
                    " samples; the limit is " +
                    CompanionProtocol.MAX_COURSE_PROFILE_SAMPLES +
                    ". Resample it: 64 evenly spaced points is what the app's own " +
                    "route listings carry and is plenty to draw.",
            )
        )
        return emptyList()
    }

    /**
     * A picture the rider asked for, or the companion saying it cannot take one.
     *
     * Refused, with a warning, when nobody asked: an unknown or already
     * answered id. The image is checked from its header before anything
     * decodes it, and a refused one is reported both ways, to the companion as
     * a warning it can fix and to the host as a failure the rider can be told.
     */
    private fun onScreenshot(frame: ScreenshotFrame): List<CompanionEffect> {
        if (!isController) return listOf(notController("screenshot"))
        val id = frame.id
        if (id == null || !pendingScreenshots.remove(id)) {
            return listOf(
                CompanionEffect.Send(
                    WarningFrame(
                        code = CompanionWarning.REJECTED,
                        field = "id",
                        message = "No screenshot was asked for with id " + id + ", or it was already " +
                            "answered. Send one only in answer to requestScreenshot, echoing its id.",
                    )
                )
            )
        }
        val error = frame.error?.trim()?.take(ScreenshotImage.MAX_ERROR_CHARS)
        if (!error.isNullOrEmpty()) return listOf(CompanionEffect.ScreenshotFailed(id, error))

        val image = frame.image
        val refusal = if (image.isNullOrBlank()) {
            "The screenshot frame carried neither an image nor an error."
        } else {
            when (val checked = ScreenshotImage.check(image)) {
                is ScreenshotImage.Companion.Checked.Ok ->
                    return listOf(CompanionEffect.SaveScreenshot(id, checked.image))
                is ScreenshotImage.Companion.Checked.Rejected -> checked.reason
            }
        }
        return listOf(
            CompanionEffect.Send(
                WarningFrame(code = CompanionWarning.REJECTED, field = "image", message = refusal)
            ),
            CompanionEffect.ScreenshotFailed(id, refusal),
        )
    }

    // Host-initiated ─────────────────────────────────────────────────────────

    /**
     * Ask the companion for a picture of its screen.
     *
     * Nothing at all for a companion that did not list `screenshot`: asking one
     * that never said it answers would leave the rider waiting on a reply that
     * was never coming. [id] must be unique on this connection.
     */
    fun requestScreenshot(id: Long): List<CompanionEffect> {
        if (!answersScreenshots) return emptyList()
        pendingScreenshots += id
        return listOf(CompanionEffect.Send(ScreenshotRequestFrame(id = id)))
    }

    /**
     * Refuse a connection that has not finished its handshake: it sent something
     * no handshake sends, or took too long to send anything. A no-op once the
     * handshake is done or the connection closed.
     */
    fun refuseUnauthenticated(message: String): List<CompanionEffect> =
        if (phase == Phase.READY || phase == Phase.CLOSED) {
            emptyList()
        } else {
            fail(CompanionError.UNAUTHORIZED, message, CompanionClose.UNAUTHORIZED)
        }

    /**
     * This connection won the handshake but lost the controller slot to a
     * connection that got there first. Only possible when two arrive within
     * the same instant; the second is told what it would have been told had
     * it been a moment later.
     */
    fun refuseControl(): List<CompanionEffect> = fail(
        CompanionError.CONTROLLER_BUSY,
        "Another companion is already driving this ride. Connect as an observer, " +
            "or disconnect the other one first.",
        CompanionClose.CONTROLLER_BUSY,
    )

    /** The rider took back control. The recording continues without a companion. */
    fun revoke(): List<CompanionEffect> {
        if (phase == Phase.CLOSED) return emptyList()
        phase = Phase.CLOSED
        road.release()
        return listOf(
            CompanionEffect.Send(EventFrame(event = EventFrame.CONTROL_REVOKED, by = "rider")),
            CompanionEffect.Close(CompanionClose.CONTROL_REVOKED, "control revoked by the rider"),
        )
    }

    /** The ride is over, whoever ended it. */
    fun rideEnded(): List<CompanionEffect> {
        if (phase == Phase.CLOSED) return emptyList()
        phase = Phase.CLOSED
        return listOf(
            CompanionEffect.Send(
                EventFrame(event = EventFrame.STATE_CHANGED, state = CompanionState.FINISHED)
            ),
            CompanionEffect.Close(CompanionClose.RIDE_ENDED, "ride ended"),
        )
    }

    // Failure paths ──────────────────────────────────────────────────────────

    /**
     * A version failure sends the frame *and* closes with a reason.
     *
     * Both, always. A client that fails negotiation may close the socket before
     * it ever processes the error frame, and then the close reason is the only
     * thing it sees. Putting the explanation in only one of the two is how a
     * developer ends up staring at a bare disconnect.
     */
    private fun failVersion(message: String, clientRange: String): List<CompanionEffect> {
        phase = Phase.CLOSED
        return listOf(
            CompanionEffect.Send(
                ErrorFrame(
                    code = CompanionError.PROTO_UNSUPPORTED,
                    message = message,
                    serverRange = config.supported.toString(),
                    clientRange = clientRange,
                )
            ),
            CompanionEffect.Close(
                CompanionClose.PROTOCOL_MISMATCH,
                CompanionProtocol.closeReason(
                    "protocol mismatch: server " + config.supported + ", client " + clientRange
                ),
            ),
        )
    }

    /**
     * The full explanation goes in the frame; a summary goes in the close.
     *
     * They are not the same string because they cannot be: a close frame's
     * reason is capped at [CompanionProtocol.MAX_CLOSE_REASON_BYTES], and a
     * message that overruns it produces an invalid frame, so the peer sees no
     * close code at all. A parser error naming the offending token is easily
     * long enough to trip that.
     */
    private fun fail(code: String, message: String, closeCode: Int): List<CompanionEffect> {
        phase = Phase.CLOSED
        return listOf(
            CompanionEffect.Send(ErrorFrame(code = code, message = message)),
            CompanionEffect.Close(closeCode, CompanionProtocol.closeReason(message)),
        )
    }

    /**
     * An observer tried to steer.
     *
     * Answered but not closed: the close-code table has no code for it, the
     * client is merely confused rather than dangerous, and dropping a
     * dashboard because it sent one frame too many would be worse than saying
     * no.
     */
    private companion object {
        /** Ride-state requests a controller may make in any one second. */
        const val MAX_STATE_REQUESTS_PER_SECOND = 2
    }

    private fun notController(frameType: String): CompanionEffect =
        CompanionEffect.Send(
            ErrorFrame(
                code = CompanionError.NOT_CONTROLLER,
                message = "This connection is an observer and may not send '" + frameType +
                    "'. Reconnect with role 'controller' to drive the ride.",
            )
        )
}
