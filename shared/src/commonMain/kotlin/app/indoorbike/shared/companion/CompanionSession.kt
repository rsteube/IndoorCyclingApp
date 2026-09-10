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
    data class ApplyRoad(val gradient: Double, val speedMps: Double) : CompanionEffect

    /** Signed steps; positive is a harder gear. The drivetrain still clamps. */
    data class Shift(val steps: Int) : CompanionEffect

    data class SetLabel(val name: String, val world: String?) : CompanionEffect

    /**
     * The road this ride is on, merged into whatever was already known. The
     * course, never a position on it.
     */
    data class SetCourse(val course: CompanionCourse) : CompanionEffect

    /** Pause or resume the recording. A request: it can legitimately do nothing. */
    data class SetRideState(val paused: Boolean) : CompanionEffect

    /**
     * Put the rider on a different bike. The ride's drivetrain only; the
     * rider's own setting is untouched and comes back when the ride ends.
     */
    data class SetDrivetrain(val drivetrain: Drivetrain) : CompanionEffect

    /**
     * A controllable screen came up, or went away when null.
     *
     * Reaches the HUD and nothing else. It cannot touch the trainer, the
     * physics or the ride record, which is the property that makes it safe to
     * let a companion put buttons on the rider's phone at all.
     */
    data class SetControls(val controls: RemoteControls?) : CompanionEffect

    /** Cumulative totals for the ride record. Never a position. */
    data class ReportProgress(val distanceM: Double?, val ascentM: Double?) : CompanionEffect

    data class Finish(val summary: RideSummary?) : CompanionEffect
}

/** Static configuration for one listening socket. */
data class CompanionConfig(
    val appVersion: String,
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
    val road: CompanionRoad = CompanionRoad(),
) {

    private enum class Phase { AWAITING_HELLO, READY, CLOSED }

    private var phase = Phase.AWAITING_HELLO

    /** Null until the handshake succeeds. */
    var version: ProtocolVersion? = null
        private set

    /** Null until the handshake succeeds. */
    var role: String? = null
        private set

    var clientName: String = "Unnamed companion"
        private set

    val isController: Boolean get() = role == HelloFrame.ROLE_CONTROLLER

    /** Fields already complained about, so a warning is once per connection, not once per frame. */
    private val warnedFields = mutableSetOf<String>()

    /** Timestamps of recent shift requests, for the rate limiter. */
    private val shiftRequests = ArrayDeque<Long>()

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

            // Ignored on purpose. This is what lets a companion built against a
            // later version share a socket with this build without either end
            // having to know it happened.
            is Decoded.Unknown -> emptyList()

            is Decoded.Ok -> onDecoded(decoded.frame, decoded.fields, nowMs)
        }
    }

    private fun onDecoded(
        frame: ClientFrame,
        fields: Set<String>,
        nowMs: Long,
    ): List<CompanionEffect> {
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

        return when (frame) {
            is HelloFrame -> emptyList() // a second hello is meaningless, not fatal
            is WorldFrame -> onWorld(frame, fields, nowMs)
            is RequestShiftFrame -> onRequestShift(frame, nowMs)
            is LabelFrame -> onLabel(frame)
            is CourseFrame -> onCourse(frame)
            is SetStateFrame -> onSetState(frame)
            is ControlsFrame -> onControls(frame)
            is DrivetrainFrame -> onDrivetrain(frame)
            is FinishFrame -> onFinish(frame)
            is PingFrame -> listOf(CompanionEffect.Send(PongFrame(id = frame.id)))
        }
    }

    // Handshake ──────────────────────────────────────────────────────────────

    private fun onHello(frame: HelloFrame): List<CompanionEffect> {
        clientName = frame.client.name

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

        if (config.token.isNotEmpty() && frame.token != config.token) {
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
        role = granted
        phase = Phase.READY

        val now = snapshot()
        return listOf(
            CompanionEffect.Send(
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
                )
            )
        )
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

        when (val update = road.accept(frame, nowMs)) {
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

        effects += CompanionEffect.ApplyRoad(road.gradient, road.speedMps)
        if (frame.distanceM != null || frame.ascentM != null) {
            effects += CompanionEffect.ReportProgress(road.distanceM, road.ascentM)
        }
        return effects
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
        return listOf(CompanionEffect.SetLabel(frame.name, frame.world))
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

        var shape = frame.shape.orEmpty()
        if (shape.length > CompanionProtocol.MAX_COURSE_SHAPE_CHARS) {
            shape = ""
            effects += CompanionEffect.Send(
                WarningFrame(
                    code = CompanionWarning.REJECTED,
                    field = "shape",
                    message = "The course shape is " + frame.shape.orEmpty().length +
                        " characters; the limit is " + CompanionProtocol.MAX_COURSE_SHAPE_CHARS +
                        ". It was dropped rather than truncated, because half a polyline " +
                        "decodes into a road that leaves the map. Simplify the track first.",
                )
            )
        }

        var profile = frame.profile
        if (profile.size > CompanionProtocol.MAX_COURSE_PROFILE_SAMPLES) {
            profile = emptyList()
            effects += CompanionEffect.Send(
                WarningFrame(
                    code = CompanionWarning.REJECTED,
                    field = "profile",
                    message = "The course profile has " + frame.profile.size +
                        " samples; the limit is " +
                        CompanionProtocol.MAX_COURSE_PROFILE_SAMPLES +
                        ". Resample it: 64 evenly spaced points is what the app's own " +
                        "route listings carry and is plenty to draw.",
                )
            )
        }

        val course = CompanionCourse(
            name = frame.name?.take(CompanionProtocol.MAX_COURSE_NAME_CHARS)?.ifBlank { null },
            world = frame.world?.take(CompanionProtocol.MAX_COURSE_NAME_CHARS)?.ifBlank { null },
            distanceMeters = frame.distanceM,
            ascentMeters = frame.ascentM,
            shape = shape,
            profileMeters = profile,
        )
        if (!course.isEmpty) effects += CompanionEffect.SetCourse(course)
        return effects
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
    private fun onSetState(frame: SetStateFrame): List<CompanionEffect> {
        if (!isController) return listOf(notController("setState"))
        return when (frame.state) {
            CompanionState.PAUSED -> listOf(CompanionEffect.SetRideState(paused = true))
            CompanionState.ACTIVE -> listOf(CompanionEffect.SetRideState(paused = false))
            else -> listOf(
                CompanionEffect.Send(
                    WarningFrame(
                        code = CompanionWarning.REJECTED,
                        field = "state",
                        message = "Unknown ride state '" + frame.state + "'; ignored. " +
                            "Expected '" + CompanionState.ACTIVE + "' or '" +
                            CompanionState.PAUSED + "'.",
                    )
                )
            )
        }
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
        val summary = frame.summary
        val course = summary?.let {
            CompanionCourse(
                // Dropped whole rather than trimmed, for the reason [onCourse]
                // gives: a truncated polyline is a corrupt road, not a short one.
                shape = it.shape
                    .orEmpty()
                    .takeIf { text -> text.length <= CompanionProtocol.MAX_COURSE_SHAPE_CHARS }
                    .orEmpty(),
                profileMeters = it.profile
                    .takeIf { p -> p.size <= CompanionProtocol.MAX_COURSE_PROFILE_SAMPLES }
                    ?: emptyList(),
            )
        }
        return buildList {
            if (course != null && !course.isEmpty) add(CompanionEffect.SetCourse(course))
            add(CompanionEffect.Finish(summary))
        }
    }

    // Host-initiated ─────────────────────────────────────────────────────────

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
    private fun notController(frameType: String): CompanionEffect =
        CompanionEffect.Send(
            ErrorFrame(
                code = CompanionError.NOT_CONTROLLER,
                message = "This connection is an observer and may not send '" + frameType +
                    "'. Reconnect with role 'controller' to drive the ride.",
            )
        )
}
