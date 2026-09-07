/*
 * Copyright (C) 2026 Juhani Lehtimäki
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
import app.indoorbike.shared.companion.CompanionCodec
import app.indoorbike.shared.companion.CompanionConfig
import app.indoorbike.shared.companion.CompanionEffect
import app.indoorbike.shared.companion.CompanionEngine
import app.indoorbike.shared.companion.CompanionProtocol
import app.indoorbike.shared.companion.CompanionSession
import app.indoorbike.shared.companion.CompanionSnapshot
import app.indoorbike.shared.companion.CompanionState
import app.indoorbike.shared.companion.EventFrame
import app.indoorbike.shared.companion.GearInfo
import app.indoorbike.shared.companion.HelloFrame
import app.indoorbike.shared.companion.RiderInfo
import app.indoorbike.shared.companion.ServerFrame
import app.indoorbike.shared.companion.TelemetryFrame
import app.indoorbike.shared.companion.TrainerInfo
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
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val advertiser = CompanionAdvertiser(appContext)

    private val _status = MutableStateFlow(CompanionStatus())
    val status: StateFlow<CompanionStatus> = _status.asStateFlow()

    private var server: EmbeddedServer<*, *>? = null
    private var telemetryJob: Job? = null
    private var startJob: Job? = null

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

        val token = CompanionPairing.newToken()
        val sessionId = CompanionPairing.newSessionId()
        _status.value = CompanionStatus(
            running = false,
            token = token,
            sessionId = sessionId,
            addresses = CompanionPairing.localAddresses(),
        )

        startJob = scope.launch {
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

                engine.onGearChanged = ::broadcastGearChanged
                engine.onRoadStaleChanged = ::broadcastRoadStale
                telemetryJob = scope.launch { telemetryLoop() }
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
        engine.onGearChanged = null
        engine.onRoadStaleChanged = null
        advertiser.stop()

        val running = server
        server = null
        controller = null
        connections.clear()
        ridePrepared = false

        if (running != null) {
            // Stopped off the caller's thread: CIO's shutdown joins its
            // selector, and doing that on the main thread while a screen is
            // being disposed is a visible stall.
            scope.launch { runCatching { running.stop(GRACE_MS, TIMEOUT_MS) } }
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

    fun dispose() {
        stop()
        scope.cancel()
    }

    // One connection ─────────────────────────────────────────────────────────

    private suspend fun serve(token: String, sessionId: String, ws: DefaultWebSocketSession) {
        val session = CompanionSession(
            config = CompanionConfig(
                appVersion = appVersion,
                sessionId = sessionId,
                token = token,
            ),
            snapshot = ::snapshot,
            controllerConnected = { controller != null },
        )
        val conn = Conn(session, ws)
        connections.add(conn)
        try {
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                val effects = session.onFrame(frame.readText(), System.currentTimeMillis())
                val open = dispatch(conn, effects)
                registerRoleIfNeeded(conn)
                if (!open) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "Companion connection ended: " + (e.message ?: e::class.simpleName))
        } finally {
            connections.remove(conn)
            if (controller === conn) {
                controller = null
                Log.i(TAG, "Controller disconnected; the road will ramp to flat")
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
                    runCatching {
                        conn.ws.close(CloseReason(effect.code.toShort(), effect.reason))
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
                    _status.value = _status.value.copy(label = effect.name)

                is CompanionEffect.Finish -> {
                    // The summary is accepted and, for now, only logged: the
                    // session record has no column to hold a shape yet. See
                    // docs/COMPANION_API.md, "Known gaps".
                    effect.summary?.let {
                        Log.i(TAG, "Companion supplied a ride summary (" + it.profile.size + " samples)")
                    }
                    sessionManager.endWorkoutEarly()
                }
            }
        }
        return open
    }

    private suspend fun Conn.send(frame: ServerFrame) {
        runCatching { ws.send(CompanionCodec.encode(frame)) }
            .onFailure { Log.w(TAG, "Dropping a frame to " + session.clientName + ": " + it.message) }
    }

    // Outbound ───────────────────────────────────────────────────────────────

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
            gearIndex = engine.gearIndex.value,
            loadFactor = engine.drivetrain.value.loadFactor(engine.gearIndex.value),
            appliedGradient = engine.gradient.value,
            trainerForceN = engine.trainerForceN.value,
            trainerGradePercent = engine.trainerGradePercent.value,
            elapsedS = sessionManager.elapsedSeconds.value.toLong(),
        )
    }

    private fun rideState(): String = when (sessionManager.state.value) {
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
            gears = drivetrain.gears.mapIndexed { i, gear ->
                GearInfo(
                    i = i,
                    ratio = gear.ratio,
                    label = gear.label,
                    loadFactor = drivetrain.loadFactor(i),
                )
            },
            gearIndex = engine.gearIndex.value,
            trainer = TrainerInfo(
                name = trainerName(),
                connected = trainerConnected(),
                control = "sim",
            ),
            resume = null,
        )
    }

    private companion object {
        const val TAG = "CompanionHost"
        const val START_TIMEOUT_MS = 5_000L
        const val GRACE_MS = 200L
        const val TIMEOUT_MS = 1_000L
    }
}
