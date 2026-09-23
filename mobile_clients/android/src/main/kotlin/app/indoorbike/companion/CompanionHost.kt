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
package app.indoorbike.companion

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.indoorbike.shared.companion.BrandPalette
import app.indoorbike.shared.companion.CompanionBrand
import app.indoorbike.shared.companion.CompanionWarning
import app.indoorbike.shared.companion.WarningFrame
import app.indoorbike.shared.companion.BikeInfo
import app.indoorbike.shared.companion.CompanionAnnouncement
import app.indoorbike.shared.companion.CompanionCodec
import app.indoorbike.shared.companion.CompanionConfig
import app.indoorbike.shared.companion.CompanionCourse
import app.indoorbike.shared.companion.CompanionControl
import app.indoorbike.shared.companion.CompanionEffect
import app.indoorbike.shared.companion.CompanionEngine
import app.indoorbike.shared.companion.CompanionClose
import app.indoorbike.shared.companion.CompanionError
import app.indoorbike.shared.companion.CompanionProtocol
import app.indoorbike.shared.companion.CompanionSecurity
import app.indoorbike.shared.companion.CompanionSession
import app.indoorbike.shared.companion.CompanionSnapshot
import app.indoorbike.shared.companion.CompanionState
import app.indoorbike.shared.companion.ErrorFrame
import app.indoorbike.shared.companion.EventFrame
import app.indoorbike.shared.companion.GearInfo
import app.indoorbike.shared.companion.HelloFrame
import app.indoorbike.shared.companion.PairedCompanion
import app.indoorbike.shared.companion.PairedCompanionStore
import app.indoorbike.shared.companion.RemoteControls
import app.indoorbike.shared.companion.RiderInfo
import app.indoorbike.shared.companion.ServerFrame
import app.indoorbike.shared.companion.TelemetryFrame
import app.indoorbike.shared.companion.TrainerInfo
import app.indoorbike.ride.CompanionRideRecorder
import app.indoorbike.ride.SavedCompanionRide
import app.indoorbike.shared.session.RideEndWatch
import app.indoorbike.shared.session.RideRoad
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.shared.session.WorkoutSessionManager
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.shared.util.Log
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import app.indoorbike.ride.RideScreenshotStore
import app.indoorbike.ride.RideBrandStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything the pairing screen and the HUD need to know about the socket.
 *
 * One flat object rather than a sealed hierarchy because the screen shows most
 * of it at once: a rider looking at the pairing card wants the code, the
 * address and whether anything has connected, and modelling those as mutually
 * exclusive states would only mean unpacking them again.
 */
data class CompanionStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val token: String = "",
    val sessionId: String = "",
    val addresses: List<String> = emptyList(),
    val advertising: Boolean = false,
    /** Self-declared name of the connected controller, or null when none holds the ride. */
    val controllerName: String? = null,
    /**
     * Where the controller is connecting from. Shown to the rider, so a
     * connection they did not expect is one they can see and take control back
     * from, rather than a name any device could claim.
     */
    val controllerAddress: String? = null,
    val observers: Int = 0,
    /** True once any road has arrived. The pairing screen waits for this. */
    val receivingRoad: Boolean = false,
    /** True while the companion has gone quiet past its lease. */
    val roadStale: Boolean = false,
    /** What the companion called this ride, if it said. */
    val label: String? = null,
    /** Which world or game it is in, if it said. Shown beside [label]. */
    val world: String? = null,
    val error: String? = null,
    /**
     * Changes with every start and every stop. The pairing screen keys its
     * "open the socket" effect on it: a stop that landed while the screen was
     * up used to reset the status to exactly the values the effect had already
     * run for, so it never ran again and the rider was left looking at a card
     * with no code and no address.
     */
    val epoch: Int = 0,
) {
    val primaryAddress: String? get() = addresses.firstOrNull()

    val qrPayload: String?
        get() = primaryAddress?.let { CompanionPairing.qrPayload(it, port, token, sessionId) }

    val websocketUrl: String?
        get() = primaryAddress?.let { CompanionPairing.websocketUrl(it, port) }
}

/**
 * The app half of the Companion API: a WebSocket server, an mDNS
 * advertisement, and the wiring between the protocol's effects and the ride.
 *
 * ## What lives here and what does not
 *
 * The protocol itself is in `shared`, as pure Kotlin with no sockets and no
 * clock. [CompanionSession] decides what should happen; this decides how. That
 * split is why the handshake, the clamps, the deadman, warn-once and the shift
 * rate limiter are all tested with no device attached, and why the only thing
 * left to get wrong here is plumbing.
 *
 * ## Why the transport is Android-only for now
 *
 * `Ktor` CIO would run under KMP, and the day iOS happens this becomes a
 * transport swap rather than a rewrite. But discovery is [android.net.nsd]
 * either way, and the service whose lifetime the socket matches is an Android
 * foreground service, so an `expect`/`actual` seam here would be speculation.
 * The real reuse boundary is [CompanionSession], and that is already shared.
 *
 * ## Lifetime
 *
 * The server listens only while a companion ride is on screen. It is not a
 * background daemon and not a permanently open port on somebody's phone: the
 * ride ends, the socket closes, the advertisement is withdrawn. That is the
 * single most valuable security property in the design, and it costs nothing
 * because it falls out of tying [start] and [stop] to the screen.
 */
class CompanionHost(
    context: Context,
    private val engine: CompanionEngine,
    private val sessionManager: WorkoutSessionManager,
    private val appVersion: String,
    private val ftp: () -> Int,
    private val riderMassKg: () -> Float,
    private val bikeMassKg: () -> Float,
    private val powerWatts: () -> Int,
    private val cadenceRpm: () -> Int,
    private val heartRateBpm: () -> Int,
    private val trainerName: () -> String?,
    private val trainerConnected: () -> Boolean,
    /**
     * Whether a connected trainer will take a Set Target Power write.
     *
     * Separate from [trainerConnected] because the two fail differently: a
     * power target with no trainer is a request too early, and one to a trainer
     * with no FTMS control point is a request this machine can never honour.
     * The companion is told which. Defaults to false so a platform that has not
     * wired it refuses targets rather than swallowing them.
     */
    private val trainerAcceptsPower: () -> Boolean = { false },
    /** This phone's stable identity, so a paired companion knows which it reached. */
    private val deviceId: () -> String = { "" },
    /** Companions the rider has trusted. Null disables pairing entirely. */
    private val pairedStore: PairedCompanionStore? = null,
    /** Where screenshots are kept. Null offers the rider no screenshot button. */
    private val screenshots: RideScreenshotStore? = null,
    /**
     * Where a ride's companion is remembered. Null forgets one the moment it
     * disconnects, which is all this app did before the share card could credit
     * the game a ride was ridden with.
     */
    private val brands: RideBrandStore? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Where closing happens. Its own scope, never cancelled, so that [dispose]
     * cancelling [scope] cannot cut short the shutdown [stop] has just asked
     * for, which used to leave the socket listening after the host was gone.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val advertiser = CompanionAdvertiser(appContext)

    /**
     * Guards the lifecycle fields - [server], the jobs, [generation] - which a
     * screen's start and another screen's stop reach from different threads,
     * in either order.
     */
    private val lifecycle = Any()

    /**
     * Bumped by every start and every stop. A start that is still opening
     * checks it before publishing anything, so a stop that lands in the middle
     * wins: before, the opening carried on after the stop, wired up a server
     * nothing could reach and left the port open until the process died.
     */
    private var generation = 0

    /** The pairing code now being accepted. Read by each new connection. */
    @Volatile
    private var currentToken = ""

    /** Guards the controller slot, so two handshakes finishing at once cannot both take it. */
    private val slot = Any()

    /**
     * Each pairing write reads the stored list, changes it and writes it back,
     * and the in-memory keys are rebuilt from the result. Two at once, a forget
     * from the screen and a companion proving its pairing on the socket, could
     * each write back a copy missing the other's change and bring a forgotten
     * companion back. One at a time.
     */
    private val pairingMutex = Mutex()

    /** Failed handshakes by address, for the backoff. */
    private class Failures(var count: Int = 0, var blockedUntilMs: Long = 0L)
    private val failuresByHost = ConcurrentHashMap<String, Failures>()

    /** Failed handshakes since the pairing code was last issued. */
    private val codeFailures = AtomicInteger(0)

    /**
     * The ride's road as it stood when the companion said the ride was over,
     * held until the ride is written. See [applyCourse].
     */
    @Volatile
    private var roadAtFinish: RideRoad? = null

    /**
     * A course that arrived for the next ride while the last one was still
     * being written. Preparing the next ride clears the engine's course, so it
     * waits here and is applied once that is done.
     */
    @Volatile
    private var nextRideCourse: CompanionCourse? = null

    /**
     * The bike the companion described, kept for the rest of the session.
     * Every new ride restores the rider's own bike, and a game's drivetrain
     * used to revert at every lap with nothing to tell the game so.
     */
    @Volatile
    private var companionDrivetrain: Drivetrain? = null

    /**
     * The other half of discovery. mDNS is the standard answer; this is the one
     * a client with no DNS-SD library can consume, which in practice means most
     * game engines. Neither is load-bearing - the pairing screen always shows
     * the address and a QR.
     */
    private val beacon = CompanionBeacon(scope)

    private val _status = MutableStateFlow(CompanionStatus())
    val status: StateFlow<CompanionStatus> = _status.asStateFlow()

    /**
     * A controllable screen the companion is showing, or null for none.
     *
     * Its own flow rather than a field on [CompanionStatus] because it has
     * nothing to do with pairing and everything to do with the HUD, and
     * because it changes on its own schedule: a menu opening should not make
     * every pairing-card observer recompose.
     */
    private val _remoteControls = MutableStateFlow<RemoteControls?>(null)
    val remoteControls: StateFlow<RemoteControls?> = _remoteControls.asStateFlow()

    /**
     * A companion asking to be remembered, waiting on the rider.
     *
     * Null almost always. Non-null only between a `pairRequest` arriving and
     * the rider answering it, which is the one moment in this design that
     * requires a human.
     */
    private val _pendingPairing = MutableStateFlow<PendingPairing?>(null)
    val pendingPairing: StateFlow<PendingPairing?> = _pendingPairing.asStateFlow()

    /**
     * Keys for paired companions, in memory.
     *
     * The session state machine is synchronous by design, so it cannot wait on
     * disk mid-handshake. The store is read once when the socket opens and kept
     * here; a pairing made during a ride is written to both.
     */
    @Volatile
    private var pairedKeys: Map<String, String> = emptyMap()

    /**
     * The rider's paired companions, for the screen that lets them take a
     * trust back.
     *
     * A flow rather than a suspend call the screen makes once, because the list
     * changes underneath it: a companion can be paired while that very screen
     * is open, and a list that only loaded on entry would not show it.
     */
    private val _pairedCompanions = MutableStateFlow<List<PairedCompanion>>(emptyList())
    val pairedCompanions: StateFlow<List<PairedCompanion>> = _pairedCompanions.asStateFlow()

    /**
     * Writes a finished ride to history. Set once, after the container exists,
     * because saving needs the history repository and this is built before it.
     */
    var rideRecorder: CompanionRideRecorder? = null

    /**
     * Every ride written during this session, oldest first.
     *
     * A companion session is not one ride. The rider stays on the bike, the
     * game runs lap after lap, and each lap is a row in history the moment it
     * ends. This is what the ride screen counts to tell them it happened, and
     * what they are shown when the session is over.
     */
    private val _sessionRides = MutableStateFlow<List<SavedCompanionRide>>(emptyList())
    val sessionRides: StateFlow<List<SavedCompanionRide>> = _sessionRides.asStateFlow()

    /** Where a companion session is in its life. */
    enum class SessionPhase {
        /** A companion is connected, or none has connected yet. */
        LIVE,

        /**
         * The companion has gone and the clock is running. Not yet the end:
         * a game being restarted, a laptop sleeping and a Wi-Fi hiccup all close
         * a socket, so a disconnect starts a countdown rather than a teardown.
         */
        COMPANION_AWAY,

        /**
         * Over. Any ride that was underway has been written, the socket is
         * closed, and the rider is looking at what the session produced until
         * they dismiss it. Final: a companion reconnecting does not reopen it.
         */
        ENDED,
    }

    private val _sessionPhase = MutableStateFlow(SessionPhase.LIVE)
    val sessionPhase: StateFlow<SessionPhase> = _sessionPhase.asStateFlow()

    /**
     * The companion that drove this session, kept after it leaves. Null until
     * one has connected, which is also how the ride screen tells a real session
     * from the debug HUD nobody ever drove.
     */
    private val _sessionCompanionName = MutableStateFlow<String?>(null)
    val sessionCompanionName: StateFlow<String?> = _sessionCompanionName.asStateFlow()

    /**
     * How the companion driving this session wants to look, decoded and ready to
     * draw. Null until it sends a `brand` frame, and after it clears one.
     *
     * Kept while the companion is away, so the panel saying it has gone still
     * looks like the thing that left. Dropped when a different companion takes
     * over, because another game's colours on this one's ride would be a lie.
     */
    data class Look(
        val name: String?,
        val icon: ImageBitmap?,
        val palette: BrandPalette?,
    )

    private val _look = MutableStateFlow<Look?>(null)
    val look: StateFlow<Look?> = _look.asStateFlow()

    /** Which companion the current [look] came from, by its `hello` name. */
    @Volatile
    private var lookOwner: String? = null

    /** The last icon decoded, so a frame that only changes colours does not decode it again. */
    private var lastIconPng: ByteArray? = null
    private var lastIcon: ImageBitmap? = null

    /**
     * Where the last screenshot the rider asked for has got to, for the line
     * under the ride's name. [seq] changes with every status, so the same
     * message twice still shows twice; SAVED and FAILED clear themselves.
     */
    data class ScreenshotStatus(val seq: Int, val kind: Kind, val message: String) {
        enum class Kind { WAITING, SAVED, FAILED }
    }

    private val _screenshotStatus = MutableStateFlow<ScreenshotStatus?>(null)
    val screenshotStatus: StateFlow<ScreenshotStatus?> = _screenshotStatus.asStateFlow()

    private val _screenshotsOffered = MutableStateFlow(false)

    /** The companion driving the ride said it answers screenshots, and there is somewhere to keep them. */
    val screenshotsOffered: StateFlow<Boolean> = _screenshotsOffered.asStateFlow()

    /** A screenshot asked for: the ride it belongs to, and when on that ride the rider asked. */
    private data class PendingShot(val rideStartEpochMs: Long, val rideSeconds: Int, val requestedAtMs: Long)

    private val pendingShots = ConcurrentHashMap<Long, PendingShot>()
    private val nextShotId = AtomicLong(1)
    private val screenshotSeq = AtomicInteger(0)

    /**
     * True from [start] until [stop]: this host is hosting a companion session,
     * and so it is the thing that writes the session's rides.
     *
     * The ride screen has to know, because it saves every other ride itself and
     * must neither save one of these twice nor skip one the host is not there
     * to save. The second case is real: a companion ride continued from the
     * unfinished-ride banner runs on the ride screen with no host at all, and
     * ending it there once saved nothing.
     */
    private val _hosting = MutableStateFlow(false)
    val hosting: StateFlow<Boolean> = _hosting.asStateFlow()

    /** Set while a session is being ended, so the ride it completes is not re-prepared. */
    @Volatile
    private var sessionClosing = false

    /**
     * The companion whose disconnect paused a riding ride, by its `hello` name.
     * When it comes back the ride carries on by itself; null when there is
     * nothing to resume, including a ride the game had paused on its own.
     */
    @Volatile
    private var resumeForCompanion: String? = null

    /**
     * The last ride written, while it can still be added to.
     *
     * Held from the moment a ride is saved until the next one starts, because
     * that is exactly the window in which a late `course` still belongs to it.
     */
    @Volatile
    private var completableRide: SavedCompanionRide? = null

    /** True between the first active moment of a ride and its ending. */
    private var rideUnderway = false


    private var server: EmbeddedServer<*, *>? = null
    private var telemetryJob: Job? = null
    private var startJob: Job? = null
    private var rideWatchJob: Job? = null

    /** Every open connection, controllers and observers alike. */
    private val connections = CopyOnWriteArrayList<Conn>()

    /** Non-null while a controller holds the ride. One controller, many observers. */
    @Volatile
    private var controller: Conn? = null

    /** Set once a controller has connected and the ride has been prepared. */
    @Volatile
    private var ridePrepared = false

    private var telemetrySeq = 0L
    private var hostStartedAtMs = 0L

    /** Something to put on a connection's socket, in turn. */
    private sealed interface Outbound {
        class Send(val frame: ServerFrame) : Outbound
        class Close(val code: Int, val reason: String) : Outbound
    }

    private class Conn(
        val session: CompanionSession,
        val ws: DefaultWebSocketSession,
        /** The address it connected from. */
        val remoteHost: String,
    ) {
        /**
         * Everything bound for this socket, in order, drained by one writer.
         *
         * Writes used to be made by whichever thread had something to say,
         * under a lock, each event on a coroutine of its own. Two events could
         * reach the wire in either order, and a peer that stopped reading - a
         * laptop asleep with its socket half open - filled its window, parked a
         * sender on the lock, and every other connection's telemetry waited
         * behind it. One queue per connection keeps its frames in order, and a
         * bounded one means a stalled peer is dropped rather than waited on.
         */
        val outbox = Channel<Outbound>(OUTBOX_CAPACITY)

        /** Frames queued and not yet written. */
        val queued = AtomicInteger(0)

        /**
         * The ride state this connection was last told about, in its own
         * vocabulary.
         *
         * Per connection rather than per host because two companions on one
         * ride can be owed different words for the same state: one that
         * negotiated 0.10 hears `untracked`, an older one hears `paused`, and
         * an untracked stretch that begins and ends between two of its pauses
         * is a change for the first and no change at all for the second.
         */
        @Volatile
        var announcedState: String? = null
    }

    // Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Open the socket and start advertising.
     *
     * Idempotent: calling it while already running does nothing, so a screen
     * that recomposes or an activity that is recreated cannot end up with two
     * servers fighting over a port.
     */
    fun start() {
        synchronized(lifecycle) {
            if (server != null || startJob != null) return
            generation += 1
            val gen = generation
            hostStartedAtMs = System.currentTimeMillis()
            telemetrySeq = 0
            ridePrepared = false
            rideUnderway = false
            _hosting.value = true
            clearLook()
            _sessionPhase.value = SessionPhase.LIVE
            _sessionCompanionName.value = null
            _sessionRides.value = emptyList()
            sessionClosing = false
            resumeForCompanion = null
            roadAtFinish = null
            nextRideCourse = null
            companionDrivetrain = null
            failuresByHost.clear()
            codeFailures.set(0)

            currentToken = CompanionPairing.newToken()
            val sessionId = CompanionPairing.newSessionId()
            _status.value = CompanionStatus(
                running = false,
                token = currentToken,
                sessionId = sessionId,
                addresses = CompanionPairing.localAddresses(),
                epoch = gen,
            )
            startJob = scope.launch { open(gen, sessionId) }
        }
    }

    private fun isCurrent(gen: Int): Boolean = synchronized(lifecycle) { generation == gen }

    private suspend fun open(gen: Int, sessionId: String) {
        // A server made here and not yet handed to [server] belongs to this
        // attempt alone, and is stopped on the way out if it was never handed
        // over.
        var orphan: EmbeddedServer<*, *>? = null
        try {
            // Before the server: the rider should still be able to see and
            // prune their paired companions on a night when the socket refuses
            // to open.
            refreshPairedCompanions()
            if (!isCurrent(gen)) return
            // Port 0 asks the OS for a free one, which is the right answer
            // for something that only exists for the length of a ride: a
            // fixed port would collide with whatever else the rider runs,
            // and the number reaches the companion through mDNS and the QR
            // payload anyway.
            val started = embeddedServer(CIO, port = 0) {
                install(WebSockets) {
                    // A peer that has gone - a laptop asleep, Wi-Fi dropped -
                    // sends no FIN, and without pings its socket stayed "open"
                    // for as long as TCP took to give up, a quarter of an hour
                    // or more, holding the ride and refusing the game when it
                    // came back.
                    pingPeriodMillis = PING_PERIOD_MS
                    timeoutMillis = PING_TIMEOUT_MS
                    // Big enough for a screenshot, far short of what used to be
                    // accepted: anything, which let one frame from any device on
                    // the network exhaust the app's memory mid-ride.
                    maxFrameSize = MAX_FRAME_BYTES
                }
                routing {
                    webSocket(CompanionProtocol.PATH) {
                        serve(sessionId, this)
                    }
                }
            }
            orphan = started
            started.start(wait = false)
            val port = withTimeoutOrNull(START_TIMEOUT_MS) {
                started.engine.resolvedConnectors().firstOrNull()?.port
            } ?: error("the server did not report a port")

            synchronized(lifecycle) {
                if (generation != gen) throw CancellationException("stopped while opening")
                server = started
                orphan = null
                _status.update {
                    it.copy(running = true, port = port, addresses = CompanionPairing.localAddresses())
                }
                Log.i(TAG, "Companion socket listening on " + port)

                advertiser.start(port, sessionId, appVersion) { advertising ->
                    _status.update { it.copy(advertising = advertising) }
                }
                beacon.start {
                    CompanionAnnouncement(
                        port = port,
                        app = appVersion,
                        session = sessionId,
                        name = BEACON_NAME,
                        // Read live, so a picker on the other end can tell a
                        // phone that is free from one already being driven.
                        paired = controller != null,
                    )
                }

                engine.onGearChanged = ::broadcastGearChanged
                engine.onRoadStaleChanged = ::broadcastRoadStale
                engine.onPowerTargetChanged = ::broadcastPowerTarget
                telemetryJob = scope.launch { telemetryLoop() }
                // A finished ride has to reach the companion when it finishes,
                // not when the rider gets round to tapping through the summary.
                // Tying this to the screen meant a game sat waiting on a ride
                // that had been over for minutes.
                rideWatchJob = scope.launch { watchRide() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the companion socket", e)
            if (isCurrent(gen)) {
                _status.update {
                    it.copy(
                        running = false,
                        error = "Could not open the companion socket: " + (e.message ?: "unknown error"),
                    )
                }
            }
        } finally {
            orphan?.let { unpublished -> teardownScope.launch { runCatching { unpublished.stop(GRACE_MS, TIMEOUT_MS) } } }
            // Only this attempt's own handle: a newer start has one of its own.
            synchronized(lifecycle) { if (generation == gen) startJob = null }
        }
    }

    private suspend fun watchRide() {
        // Not `state == Completed`. The session sits on that from the moment a
        // ride is saved until the next one is prepared, which includes every
        // second of the pairing screen the rider comes back to, so the socket
        // would open and shut itself and the second ride of an evening could
        // never start. [RideEndWatch] carries the rule and the tests that pin it.
        val rideEnd = RideEndWatch()
        sessionManager.state.collect { rideState ->
            if (rideEnd.ended(rideState)) {
                onRideEnded()
                return@collect
            }
            if (rideState == WorkoutState.Completed) return@collect
            if (rideState == WorkoutState.Active) {
                // A ride that is running again is a ride the previous one can
                // no longer be added to.
                completableRide = null
                // The first move into active is where this ride starts
                // measuring from. Not a resume: a rider back from a pause is
                // continuing, and re-basing there would discard everything
                // they had already done.
                if (!rideUnderway) {
                    rideUnderway = true
                    engine.markRideStart()
                    // The ride now has a start time to file it under, which is
                    // what the brand was waiting for: it arrives at connect,
                    // before there is any ride to attach it to.
                    rememberBrand()
                }
            }
            // Pausing is not only something a companion asks for. The rider
            // has a pause button, the stop-pedalling watchdog has its own, and
            // the foreground service can force one. A game that only ever
            // heard about the pauses it requested would carry on rolling its
            // world while the recording sat still.
            announceState(rideState.asCompanionState())
        }
    }

    /**
     * Close the socket, withdraw the advertisement, forget every connection,
     * and forget the session: the rider has left Companion mode.
     */
    fun stop() {
        synchronized(lifecycle) {
            stopListening()
            clearLook()
            _hosting.value = false
            _sessionPhase.value = SessionPhase.LIVE
            _sessionCompanionName.value = null
            sessionClosing = false
            resumeForCompanion = null
            roadAtFinish = null
            nextRideCourse = null
            companionDrivetrain = null
            // The rides stay in history; the list of them belongs to the session
            // that is ending here, and the next one starts empty.
            _sessionRides.value = emptyList()
            completableRide = null
            pendingShots.clear()
            _screenshotStatus.value = null
        }
    }

    /**
     * Close the socket and drop every connection, but remember the session.
     *
     * What a session that has ended does while the rider is still reading its
     * summary: nothing more can connect or start a ride, and the list of what
     * the session produced stays on screen until they dismiss it.
     */
    private fun stopListening() = synchronized(lifecycle) {
        // Whatever start is still opening is now out of date, and stands down
        // at its next check rather than publishing a server after this.
        generation += 1
        startJob?.cancel()
        startJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        rideWatchJob?.cancel()
        rideWatchJob = null
        engine.onGearChanged = null
        engine.onRoadStaleChanged = null
        engine.onPowerTargetChanged = null
        advertiser.stop()
        beacon.stop()

        val running = server
        server = null
        val closing = connections.toList()
        connections.clear()
        synchronized(slot) {
            controller = null
            ridePrepared = false
        }
        _remoteControls.value = null
        _pendingPairing.value = null
        if (running != null || closing.isNotEmpty()) {
            // Stopped off the caller's thread: CIO's shutdown joins its
            // selector, and doing that on the main thread while a screen is
            // being disposed is a visible stall. On the teardown scope, so a
            // dispose right behind this cannot cancel it.
            teardownScope.launch {
                // Say why before the socket goes away. Without this a
                // companion cannot tell a finished ride from a crashed phone,
                // and the protocol promises it a `stateChanged` and a 4005.
                closing.forEach { conn ->
                    runCatching { dispatch(conn, synchronized(conn.session) { conn.session.rideEnded() }) }
                }
                // Long enough for each writer to put those two on the wire.
                delay(GRACE_MS)
                runCatching { running?.stop(GRACE_MS, TIMEOUT_MS) }
            }
        }
        _status.value = CompanionStatus(epoch = generation)
    }

    /**
     * The rider took back control.
     *
     * The recording carries on, on a flat road, which is why this is not the
     * same as ending the ride. The companion is told why it was dropped rather
     * than simply losing the socket.
     */
    fun revokeControl() {
        val holder = controller ?: return
        // Everything the companion was holding is let go of now, not when its
        // lease runs out: the road goes flat at the speed the rider is doing,
        // a power target goes, and an untracked stretch stops being one. The
        // revoke used to release only the session's own copy of the road, so
        // the hill stood for another three and a half seconds and a target
        // stood for good.
        engine.setPowerTarget(null, CompanionEngine.SOURCE_STATE)
        engine.setRoad(gradient = 0.0, speedMps = engine.speedMps.value)
        sessionManager.takeBackFromCompanion()
        scope.launch {
            dispatch(holder, synchronized(holder.session) { holder.session.revoke() })
        }
    }

    /**
     * Fire an event at every connected companion.
     *
     * Exists for the debug harness. Most events are produced by something real
     * happening - a shift, a timeout, the rider taking control - but a
     * companion developer has to be able to exercise their reconnect and
     * error paths without unplugging a trainer or crashing their own game, and
     * `worldTimeout` in particular is a condition the app cannot manufacture
     * on demand because only the companion's silence causes it.
     *
     * Injected events are indistinguishable on the wire from real ones, which
     * is the point: the companion should not need a test mode of its own.
     */
    fun sendEvent(frame: EventFrame) = broadcast(frame)

    /**
     * Prepare the ride without waiting for a companion. Debug harness only.
     *
     * Normally the ride is prepared when a controller completes the handshake,
     * which means the debug panel on the HUD is unreachable until a companion
     * both connects *and* sends a road. That is the wrong way round for someone
     * whose companion is the thing being debugged: a game that connects and
     * then stalls leaves them stranded on the pairing screen with no tools.
     *
     * Goes through the host rather than calling the session manager directly so
     * that a companion connecting afterwards does not prepare a second ride on
     * top of this one and reset it underneath the rider.
     */
    fun prepareRideWithoutCompanion() {
        prepareOnce()
    }

    /**
     * Prepare the session's ride, once. A check and a set under one lock:
     * checked and set separately, a debug tap and a companion connecting at
     * the same moment could each prepare a ride, the second resetting the
     * first underneath the rider.
     */
    private fun prepareOnce() {
        synchronized(slot) {
            if (ridePrepared) return
            ridePrepared = true
        }
        sessionManager.prepareCompanion(ftp())
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    // One connection ─────────────────────────────────────────────────────────

    private suspend fun serve(sessionId: String, ws: DefaultWebSocketServerSession) {
        val remoteHost = runCatching { ws.call.request.local.remoteHost }.getOrDefault("unknown")

        // An address that has just failed a handshake several times waits
        // before it may try again, so the pairing code cannot be guessed at
        // the speed of the network.
        refusalFor(remoteHost)?.let { reason ->
            runCatching { ws.close(CloseReason(CompanionClose.UNAUTHORIZED.toShort(), reason)) }
            return
        }
        // And there is a ceiling on how many may be connected, or connecting,
        // at once: nothing limited it, and every open socket is a file
        // descriptor shared with the trainer link and the database.
        if (connections.size >= MAX_CONNECTIONS ||
            connections.count { !it.session.isReady } >= MAX_UNAUTHENTICATED
        ) {
            runCatching { ws.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many connections")) }
            return
        }

        val session = CompanionSession(
            config = CompanionConfig(
                appVersion = appVersion,
                deviceId = deviceId(),
                appName = BEACON_NAME,
                sessionId = sessionId,
                token = currentToken,
            ),
            snapshot = ::snapshot,
            controllerConnected = { controller != null },
            pairedKeyFor = { pairedKeys[it] },
            rideState = ::rideState,
        )
        val conn = Conn(session, ws, remoteHost)
        connections.add(conn)
        val writer = ws.launch { writeOut(conn) }
        // A connection that has not finished its handshake in time is closed.
        // One that never said anything at all used to stay open for good.
        val deadline = ws.launch {
            delay(HANDSHAKE_MS)
            val effects = synchronized(session) {
                session.refuseUnauthenticated(
                    "No completed handshake within " + HANDSHAKE_MS / 1000 + " s. Send 'hello' first.",
                )
            }
            if (effects.isNotEmpty()) dispatch(conn, effects)
        }
        // The controller this app closed for something it sent. Gone just the
        // same as one that closed its own socket, and handled the same way below.
        var refusedController = false
        try {
            for (frame in ws.incoming) {
                if (!session.isReady) {
                    // Until the handshake is done, only a short text frame is
                    // read at all. A hello is a few hundred bytes; the frames
                    // that need megabytes - a screenshot - come after it.
                    val refusal = when {
                        frame !is Frame.Text -> "Only text frames are read before the handshake."
                        frame.data.size > PRE_AUTH_MAX_BYTES ->
                            "A frame before the handshake may be at most " + PRE_AUTH_MAX_BYTES + " bytes."
                        else -> null
                    }
                    if (refusal != null) {
                        dispatch(conn, synchronized(session) { session.refuseUnauthenticated(refusal) })
                        break
                    }
                }
                if (frame !is Frame.Text) continue
                val wasController = controller === conn
                val text = frame.readText()
                // The session is also asked for screenshots from the rider's
                // tap, on another thread, and it is not built to be shared.
                val effects = synchronized(session) { session.onFrame(text, System.currentTimeMillis()) }
                val open = dispatch(conn, effects)
                // Registering after the close check, not before it: a
                // connection that has just been closed must not be put back as
                // the controller on its way out.
                if (!open) {
                    // Said out loud. A controller closed from this end used to
                    // leave no trace at all: dispatch had already let go of it,
                    // so nothing below ran, the ride carried on as if the game
                    // were still there, and a game that reconnected a second
                    // later named its next ride onto this one.
                    val close = effects.filterIsInstance<CompanionEffect.Close>().firstOrNull()
                    val error = effects.filterIsInstance<CompanionEffect.Send>()
                        .map { it.frame }.filterIsInstance<ErrorFrame>().firstOrNull()
                    Log.w(
                        TAG,
                        "Closed " + session.clientName + " (" + close?.code + "): " +
                            (error?.message ?: close?.reason),
                    )
                    refusedController = wasController
                    break
                }
                if (!registerRoleIfNeeded(conn)) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Companion connection ended: " + (e.message ?: e::class.simpleName))
        } finally {
            deadline.cancel()
            connections.remove(conn)
            // A refused controller only counts while nobody has taken its place.
            val wasHolding = synchronized(slot) {
                val holding = controller === conn || (refusedController && controller == null)
                if (holding) controller = null
                holding
            }
            if (wasHolding) {
                // Whatever menu it was showing is gone with it. Leaving the pad
                // up would offer the rider buttons with nothing on the other
                // end of them.
                _remoteControls.value = null
                _pendingPairing.value = null
                if (_sessionPhase.value == SessionPhase.LIVE) {
                    _sessionPhase.value = SessionPhase.COMPANION_AWAY
                    // Gone without a `finish`: the game stopped driving the
                    // ride, it did not end it. Pause and wait. Not a countdown
                    // to an ending, because nothing about a game that has gone
                    // quiet says the rider is done, and a ride ended by a timer
                    // while they fetched a drink is a ride they did not choose
                    // to end.
                    if (sessionManager.holdCompanionRide()) {
                        resumeForCompanion = identityOf(conn)
                        Log.i(TAG, "Controller disconnected mid-ride; ride paused until it returns")
                    } else {
                        Log.i(TAG, "Controller disconnected; nothing was riding")
                    }
                }
            }
            publishConnectionCounts()
            // Whatever is still queued - the error and the close that explain
            // why it was dropped - gets its moment on the wire before the
            // handler returns and the socket goes with it.
            conn.outbox.close()
            withContext(NonCancellable) { withTimeoutOrNull(CLOSE_DRAIN_MS) { writer.join() } }
        }
    }

    /** One connection's writer: its queue, in order, onto the socket. */
    private suspend fun writeOut(conn: Conn) {
        try {
            for (item in conn.outbox) {
                conn.queued.decrementAndGet()
                when (item) {
                    is Outbound.Send -> {
                        val sent = withTimeoutOrNull(SEND_TIMEOUT_MS) {
                            conn.ws.send(CompanionCodec.encode(item.frame))
                            true
                        }
                        if (sent == null) {
                            // Not reading. Its socket is closed rather than
                            // written to for ever, which also ends the read
                            // loop and lets the ride be held.
                            Log.w(TAG, conn.session.clientName + " stopped taking frames; closing it")
                            conn.ws.cancel()
                            return
                        }
                    }
                    is Outbound.Close -> {
                        withTimeoutOrNull(SEND_TIMEOUT_MS) {
                            conn.ws.close(
                                CloseReason(
                                    item.code.toShort(),
                                    // Clamped again here because the 125-byte
                                    // cap is a fact about WebSocket frames
                                    // rather than about the protocol, and the
                                    // transport is the last place that can
                                    // still honour it.
                                    CompanionProtocol.closeReason(item.reason),
                                )
                            )
                        }
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Writing to " + conn.session.clientName + " failed: " + (e.message ?: e::class.simpleName))
        }
    }

    /**
     * Who a connection is, as far as handing a held ride back to it goes. A
     * proven pairing where there is one; the name it gave, which the pairing
     * code already vouched for, otherwise. A ride held for a game used to be
     * handed back to anything that connected with the same self-declared name.
     */
    private fun identityOf(conn: Conn): String =
        conn.session.peerDeviceId?.takeIf { conn.session.authenticatedByPairing }?.let { "device:" + it }
            ?: ("name:" + conn.session.clientName)

    /**
     * Note the role the handshake granted, and prepare the ride for a
     * controller. Returns false when the connection has been refused.
     *
     * Preparing here rather than on the first road frame is deliberate. Doing
     * it later would mean [WorkoutSessionManager.prepareCompanion] clearing the
     * engine *after* a road had already landed in it, and the rider would feel
     * the first hill of the ride disappear.
     */
    private suspend fun registerRoleIfNeeded(conn: Conn): Boolean {
        val role = conn.session.role ?: return true
        if (role == HelloFrame.ROLE_CONTROLLER && controller !== conn) {
            // Claimed in one step. Two handshakes that finished together
            // could each see the slot empty and each take it, and the one
            // overwritten could still steer, since what it may do is its own
            // session's to say.
            val claimed = synchronized(slot) {
                val holder = controller
                if (holder == null || holder === conn) {
                    controller = conn
                    true
                } else {
                    false
                }
            }
            if (!claimed) {
                Log.w(TAG, "Refused " + conn.session.clientName + ": another companion took control first")
                dispatch(conn, synchronized(conn.session) { conn.session.refuseControl() })
                return false
            }
            if (lookOwner != null && lookOwner != conn.session.clientName) clearLook()
            _sessionCompanionName.value = conn.session.clientName
            if (_sessionPhase.value == SessionPhase.COMPANION_AWAY) {
                _sessionPhase.value = SessionPhase.LIVE
            }
            // The game that dropped is back: the ride it was driving carries on.
            // Registered after the welcome went out, so the `stateChanged:
            // active` this produces reaches it. A different companion taking
            // over is not handed somebody else's ride mid-effort; it finds the
            // ride paused and can resume it with `setState`, as it would any
            // pause.
            val resumeFor = resumeForCompanion
            if (resumeFor != null) {
                resumeForCompanion = null
                if (resumeFor == identityOf(conn)) {
                    sessionManager.resumeWorkout()
                    Log.i(TAG, "Controller back; ride resumed")
                }
            }
            prepareOnce()
            Log.i(TAG, "Controller connected: " + conn.session.clientName + " from " + conn.remoteHost)
        }
        publishConnectionCounts()
        return true
    }

    /**
     * Count a failed handshake against its address, and against the code.
     *
     * An address that keeps failing waits longer each time before it may try
     * again, and a code that has been guessed at too often is replaced: at a
     * thousand guesses a second across the network, a six-character code held
     * for a whole evening is a real chance, and nothing counted.
     */
    private fun noteAuthFailure(conn: Conn) {
        val now = System.currentTimeMillis()
        val failures = failuresByHost.getOrPut(conn.remoteHost) { Failures() }
        synchronized(failures) {
            failures.count += 1
            if (failures.count >= FREE_FAILURES) {
                val doublings = (failures.count - FREE_FAILURES).coerceAtMost(6)
                failures.blockedUntilMs = now + (1_000L shl doublings).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
        if (codeFailures.incrementAndGet() >= ROTATE_CODE_AFTER) rotateCode()
    }

    private fun refusalFor(host: String): String? {
        val failures = failuresByHost[host] ?: return null
        val waitMs = synchronized(failures) { failures.blockedUntilMs - System.currentTimeMillis() }
        return if (waitMs > 0) "too many failed attempts; try again in " + ((waitMs + 999) / 1000) + " s" else null
    }

    /**
     * Issue a new pairing code. The card shows it at once; paired companions
     * are unaffected, since they never use one. Not in a debug build, whose
     * code is fixed by design.
     */
    private fun rotateCode() {
        if (app.indoorbike.BuildConfig.DEBUG) return
        codeFailures.set(0)
        val token = CompanionPairing.newToken()
        currentToken = token
        _status.update { it.copy(token = token) }
        Log.w(TAG, "Pairing code replaced after repeated failed attempts")
    }

    private fun publishConnectionCounts() {
        val holder = controller
        _screenshotsOffered.value = screenshots != null && holder?.session?.answersScreenshots == true
        _status.update {
            it.copy(
                controllerName = holder?.session?.clientName,
                controllerAddress = holder?.remoteHost,
                observers = connections.count { c -> c.session.role == HelloFrame.ROLE_OBSERVER },
            )
        }
    }

    /**
     * Carry out what the protocol decided.
     *
     * @return false once the socket has been closed, so the read loop stops.
     */
    private suspend fun dispatch(conn: Conn, effects: List<CompanionEffect>): Boolean {
        var open = true
        for (effect in effects) {
            when (effect) {
                is CompanionEffect.Send -> {
                    // A refused field is a bug in the game that the rider only
                    // ever sees as something missing, so it goes in the log too.
                    val frame = effect.frame
                    if (frame is WarningFrame && frame.code == CompanionWarning.REJECTED) {
                        Log.w(TAG, "Refused from " + conn.session.clientName + ": " + frame.message)
                    }
                    // A handshake refused is counted against where it came from.
                    if (frame is ErrorFrame && frame.code == CompanionError.UNAUTHORIZED && !conn.session.isReady) {
                        noteAuthFailure(conn)
                    }
                    conn.send(frame)
                }

                is CompanionEffect.Close -> {
                    // Retire the connection *before* closing it. The telemetry
                    // loop writes to everything in `connections` four times a
                    // second, and a frame queued behind the close would never
                    // be written; the companion should see the code and reason
                    // that explain why it was dropped, not a bare 1006.
                    connections.remove(conn)
                    val wasController = synchronized(slot) {
                        (controller === conn).also { if (it) controller = null }
                    }
                    if (wasController) _remoteControls.value = null
                    conn.outbox.trySend(Outbound.Close(effect.code, effect.reason))
                    conn.outbox.close()
                    open = false
                }

                is CompanionEffect.ApplyRoad -> {
                    engine.setRoad(effect.gradient, effect.speedMps, effect.crr)
                    if (!_status.value.receivingRoad) {
                        _status.update { it.copy(receivingRoad = true) }
                    }
                }

                is CompanionEffect.ReportProgress ->
                    engine.setProgress(effect.distanceM, effect.ascentM)

                is CompanionEffect.Shift ->
                    engine.shiftBy(effect.steps, CompanionEngine.SOURCE_COMPANION)

                is CompanionEffect.SetLabel ->
                    _status.update {
                        it.copy(label = effect.name.ifEmpty { null } ?: it.label, world = effect.world ?: it.world)
                    }

                is CompanionEffect.SetCourse -> applyCourse(effect.course)

                is CompanionEffect.RequestPairing -> {
                    // The one place this design asks for a human. Everything
                    // else about a paired connection happens silently, which is
                    // exactly why the moment trust is granted should not.
                    _pendingPairing.value = PendingPairing(effect.deviceId, effect.name)
                }

                is CompanionEffect.SavePairing -> pairingMutex.withLock {
                    pairedKeys = pairedKeys + (effect.companion.deviceId to effect.companion.keyHex)
                    pairedStore?.save(effect.companion)
                    refreshPairedCompanionsLocked()
                    Log.i(TAG, "Paired with a companion; it will connect without a code from now on")
                }

                is CompanionEffect.PairingProved -> pairingMutex.withLock {
                    // A timestamp, so a stale row in the rider's list is
                    // obvious when they come to prune it.
                    pairedStore?.find(effect.deviceId)?.let {
                        pairedStore.save(it.copy(lastSeenEpochMs = System.currentTimeMillis()))
                        refreshPairedCompanionsLocked()
                    }
                }

                is CompanionEffect.SaveScreenshot -> keepScreenshot(conn, effect)

                is CompanionEffect.ScreenshotFailed -> {
                    if (pendingShots.remove(effect.id) != null) {
                        Log.w(TAG, "No screenshot from " + conn.session.clientName + ": " + effect.reason)
                        showScreenshotStatus(
                            ScreenshotStatus.Kind.FAILED,
                            companionLabel(conn) + " could not take a screenshot: " + effect.reason,
                        )
                    }
                }

                is CompanionEffect.SetControls -> _remoteControls.value = effect.controls

                is CompanionEffect.SetBrand -> applyLook(conn, effect.brand)

                is CompanionEffect.SetDrivetrain -> {
                    companionDrivetrain = effect.drivetrain
                    engine.setDrivetrain(effect.drivetrain)
                    // The ladder that came out is not the one that went in:
                    // near-identical ratios are dropped, so a 2x described as
                    // fifty-odd combinations comes back as twenty gears. Only
                    // this side knows which twenty, so it says.
                    broadcast(
                        EventFrame(
                            event = EventFrame.DRIVETRAIN_CHANGED,
                            index = engine.gearIndex.value,
                            gears = gearLadder(),
                        )
                    )
                }

                is CompanionEffect.SetRideState -> if (sessionManager.state.value == WorkoutState.Completed) {
                    // Between a finish and the next ride being ready, there is
                    // no ride to act on, and the request used to vanish without
                    // a word: a game that asked to start the next lap at once
                    // heard nothing and started nothing.
                    conn.send(
                        WarningFrame(
                            code = CompanionWarning.REJECTED,
                            field = "state",
                            message = "The last ride has just finished and the next is not ready yet; " +
                                "ask again after 'stateChanged: paused'.",
                        )
                    )
                } else when (effect.state) {
                    // Requests, not commands. Each of these ignores a ride that
                    // is not in a state it can act on, so a companion that
                    // pauses twice, or resumes a ride the rider already
                    // resumed, changes nothing. The `stateChanged` the ride
                    // watch sends is the answer either way.
                    CompanionState.PAUSED -> sessionManager.pauseWorkout()

                    // Riding that is not written down. Asking again with a new
                    // label only changes the label, which is why the announce
                    // below is conditional on the state having moved.
                    CompanionState.UNTRACKED ->
                        if (sessionManager.startUntracked(effect.label)) {
                            // Said at once rather than waiting for the ride
                            // watch: a companion sends `setPower` the moment it
                            // hears this, and a target that arrives before the
                            // state does is a target that gets refused.
                            announceState(CompanionState.UNTRACKED)
                        }

                    // Starts the ride the first time, resumes it after that,
                    // and brings it back on the record from untracked. A
                    // companion should not have to know which it is asking for.
                    else -> sessionManager.startOrResumeCompanionRide()
                }

                is CompanionEffect.SetPowerTarget -> {
                    val watts = effect.watts
                    if (watts != null && sessionManager.state.value != WorkoutState.Untracked) {
                        // Checked again here, where it is applied: the ride can
                        // leave untracked riding between the session checking
                        // and this line, and a target that landed then would
                        // hold a recorded ride in ERG.
                        conn.send(
                            WarningFrame(
                                code = CompanionWarning.REJECTED,
                                field = "watts",
                                message = "The ride left untracked riding before the target could be " +
                                    "applied; a power target holds only while untracked.",
                            )
                        )
                    } else if (watts != null && !trainerAcceptsPower()) {
                        // P8: nothing is queued for a trainer that might turn
                        // up later. The rider stays on the road, and the game
                        // can ask again once one is connected.
                        conn.send(
                            WarningFrame(
                                code = CompanionWarning.REJECTED,
                                field = "watts",
                                message = if (trainerConnected()) {
                                    "This trainer will not take a power target " +
                                        "(no FTMS control point); the ride stays on the road."
                                } else {
                                    "No trainer is connected, so there is nothing to " +
                                        "hold at a wattage; the ride stays on the road."
                                },
                            )
                        )
                    } else {
                        engine.setPowerTarget(watts, CompanionEngine.SOURCE_COMPANION)
                    }
                }

                is CompanionEffect.Finish -> {
                    // Any course the summary carried has already been merged by
                    // the SetCourse effect the session emits ahead of this one,
                    // which is the whole reason the ordering is fixed there:
                    // ending the ride is what writes the row.
                    //
                    // The road is taken now, before the ending: it is what the
                    // ride was, and anything the game sends from here on is the
                    // next ride's, which reading the engine at save time used
                    // to give to this one. See [applyCourse].
                    roadAtFinish = sessionManager.pendingRideRoad()
                    sessionManager.endWorkoutEarly()
                    if (sessionManager.state.value != WorkoutState.Completed) roadAtFinish = null
                    // A ride nobody ever pedalled has nothing to save, so that
                    // call deliberately does nothing and the ride watch never
                    // sees a `Completed` to act on. The companion still asked
                    // for the ride to end and is owed an answer, so give it the
                    // same pair of events a real ending produces. Nothing is
                    // written, because nothing happened.
                    if (sessionManager.state.value != WorkoutState.Completed) {
                        announceState(CompanionState.FINISHED)
                        announceState(rideState())
                    }
                }
            }
        }
        return open
    }

    /**
     * Decode the icon off the connection's thread and publish the look.
     *
     * The shared session has already checked the PNG's header, size and shape,
     * so this is a bounded decode of at most 512 px. A PNG that passed those
     * checks and still will not decode is corrupt past its header, and the
     * companion is told so rather than left wondering where its icon went.
     */
    private suspend fun applyLook(conn: Conn, brand: CompanionBrand) {
        if (brand.isEmpty) {
            clearLook()
            return
        }
        val png = brand.iconPng
        val icon = when {
            png == null -> null
            lastIconPng?.contentEquals(png) == true -> lastIcon
            else -> runCatching {
                BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
            }.getOrNull().also { decoded ->
                if (decoded == null) {
                    conn.send(
                        WarningFrame(
                            code = CompanionWarning.REJECTED,
                            field = "icon",
                            message = "The icon's PNG header was fine but the image would not " +
                                "decode. Re-export it; the colours and name were still applied.",
                        )
                    )
                } else {
                    lastIconPng = png
                    lastIcon = decoded
                }
            }
        }
        lookOwner = conn.session.clientName
        _look.value = Look(name = brand.name, icon = icon, palette = brand.palette)
        // And again here, for a companion that sends its brand after the ride
        // is already rolling, or changes it mid-ride.
        rememberBrand()
    }

    /**
     * File the connected companion's name and icon under the ride in progress.
     *
     * Named by the same rule the HUD header uses, so what the card credits is
     * what the rider watched all ride: the brand's name if it sent one, and the
     * name it introduced itself with otherwise.
     */
    private fun rememberBrand() {
        val store = brands ?: return
        val name = _look.value?.name ?: controller?.session?.clientName ?: return
        store.save(
            rideStartEpochMs = sessionManager.sessionStartTimeEpoch,
            name = name,
            iconPng = lastIconPng,
        )
    }

    private fun clearLook() {
        _look.value = null
        lookOwner = null
        lastIconPng = null
        lastIcon = null
    }

    /**
     * Queue [frame] for this connection's writer, in call order. Never waits.
     *
     * Telemetry is the one frame that is fine to lose, the next being 250 ms
     * away, so it is skipped rather than queued behind a backlog. Anything else
     * that finds the queue full means the peer has stopped reading, and it is
     * closed rather than waited on.
     */
    private fun Conn.send(frame: ServerFrame) {
        if (frame is TelemetryFrame && queued.get() >= TELEMETRY_BACKLOG) return
        queued.incrementAndGet()
        val result = outbox.trySend(Outbound.Send(frame))
        if (result.isSuccess) return
        queued.decrementAndGet()
        if (result.isClosed) return
        Log.w(TAG, "Dropping " + session.clientName + ": it has stopped reading")
        ws.cancel()
    }

    // Outbound ───────────────────────────────────────────────────────────────

    /**
     * The rider agreed to remember this companion.
     *
     * Generates the key, writes it down and sends it, in that order. Ignored if
     * nothing is pending, so a double tap cannot mint two keys.
     */
    fun acceptPairing() {
        val holder = controller ?: return
        if (_pendingPairing.value == null) return
        _pendingPairing.value = null
        scope.launch {
            dispatch(holder, synchronized(holder.session) { holder.session.acceptPairing(System.currentTimeMillis()) })
        }
    }

    /** The rider said no. The ride carries on; the code already did its job. */
    fun declinePairing() {
        val holder = controller ?: return
        if (_pendingPairing.value == null) return
        _pendingPairing.value = null
        scope.launch {
            dispatch(holder, synchronized(holder.session) { holder.session.declinePairing("The rider declined.") })
        }
    }

    /**
     * Take the trust back.
     *
     * Both copies, in memory and on disk, because the in-memory one is what a
     * handshake actually consults and a forget that left it behind would be a
     * forget that did nothing until the next restart.
     *
     * The already-connected companion is deliberately left alone. It got in
     * honestly and the rider is revoking future silent access, not ending the
     * ride under themselves; **Take back control** is the button for that.
     */
    fun forgetPairing(deviceId: String) {
        pairedKeys = pairedKeys - deviceId
        scope.launch {
            pairingMutex.withLock {
                pairedStore?.forget(deviceId)
                refreshPairedCompanionsLocked()
            }
        }
    }

    /**
     * Reload both views of the paired companions: the list the card is drawn
     * from, and the key lookup a handshake consults.
     *
     * One function on purpose. They were two, and a refactor moved the list
     * into a flow while quietly leaving the keys behind, so a paired companion
     * was welcomed on its pairing code instead of being challenged. Nothing
     * failed loudly - it just silently stopped being paired. Keeping the two
     * derived from one read means that cannot happen again.
     */
    private suspend fun refreshPairedCompanions() {
        pairingMutex.withLock { refreshPairedCompanionsLocked() }
    }

    /**
     * Call with [pairingMutex] held. A stored key that is not a key is left
     * out: it could never be proved, and until the proof check was made to
     * fail closed it could be matched by an empty proof.
     */
    private suspend fun refreshPairedCompanionsLocked() {
        val all = (pairedStore?.all() ?: emptyList()).filter { CompanionSecurity.looksLikeKey(it.keyHex) }
        _pairedCompanions.value = all
        pairedKeys = all.associate { it.deviceId to it.keyHex }
    }

    /**
     * The rider pressed a control on the pad.
     *
     * Refused unless the companion currently says that control is live, so a
     * press cannot arrive for a menu that has closed or for a button the
     * companion never offered. The check is here rather than in the UI because
     * the UI's idea of what is enabled is a frame old at worst, and the
     * companion should never have to defend against that.
     *
     * Sent to the controller alone. An observer did not put the menu up and has
     * no business being told somebody is steering it.
     */
    fun pressControl(control: String) {
        if (!CompanionControl.isKnown(control)) return
        if (_remoteControls.value?.isEnabled(control) != true) return
        val holder = controller ?: return
        holder.send(EventFrame(event = EventFrame.CONTROL_PRESSED, control = control))
    }

    /**
     * Ask the companion for a picture of its screen, to keep with the ride.
     *
     * Only while a ride is underway, so there is a ride to keep it with, and one
     * at a time: a rider tapping twice gets one picture, not two a frame apart.
     * The ride and the moment are taken now, not when the picture arrives, so a
     * picture that lands after the ride has ended still belongs to that ride.
     */
    fun requestScreenshot() {
        val holder = controller ?: return
        if (screenshots == null || !holder.session.answersScreenshots) return
        val rideStart = sessionManager.sessionStartTimeEpoch
        if (rideStart == 0L) return
        if (_screenshotStatus.value?.kind == ScreenshotStatus.Kind.WAITING) return

        val id = nextShotId.getAndIncrement()
        pendingShots[id] = PendingShot(
            rideStartEpochMs = rideStart,
            rideSeconds = sessionManager.actualRideSeconds.value,
            requestedAtMs = System.currentTimeMillis(),
        )
        val waiting = showScreenshotStatus(
            ScreenshotStatus.Kind.WAITING,
            "Asking " + companionLabel(holder) + " for a screenshot",
        )
        scope.launch {
            dispatch(holder, synchronized(holder.session) { holder.session.requestScreenshot(id) })
            delay(SCREENSHOT_ANSWER_MS)
            // Still waiting on this one. A late picture is still kept when it
            // comes; the rider is only told it has not come yet.
            if (pendingShots.containsKey(id) && _screenshotStatus.value?.seq == waiting) {
                showScreenshotStatus(
                    ScreenshotStatus.Kind.FAILED,
                    companionLabel(holder) + " did not send a screenshot",
                )
            }
        }
    }

    private fun keepScreenshot(conn: Conn, effect: CompanionEffect.SaveScreenshot) {
        val pending = pendingShots.remove(effect.id) ?: return
        val store = screenshots ?: return
        scope.launch {
            runCatching {
                store.save(
                    rideStartEpochMs = pending.rideStartEpochMs,
                    image = effect.image,
                    takenAtEpochMs = pending.requestedAtMs,
                    rideSeconds = pending.rideSeconds,
                )
            }.onSuccess {
                Log.i(
                    TAG,
                    "Screenshot from " + conn.session.clientName + ", " + effect.image.widthPx + "x" +
                        effect.image.heightPx + " " + effect.image.format + ", kept with the ride",
                )
                showScreenshotStatus(ScreenshotStatus.Kind.SAVED, "Screenshot saved with this ride")
            }.onFailure {
                Log.e(TAG, "Could not keep a screenshot", it)
                showScreenshotStatus(ScreenshotStatus.Kind.FAILED, "The screenshot could not be stored")
            }
        }
    }

    /** The name the rider knows the companion by: its brand name, else its `hello` name. */
    private fun companionLabel(conn: Conn): String = _look.value?.name ?: conn.session.clientName

    /** Show a status, and clear it after a while unless it is still waiting. Returns its seq. */
    private fun showScreenshotStatus(kind: ScreenshotStatus.Kind, message: String): Int {
        val seq = screenshotSeq.incrementAndGet()
        _screenshotStatus.value = ScreenshotStatus(seq, kind, message)
        if (kind != ScreenshotStatus.Kind.WAITING) {
            scope.launch {
                delay(SCREENSHOT_NOTICE_MS)
                if (_screenshotStatus.value?.seq == seq) _screenshotStatus.value = null
            }
        }
        return seq
    }

    private fun broadcastGearChanged(index: Int, changed: Boolean, source: String) {
        broadcast(
            EventFrame(
                event = EventFrame.GEAR_CHANGED,
                index = index,
                changed = changed,
                source = source,
            )
        )
    }

    /**
     * The requested power target changed, whoever caused it.
     *
     * Broadcast rather than answered to the asker alone, like `gearChanged`: an
     * observer drawing the rider's screen needs to know they are being held at
     * a wattage, and the companion that asked needs to hear when the ride's own
     * state took its target away.
     */
    private fun broadcastPowerTarget(watts: Int?, source: String) {
        broadcast(
            EventFrame(
                event = EventFrame.POWER_TARGET_CHANGED,
                watts = watts,
                source = source,
            )
        )
    }

    private fun broadcastRoadStale(stale: Boolean) {
        broadcast(
            if (stale) {
                EventFrame(event = EventFrame.WORLD_TIMEOUT, fallback = "flat")
            } else {
                EventFrame(event = EventFrame.WORLD_RESUMED)
            }
        )
        _status.update { it.copy(roadStale = stale) }
    }

    /**
     * Queue [frame] for every connection past its handshake. Straight onto each
     * queue, in call order: each event used to be launched on a coroutine of its
     * own, and two close together could arrive the wrong way round - a
     * `paused` after the `finished` it came before.
     */
    private fun broadcast(frame: ServerFrame) {
        connections.filter { it.session.role != null }.forEach { it.send(frame) }
    }

    /**
     * Push telemetry at the engine's own tick.
     *
     * Locked to [CompanionProtocol.TICK_MS] rather than something faster
     * because the physics only moves four times a second, and a stream that
     * claimed otherwise would be inventing data a companion would then smooth
     * back out.
     */
    private suspend fun telemetryLoop() {
        while (scope.isActive) {
            delay(CompanionProtocol.TICK_MS)
            val ready = connections.filter { it.session.role != null }
            if (ready.isEmpty()) continue
            val frame = telemetryFrame()
            // One frame, two vocabularies: the `state` is the only field that
            // differs, and only for a connection older than 0.10.
            ready.forEach { conn ->
                val state = stateFor(conn, frame.state)
                conn.send(if (state == frame.state) frame else frame.copy(state = state))
            }
        }
    }

    private fun telemetryFrame(): TelemetryFrame {
        val started = sessionManager.sessionStartTimeEpoch.takeIf { it != 0L } ?: hostStartedAtMs
        // While a target holds there is no road being applied, so the three
        // road fields report nothing rather than the hill the engine is still
        // reading and timing underneath. Speed goes with them: the drivetrain
        // is still turning, but it is not turning a road out.
        val holding = engine.powerTarget.value != null
        return TelemetryFrame(
            seq = telemetrySeq++,
            tMono = System.currentTimeMillis() - started,
            state = rideState(),
            // Omitted rather than zeroed when there is no reading: a missing
            // heart rate means no strap, not a heart rate of zero.
            power = powerWatts().takeIf { trainerConnected() },
            cadence = cadenceRpm().takeIf { trainerConnected() },
            hr = heartRateBpm().takeIf { it > 0 },
            // The bike's speed as the phone models it: from the legs while
            // pulling away, so it is there on the first turn of the crank, and
            // carried by the bike's mass after, so a shift cannot make it jump.
            // A companion rides its world at this and sends it back as the
            // road's speed, which is what the resistance is evaluated at; see
            // BikeSpeed for what a jumping one did to every upshift.
            //
            // Omitted rather than zeroed until a crank has turned: a trainer
            // that cannot count cadence gives nothing to pull away from, and
            // echoing the companion's own number back at it would look like
            // agreement and mean nothing.
            speedMps = engine.bikeSpeedMps.value
                .takeIf { engine.driveSpeedKnown.value && !holding },
            gearIndex = engine.gearIndex.value,
            loadFactor = engine.drivetrain.value.loadFactor(engine.gearIndex.value),
            appliedGradient = if (holding) 0.0 else engine.gradient.value,
            trainerForceN = if (holding) 0.0 else engine.trainerForceN.value,
            trainerGradePercent = if (holding) 0.0 else engine.trainerGradePercent.value,
            elapsedS = sessionManager.elapsedSeconds.value.toLong(),
            powerTarget = engine.powerTarget.value,
            // Present exactly when a target is, so a companion can tell "held
            // at 125" from "let go of 125" without guessing.
            powerCommanded = engine.powerCommanded.value.takeIf { holding },
        )
    }

    /**
     * The whole drivetrain with real teeth on it, so a companion can draw
     * "34 x 17" rather than "gear 11 of 20". One description, shared by the
     * welcome and by [EventFrame.DRIVETRAIN_CHANGED], because a ladder
     * described two ways is a ladder that will eventually be described two
     * different ways.
     */
    private fun gearLadder(): List<GearInfo> {
        val drivetrain = engine.drivetrain.value
        return drivetrain.gears.mapIndexed { i, gear ->
            GearInfo(
                i = i,
                ratio = gear.ratio,
                label = gear.label,
                loadFactor = drivetrain.loadFactor(i),
            )
        }
    }

    /**
     * A ride has finished. Write it down, tell the companion, and get ready for
     * the next one.
     *
     * The socket deliberately stays open. The end of a ride is not the end of
     * the evening: the game is still running, the rider is still on the bike,
     * and the most likely next thing either of them does is another lap. So
     * this is a boundary between rides, not a teardown, and a rider who does
     * want to stop leaves by the back button like anywhere else.
     *
     * The order matters. The companion hears first, because it asked for this
     * and should not wait on a disk write to find out it happened. The ride is
     * written second. The session is re-prepared last, because preparing is
     * what clears the engine, and clearing it before the road had been read
     * would save a ride with no road on it.
     */
    private suspend fun onRideEnded() {
        val closing = sessionClosing
        // A session that is ending tells each companion `finished` as it closes
        // the connection, just before the 4005. Announcing it here as well sent
        // it twice.
        if (!closing) {
            announceState(CompanionState.FINISHED)
        }

        rideUnderway = false
        val road = roadAtFinish
        roadAtFinish = null
        val saved = runCatching { rideRecorder?.save(road) }
            .onFailure { Log.e(TAG, "Could not write the companion ride to history", it) }
            .getOrNull()
        if (saved != null) {
            completableRide = saved
            _sessionRides.value = _sessionRides.value + saved
            Log.i(TAG, "Companion ride saved: " + saved.name)
        }

        if (closing) {
            // The last ride of a session that is ending. Nothing to get ready for.
            ridePrepared = false
            finishSessionEnd()
            return
        }

        // Back to ready. `prepareCompanion` lands the session in Standby, which
        // the watch above announces as `paused`, so a companion sees
        // active -> finished -> paused and knows it may start another.
        sessionManager.prepareCompanion(ftp())
        synchronized(slot) { ridePrepared = true }
        // Preparing puts the rider back on their own bike and clears the
        // course. The bike the game described stays for the session, and a
        // course the game sent for the next ride while this one was being
        // written is that ride's.
        companionDrivetrain?.let { engine.setDrivetrain(it) }
        nextRideCourse?.let { course ->
            nextRideCourse = null
            applyCourse(course)
        }
    }

    /**
     * End the session now, keeping whatever ride is underway.
     *
     * Only ever the rider's decision: from the paused-ride panel after a
     * companion has gone, or by backing out of a live session. Nothing ends a
     * session on a timer. A ride in progress, running or paused, is written as
     * an ordinary completed ride rather than dropped: the effort was real, and
     * the rider has just said this is where it stops.
     */
    fun endSession() {
        if (_sessionPhase.value == SessionPhase.ENDED || sessionClosing) return
        sessionClosing = true
        resumeForCompanion = null

        val rideState = sessionManager.state.value
        val underway = sessionManager.sessionStartTimeEpoch != 0L &&
            rideState != WorkoutState.Completed && rideState != WorkoutState.Idle
        if (!underway) {
            finishSessionEnd()
            return
        }
        // Completing the ride hands it to the ride watch, which writes it and
        // then finishes the session end. If the watch is not running there is
        // nobody to hand it to, so do the same work directly.
        val watching = rideWatchJob?.isActive == true
        sessionManager.endWorkoutEarly()
        when {
            sessionManager.state.value != WorkoutState.Completed -> finishSessionEnd()
            !watching -> scope.launch { onRideEnded() }
        }
    }

    private fun finishSessionEnd() {
        _sessionPhase.value = SessionPhase.ENDED
        sessionClosing = false
        Log.i(TAG, "Companion session ended with " + _sessionRides.value.size + " ride(s)")
        stopListening()
    }

    /**
     * Put a course where it belongs: the ride under way, the one that has just
     * finished, or the next one.
     *
     * Every course used to go into the engine, whose course the saved ride is
     * read from and which preparing the next ride clears. So a game that sent
     * `finish` and then `course{name: "Lap 2"}` had its first lap saved as
     * "Lap 2", and the second lap then started unnamed.
     *
     *  - Between a finish and the ride being written, a course continuing the
     *    finished ride is added to it, and any other is kept for the next.
     *  - Once it is written, until the next ride starts, a course continuing it
     *    completes the saved row, and any other begins the next ride.
     *  - Otherwise it is the ride under way's.
     */
    private fun applyCourse(course: CompanionCourse) {
        val ending = roadAtFinish
        if (ending != null) {
            if (course.continuesSavedRide(ending.course?.name?.takeIf { it.isNotBlank() })) {
                roadAtFinish = ending.copy(course = (ending.course ?: CompanionCourse()).mergedWith(course))
            } else {
                nextRideCourse = nextRideCourse?.mergedWith(course) ?: course
            }
            return
        }
        val saved = completableRide
        if (saved != null && course.continuesSavedRide(saved.courseName)) {
            completeRideLater(course)
            return
        }
        if (saved != null) {
            completableRide = null
            Log.i(TAG, "Course for the next ride: " + course.name + "; " + saved.name + " left as saved")
        }
        engine.setCourse(course)
        // The HUD shows what the ride is called while it is happening; the
        // engine keeps the whole course for the moment the ride is written.
        val merged = engine.course.value
        _status.update { it.copy(label = merged.name ?: it.label, world = merged.world ?: it.world) }
    }

    /**
     * Apply a course to the ride that has already been written, if there is one
     * still open to it.
     *
     * Reads [completableRide] on the caller's thread and hands the coroutine a
     * value rather than the field, so a ride that starts while the write is in
     * flight cannot redirect it.
     */
    private fun completeRideLater(course: CompanionCourse) {
        val ride = completableRide ?: return
        val recorder = rideRecorder ?: return
        if (course.isEmpty && course.name.isNullOrBlank()) return
        // A course naming a different ride is the next ride's, sent before the
        // game starts it. The saved ride is finished with: nothing that arrives
        // from here on is about it.
        if (!course.continuesSavedRide(ride.courseName)) {
            completableRide = null
            Log.i(TAG, "Course for the next ride: " + course.name + "; " + ride.name + " left as saved")
            return
        }
        scope.launch {
            val updated = runCatching { recorder.complement(ride, course) }
                .onFailure { Log.w(TAG, "Could not complete the saved ride's course", it) }
                .getOrNull() ?: return@launch
            if (completableRide?.sessionId == updated.sessionId) completableRide = updated
            _sessionRides.value = _sessionRides.value.map {
                if (it.sessionId == updated.sessionId) updated else it
            }
        }
    }

    private fun rideState(): String = sessionManager.state.value.asCompanionState()

    /**
     * The app has more ride states than the protocol does, on purpose.
     *
     * A companion does not need to know the difference between a rider who
     * pressed pause, one who stopped pedalling, and one counting down to the
     * start: in all three the recording is not advancing, and that is the only
     * fact a game can act on. Collapsing them keeps the protocol small and
     * keeps this app free to add a fourth without a version bump.
     */
    private fun WorkoutState.asCompanionState(): String = when (this) {
        WorkoutState.Active -> CompanionState.ACTIVE
        WorkoutState.Paused, WorkoutState.Standby, WorkoutState.Countdown -> CompanionState.PAUSED
        // The one app state the protocol does *not* collapse, because it is the
        // one a game asked for and has to be able to see it got.
        WorkoutState.Untracked -> CompanionState.UNTRACKED
        WorkoutState.Completed -> CompanionState.FINISHED
        WorkoutState.Idle -> CompanionState.LISTENING
    }

    /**
     * [state] in the vocabulary [conn] negotiated.
     *
     * Only `untracked` needs translating, and only downwards: a companion from
     * before 0.10 is told `paused`, which is true in the one way 10.1 says it
     * may act on a state - nothing is being recorded. Inventing a word it has
     * never heard would be worse than telling it less.
     */
    private fun stateFor(conn: Conn, state: String): String =
        if (state == CompanionState.UNTRACKED &&
            (conn.session.version ?: CompanionProtocol.VERSION) < CompanionProtocol.UNTRACKED_VERSION
        ) {
            CompanionState.PAUSED
        } else {
            state
        }

    /**
     * Tell every connection the ride's state, each in its own words, and only
     * when it is news to that connection.
     */
    private fun announceState(state: String) {
        val ready = connections.filter { it.session.role != null }
        if (ready.isEmpty()) return
        val owed = ready.mapNotNull { conn ->
            val mine = stateFor(conn, state)
            if (conn.announcedState == mine) {
                null
            } else {
                conn.announcedState = mine
                conn to EventFrame(event = EventFrame.STATE_CHANGED, state = mine)
            }
        }
        owed.forEach { (conn, frame) -> conn.send(frame) }
    }

    private fun snapshot(): CompanionSnapshot {
        val drivetrain = engine.drivetrain.value
        return CompanionSnapshot(
            rider = RiderInfo(massKg = riderMassKg().toDouble(), ftpWatts = ftp()),
            bike = BikeInfo(massKg = bikeMassKg().toDouble(), drivetrain = drivetrain.id),
            // The whole ladder with real teeth, so a companion can draw "34 x 17"
            // rather than "gear 11 of 20".
            gears = gearLadder(),
            gearIndex = engine.gearIndex.value,
            trainer = TrainerInfo(
                name = trainerName(),
                connected = trainerConnected(),
                control = "sim",
            ),
            resume = null,
        )
    }

    /** A companion waiting on the rider to agree to remember it. */
    data class PendingPairing(val deviceId: String, val name: String)

    private companion object {
        const val TAG = "CompanionHost"
        const val START_TIMEOUT_MS = 5_000L
        const val GRACE_MS = 200L
        const val TIMEOUT_MS = 1_000L

        /** How often a connection is pinged, and how long an unanswered ping is allowed. */
        const val PING_PERIOD_MS = 5_000L
        const val PING_TIMEOUT_MS = 10_000L

        /** The largest frame accepted at all: a screenshot, with room to spare. */
        const val MAX_FRAME_BYTES = 8L * 1024 * 1024

        /** The largest frame read before the handshake is done. A hello is a few hundred bytes. */
        const val PRE_AUTH_MAX_BYTES = 16 * 1024

        /** How long a connection has to finish its handshake, proof included. */
        const val HANDSHAKE_MS = 10_000L

        /** Frames a connection may have queued before it counts as not reading. */
        const val OUTBOX_CAPACITY = 64

        /** Queued frames past which telemetry is skipped rather than queued. */
        const val TELEMETRY_BACKLOG = 4

        /** How long one write may take before the peer counts as gone. */
        const val SEND_TIMEOUT_MS = 5_000L

        /** How long a closing connection's queue gets to reach the wire. */
        const val CLOSE_DRAIN_MS = 2_000L

        /** Connections at once, and of those, ones still in their handshake. */
        const val MAX_CONNECTIONS = 8
        const val MAX_UNAUTHENTICATED = 4

        /** Failed handshakes an address may make before it has to wait. */
        const val FREE_FAILURES = 3

        /** The longest an address is made to wait. */
        const val MAX_BACKOFF_MS = 60_000L

        /** Failed handshakes after which the pairing code is replaced. */
        const val ROTATE_CODE_AFTER = 10

        /** What a companion's device picker shows. Matches the mDNS service name. */
        const val BEACON_NAME = "Indoor Bike"

        /**
         * How long the rider waits before being told no screenshot came. A
         * game has to finish a frame, encode it and send a few hundred
         * kilobytes over Wi-Fi, which on a busy network is seconds, not frames.
         */
        const val SCREENSHOT_ANSWER_MS = 15_000L

        /** How long "saved" or "could not" stays under the ride's name. */
        const val SCREENSHOT_NOTICE_MS = 5_000L
    }
}
