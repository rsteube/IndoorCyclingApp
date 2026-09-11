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
import app.indoorbike.shared.companion.BikeInfo
import app.indoorbike.shared.companion.CompanionAnnouncement
import app.indoorbike.shared.companion.CompanionCodec
import app.indoorbike.shared.companion.CompanionConfig
import app.indoorbike.shared.companion.CompanionCourse
import app.indoorbike.shared.companion.CompanionControl
import app.indoorbike.shared.companion.CompanionEffect
import app.indoorbike.shared.companion.CompanionEngine
import app.indoorbike.shared.companion.CompanionProtocol
import app.indoorbike.shared.companion.CompanionSession
import app.indoorbike.shared.companion.CompanionSnapshot
import app.indoorbike.shared.companion.CompanionState
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
import app.indoorbike.shared.session.WorkoutSessionManager
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.shared.util.Log
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

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
    /** This phone's stable identity, so a paired companion knows which it reached. */
    private val deviceId: () -> String = { "" },
    /** Companions the rider has trusted. Null disables pairing entirely. */
    private val pairedStore: PairedCompanionStore? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val advertiser = CompanionAdvertiser(appContext)

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

    /**
     * True once the companion has been gone long enough to call it a night.
     *
     * Not the same as the socket closing. A game being restarted, a laptop
     * sleeping for a moment and a Wi-Fi hiccup all close a socket, and pulling
     * a rider off their HUD for any of those would be worse than useless. So a
     * disconnect starts a clock, and only running it out ends the session.
     */
    private val _sessionEnded = MutableStateFlow(false)
    val sessionEnded: StateFlow<Boolean> = _sessionEnded.asStateFlow()

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

    private var sessionEndJob: Job? = null

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

    private class Conn(
        val session: CompanionSession,
        val ws: DefaultWebSocketSession,
        /**
         * Serialises everything written to this socket.
         *
         * A Ktor websocket session is not safe for concurrent sends, and this
         * host has three writers: the 4 Hz telemetry loop, event broadcasts off
         * the engine's callbacks, and the per-connection read loop answering a
         * frame. Two of them overlapping interleaves bytes on the wire, and the
         * peer drops the connection with a 1006 rather than the close code it
         * was being sent - which is exactly the failure the protocol's
         * "send the frame *and* the reason" rule exists to prevent.
         */
        val write: Mutex = Mutex(),
    )

    // Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Open the socket and start advertising.
     *
     * Idempotent: calling it while already running does nothing, so a screen
     * that recomposes or an activity that is recreated cannot end up with two
     * servers fighting over a port.
     */
    fun start() {
        if (server != null || startJob != null) return
        hostStartedAtMs = System.currentTimeMillis()
        telemetrySeq = 0
        ridePrepared = false
        rideUnderway = false

        val token = CompanionPairing.newToken()
        val sessionId = CompanionPairing.newSessionId()
        _status.value = CompanionStatus(
            running = false,
            token = token,
            sessionId = sessionId,
            addresses = CompanionPairing.localAddresses(),
        )

        startJob = scope.launch {
            // Before the server, and outside the try: the rider should still be
            // able to see and prune their paired companions on a night when the
            // socket refuses to open.
            refreshPairedCompanions()
            try {
                // Port 0 asks the OS for a free one, which is the right answer
                // for something that only exists for the length of a ride: a
                // fixed port would collide with whatever else the rider runs,
                // and the number reaches the companion through mDNS and the QR
                // payload anyway.
                val started = embeddedServer(CIO, port = 0) {
                    install(WebSockets)
                    routing {
                        webSocket(CompanionProtocol.PATH) {
                            serve(token, sessionId, this)
                        }
                    }
                }
                started.start(wait = false)
                val port = withTimeoutOrNull(START_TIMEOUT_MS) {
                    started.engine.resolvedConnectors().firstOrNull()?.port
                } ?: error("the server did not report a port")

                server = started
                _status.value = _status.value.copy(
                    running = true,
                    port = port,
                    addresses = CompanionPairing.localAddresses(),
                )
                Log.i(TAG, "Companion socket listening on " + port)

                advertiser.start(port, sessionId, appVersion) { advertising ->
                    _status.value = _status.value.copy(advertising = advertising)
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
                telemetryJob = scope.launch { telemetryLoop() }
                // A finished ride has to reach the companion when it finishes,
                // not when the rider gets round to tapping through the summary.
                // Tying this to the screen meant a game sat waiting on a ride
                // that had been over for minutes.
                rideWatchJob = scope.launch {
                    var announced: String? = null
                    // Not `state == Completed`. The session sits on that from
                    // the moment a ride is saved until the next one is
                    // prepared, which includes every second of the pairing
                    // screen the rider comes back to, so the socket would open
                    // and shut itself and the second ride of an evening could
                    // never start. [RideEndWatch] carries the rule and the
                    // tests that pin it.
                    val rideEnd = RideEndWatch()
                    sessionManager.state.collect { rideState ->
                        if (rideEnd.ended(rideState)) {
                            onRideEnded()
                            return@collect
                        }
                        if (rideState == WorkoutState.Completed) return@collect
                        if (rideState == WorkoutState.Active) {
                            // A ride that is running again is a ride the
                            // previous one can no longer be added to.
                            completableRide = null
                            // The first move into active is where this ride
                            // starts measuring from. Not a resume: a rider back
                            // from a pause is continuing, and re-basing there
                            // would discard everything they had already done.
                            if (!rideUnderway) {
                                rideUnderway = true
                                engine.markRideStart()
                            }
                        }
                        // Pausing is not only something a companion asks for.
                        // The rider has a pause button, the stop-pedalling
                        // watchdog has its own, and the foreground service can
                        // force one. A game that only ever heard about the
                        // pauses it requested would carry on rolling its world
                        // while the recording sat still.
                        val now = rideState.asCompanionState()
                        if (now != announced) {
                            announced = now
                            broadcast(EventFrame(event = EventFrame.STATE_CHANGED, state = now))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not open the companion socket", e)
                _status.value = _status.value.copy(
                    running = false,
                    error = "Could not open the companion socket: " + (e.message ?: "unknown error"),
                )
            } finally {
                startJob = null
            }
        }
    }

    /** Close the socket, withdraw the advertisement, and forget every connection. */
    fun stop() {
        startJob?.cancel()
        startJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        rideWatchJob?.cancel()
        rideWatchJob = null
        engine.onGearChanged = null
        engine.onRoadStaleChanged = null
        advertiser.stop()
        beacon.stop()

        val running = server
        server = null
        val closing = connections.toList()
        connections.clear()
        controller = null
        _remoteControls.value = null
        _pendingPairing.value = null
        ridePrepared = false
        sessionEndJob?.cancel()
        sessionEndJob = null
        _sessionEnded.value = false
        // The rides stay in history; the list of them belongs to the session
        // that is ending here, and the next one starts empty.
        _sessionRides.value = emptyList()
        completableRide = null

        if (running != null || closing.isNotEmpty()) {
            // Stopped off the caller's thread: CIO's shutdown joins its
            // selector, and doing that on the main thread while a screen is
            // being disposed is a visible stall.
            scope.launch {
                // Say why before the socket goes away. Without this a
                // companion cannot tell a finished ride from a crashed phone,
                // and the protocol promises it a `stateChanged` and a 4005.
                closing.forEach { runCatching { dispatch(it, it.session.rideEnded()) } }
                runCatching { running?.stop(GRACE_MS, TIMEOUT_MS) }
            }
        }
        _status.value = CompanionStatus()
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
        scope.launch {
            dispatch(holder, holder.session.revoke())
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
        if (ridePrepared) return
        sessionManager.prepareCompanion(ftp())
        ridePrepared = true
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    // One connection ─────────────────────────────────────────────────────────

    private suspend fun serve(token: String, sessionId: String, ws: DefaultWebSocketSession) {
        val session = CompanionSession(
            config = CompanionConfig(
                appVersion = appVersion,
                deviceId = deviceId(),
                appName = BEACON_NAME,
                sessionId = sessionId,
                token = token,
            ),
            snapshot = ::snapshot,
            controllerConnected = { controller != null },
            pairedKeyFor = { pairedKeys[it] },
        )
        val conn = Conn(session, ws)
        connections.add(conn)
        try {
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                val effects = session.onFrame(frame.readText(), System.currentTimeMillis())
                val open = dispatch(conn, effects)
                // Registering after the close check, not before it: a
                // connection that has just been closed must not be put back as
                // the controller on its way out.
                if (!open) break
                registerRoleIfNeeded(conn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Companion connection ended: " + (e.message ?: e::class.simpleName))
        } finally {
            connections.remove(conn)
            if (controller === conn) {
                controller = null
                // Whatever menu it was showing is gone with it. Leaving the pad
                // up would offer the rider buttons with nothing on the other
                // end of them.
                _remoteControls.value = null
                _pendingPairing.value = null
                Log.i(TAG, "Controller disconnected; the road will ramp to flat")
                startSessionEndCountdown()
            }
            publishConnectionCounts()
        }
    }

    /**
     * Note the role the handshake granted, and prepare the ride for a
     * controller.
     *
     * Preparing here rather than on the first road frame is deliberate. Doing
     * it later would mean [WorkoutSessionManager.prepareCompanion] clearing the
     * engine *after* a road had already landed in it, and the rider would feel
     * the first hill of the ride disappear.
     */
    private fun registerRoleIfNeeded(conn: Conn) {
        val role = conn.session.role ?: return
        if (role == HelloFrame.ROLE_CONTROLLER && controller !== conn) {
            controller = conn
            // Whatever was counting down to the end of the session, this is the
            // thing it was waiting for.
            sessionEndJob?.cancel()
            sessionEndJob = null
            _sessionEnded.value = false
            if (!ridePrepared) {
                sessionManager.prepareCompanion(ftp())
                ridePrepared = true
            }
            Log.i(TAG, "Controller connected: " + conn.session.clientName)
        }
        publishConnectionCounts()
    }

    private fun publishConnectionCounts() {
        _status.value = _status.value.copy(
            controllerName = controller?.session?.clientName,
            observers = connections.count { it.session.role == HelloFrame.ROLE_OBSERVER },
        )
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
                is CompanionEffect.Send -> conn.send(effect.frame)

                is CompanionEffect.Close -> {
                    // Retire the connection *before* closing it. The telemetry
                    // loop writes to everything in `connections` four times a
                    // second, and a frame landing on a socket that is closing
                    // aborts it: the companion then sees a bare 1006 instead
                    // of the code and reason that explain why it was dropped,
                    // which defeats the whole point of sending both.
                    connections.remove(conn)
                    if (controller === conn) {
                        controller = null
                        _remoteControls.value = null
                    }
                    runCatching {
                        conn.write.withLock {
                            conn.ws.close(
                                CloseReason(
                                    effect.code.toShort(),
                                    // Clamped again here because the 125-byte
                                    // cap is a fact about WebSocket frames
                                    // rather than about the protocol, and the
                                    // transport is the last place that can
                                    // still honour it.
                                    CompanionProtocol.closeReason(effect.reason),
                                )
                            )
                        }
                    }
                    open = false
                }

                is CompanionEffect.ApplyRoad -> {
                    engine.setRoad(effect.gradient, effect.speedMps)
                    if (!_status.value.receivingRoad) {
                        _status.value = _status.value.copy(receivingRoad = true)
                    }
                }

                is CompanionEffect.ReportProgress ->
                    engine.setProgress(effect.distanceM, effect.ascentM)

                is CompanionEffect.Shift ->
                    engine.shiftBy(effect.steps, CompanionEngine.SOURCE_COMPANION)

                is CompanionEffect.SetLabel ->
                    _status.value = _status.value.copy(
                        label = effect.name,
                        world = effect.world ?: _status.value.world,
                    )

                is CompanionEffect.SetCourse -> {
                    engine.setCourse(effect.course)
                    // The HUD shows what the ride is called while it is
                    // happening; the engine keeps the whole course for the
                    // moment the ride is written to history.
                    val merged = engine.course.value
                    // A course arriving while no ride is running belongs to the
                    // one that just ended. A game that only knows its polyline
                    // after the rider stopped drawing it is following the
                    // specification, not breaking it, and its map should not be
                    // lost to four hundred milliseconds. Read here, on the
                    // frame's own thread, so the write cannot pick up a later
                    // ride's road.
                    completeRideLater(merged)
                    _status.value = _status.value.copy(
                        label = merged.name ?: _status.value.label,
                        world = merged.world ?: _status.value.world,
                    )
                }

                is CompanionEffect.RequestPairing -> {
                    // The one place this design asks for a human. Everything
                    // else about a paired connection happens silently, which is
                    // exactly why the moment trust is granted should not.
                    _pendingPairing.value = PendingPairing(effect.deviceId, effect.name)
                }

                is CompanionEffect.SavePairing -> {
                    pairedKeys = pairedKeys + (effect.companion.deviceId to effect.companion.keyHex)
                    pairedStore?.save(effect.companion)
                    refreshPairedCompanions()
                    Log.i(TAG, "Paired with a companion; it will connect without a code from now on")
                }

                is CompanionEffect.PairingProved -> {
                    // A timestamp, so a stale row in the rider's list is
                    // obvious when they come to prune it.
                    pairedStore?.find(effect.deviceId)?.let {
                        pairedStore.save(it.copy(lastSeenEpochMs = System.currentTimeMillis()))
                        refreshPairedCompanions()
                    }
                }

                is CompanionEffect.SetControls -> _remoteControls.value = effect.controls

                is CompanionEffect.SetDrivetrain -> {
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

                is CompanionEffect.SetRideState -> {
                    // Requests, not commands. `pauseWorkout` ignores anything
                    // that is not an active ride and `resumeWorkout` anything
                    // that is not a paused one, so a companion that pauses
                    // twice, or resumes a ride the rider already resumed,
                    // changes nothing. The `stateChanged` broadcast above is
                    // the answer either way.
                    if (effect.paused) sessionManager.pauseWorkout()
                    // Starts the ride the first time, resumes it after that.
                    // A companion should not have to know which of the two it
                    // is asking for.
                    else sessionManager.startOrResumeCompanionRide()
                }

                is CompanionEffect.Finish -> {
                    // Any course the summary carried has already been merged by
                    // the SetCourse effect the session emits ahead of this one,
                    // which is the whole reason the ordering is fixed there:
                    // ending the ride is what writes the row.
                    sessionManager.endWorkoutEarly()
                    // A ride nobody ever pedalled has nothing to save, so that
                    // call deliberately does nothing and the ride watch never
                    // sees a `Completed` to act on. The companion still asked
                    // for the ride to end and is owed an answer, so give it the
                    // same pair of events a real ending produces. Nothing is
                    // written, because nothing happened.
                    if (sessionManager.state.value != WorkoutState.Completed) {
                        broadcast(
                            EventFrame(
                                event = EventFrame.STATE_CHANGED,
                                state = CompanionState.FINISHED,
                            )
                        )
                        broadcast(
                            EventFrame(event = EventFrame.STATE_CHANGED, state = rideState())
                        )
                    }
                }
            }
        }
        return open
    }

    private suspend fun Conn.send(frame: ServerFrame) {
        runCatching { write.withLock { ws.send(CompanionCodec.encode(frame)) } }
            .onFailure { Log.w(TAG, "Dropping a frame to " + session.clientName + ": " + it.message) }
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
            dispatch(holder, holder.session.acceptPairing(System.currentTimeMillis()))
        }
    }

    /** The rider said no. The ride carries on; the code already did its job. */
    fun declinePairing() {
        val holder = controller ?: return
        if (_pendingPairing.value == null) return
        _pendingPairing.value = null
        scope.launch {
            dispatch(holder, holder.session.declinePairing("The rider declined."))
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
            pairedStore?.forget(deviceId)
            refreshPairedCompanions()
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
        val all = pairedStore?.all() ?: emptyList()
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
        scope.launch {
            holder.send(
                EventFrame(event = EventFrame.CONTROL_PRESSED, control = control)
            )
        }
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

    private fun broadcastRoadStale(stale: Boolean) {
        broadcast(
            if (stale) {
                EventFrame(event = EventFrame.WORLD_TIMEOUT, fallback = "flat")
            } else {
                EventFrame(event = EventFrame.WORLD_RESUMED)
            }
        )
        _status.value = _status.value.copy(roadStale = stale)
    }

    private fun broadcast(frame: ServerFrame) {
        val ready = connections.filter { it.session.role != null }
        if (ready.isEmpty()) return
        scope.launch { ready.forEach { it.send(frame) } }
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
            ready.forEach { it.send(frame) }
        }
    }

    private fun telemetryFrame(): TelemetryFrame {
        val started = sessionManager.sessionStartTimeEpoch.takeIf { it != 0L } ?: hostStartedAtMs
        return TelemetryFrame(
            seq = telemetrySeq++,
            tMono = System.currentTimeMillis() - started,
            state = rideState(),
            // Omitted rather than zeroed when there is no reading: a missing
            // heart rate means no strap, not a heart rate of zero.
            power = powerWatts().takeIf { trainerConnected() },
            cadence = cadenceRpm().takeIf { trainerConnected() },
            hr = heartRateBpm().takeIf { it > 0 },
            // The drivetrain's own speed, known on the first turn of the crank.
            // Omitted rather than zeroed when the trainer cannot count cadence:
            // there is then no drivetrain speed to report, and echoing the
            // companion's own number back at it would look like agreement and
            // mean nothing.
            speedMps = engine.driveSpeedMps.value.takeIf { engine.driveSpeedKnown.value },
            gearIndex = engine.gearIndex.value,
            loadFactor = engine.drivetrain.value.loadFactor(engine.gearIndex.value),
            appliedGradient = engine.gradient.value,
            trainerForceN = engine.trainerForceN.value,
            trainerGradePercent = engine.trainerGradePercent.value,
            elapsedS = sessionManager.elapsedSeconds.value.toLong(),
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
        broadcast(EventFrame(event = EventFrame.STATE_CHANGED, state = CompanionState.FINISHED))

        rideUnderway = false
        val saved = runCatching { rideRecorder?.save() }
            .onFailure { Log.e(TAG, "Could not write the companion ride to history", it) }
            .getOrNull()
        if (saved != null) {
            completableRide = saved
            _sessionRides.value = _sessionRides.value + saved
            Log.i(TAG, "Companion ride saved: " + saved.name)
        }

        // Back to ready. `prepareCompanion` lands the session in Standby, which
        // the watch above announces as `paused`, so a companion sees
        // active -> finished -> paused and knows it may start another.
        sessionManager.prepareCompanion(ftp())
        ridePrepared = true
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

    /**
     * The controller has gone. Start counting, and only call the session over
     * if it stays gone.
     *
     * A game restarting, a laptop sleeping and a Wi-Fi hiccup all look exactly
     * like a rider finishing for the night at the instant the socket closes.
     * The difference only shows up afterwards, so the answer is to wait and
     * see rather than to guess.
     */
    private fun startSessionEndCountdown() {
        sessionEndJob?.cancel()
        sessionEndJob = scope.launch {
            delay(SESSION_END_GRACE_MS)
            if (controller == null) {
                Log.i(TAG, "No companion for " + (SESSION_END_GRACE_MS / 1000) + "s; session over")
                _sessionEnded.value = true
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
        WorkoutState.Completed -> CompanionState.FINISHED
        WorkoutState.Idle -> CompanionState.LISTENING
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

        /** What a companion's device picker shows. Matches the mDNS service name. */
        const val BEACON_NAME = "Indoor Bike"

        /**
         * How long a companion may be gone before the session is called over.
         *
         * Long enough to cover a game restarting or a laptop waking up, short
         * enough that a rider who has stopped is not left staring at a HUD with
         * a flat road on it. The rider is never blocked by it: backing out ends
         * the session at once and shows the same rides.
         */
        const val SESSION_END_GRACE_MS = 25_000L
    }
}
