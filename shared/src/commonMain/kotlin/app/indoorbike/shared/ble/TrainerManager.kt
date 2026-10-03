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
package app.indoorbike.shared.ble

import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.redactDeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.min

enum class TrainerConnectionState {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Disconnecting,
    Error,
}

/**
 * Owns the BLE connection to a smart trainer and exposes telemetry as flows.
 *
 * Lifecycle is managed by [AppContainer] — call [dispose] when shutting down.
 *
 * Telemetry sources:
 *  - **Cycling Power Service (0x1818)** → Cycling Power Measurement (0x2A63):
 *    parses instantaneous power and crank revolutions for cadence.
 *  - **Fitness Machine Service (0x1826)** → Indoor Bike Data (0x2AD2): power,
 *    cadence and heart rate. The second opinion on a trainer that has both
 *    (a Kickr), and the only one on a smart bike or spin bike that has only
 *    this. [TelemetrySources] decides, reading by reading, which is believed.
 *  - **Heart Rate Service (0x180D)** → Heart Rate Measurement (0x2A37): if
 *    a chest strap is present.
 *
 * Control goes through the FTMS Control Point (0x2AD9), and not every machine
 * accepts every command. The Fitness Machine Feature characteristic says which
 * it does, a refusal says the same thing later, and [TrainerControl] routes
 * each of the rides' two commands (a wattage, a road) to whatever the machine
 * can do: natively, as ERG, as a resistance level steered against measured
 * power, or by the rider's own hand.
 *
 * The mock trainer path is gated explicitly via [connectMock] and is only
 * used when no real BLE connection is active.
 *
 * The radio is [central]: Kable on Android and iOS, BlueZ on Sailfish OS.
 */
class TrainerManager(private val central: BleCentral = platformBleCentral()) {
    private val scope = bleManagerScope()

    private val _connectionState = MutableStateFlow(TrainerConnectionState.Disconnected)
    val connectionState: StateFlow<TrainerConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<TrainerCandidate>>(emptyList())
    val discoveredDevices: StateFlow<List<TrainerCandidate>> = _discoveredDevices.asStateFlow()

    private val _pairedTrainerId = MutableStateFlow<String?>(null)
    val pairedTrainerId: StateFlow<String?> = _pairedTrainerId.asStateFlow()

    private val _connectedTrainerName = MutableStateFlow<String?>(null)
    val connectedTrainerName: StateFlow<String?> = _connectedTrainerName.asStateFlow()

    private val _power = MutableStateFlow(0)
    val power: StateFlow<Int> = _power.asStateFlow()

    private val _cadence = MutableStateFlow(0)
    val cadence: StateFlow<Int> = _cadence.asStateFlow()

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private val _targetPower = MutableStateFlow(0)
    val targetPower: StateFlow<Int> = _targetPower.asStateFlow()

    /**
     * The road most recently commanded in simulation mode, or null while the
     * trainer is in ERG (or in nothing at all). Non-null means [setTargetPower]
     * is not driving this trainer - the rider's own effort is.
     */
    private val _simulationParameters = MutableStateFlow<SimulationParameters?>(null)
    val simulationParameters: StateFlow<SimulationParameters?> = _simulationParameters.asStateFlow()

    /**
     * Human-readable ERG-mode problem, or `null` when ERG is healthy. The UI
     * surfaces this as a banner so the rider knows when target power isn't
     * actually reaching the trainer (trainer can't do ERG, control couldn't be
     * taken, a write failed, or the trainer rejected the command). Cleared on a
     * successful target-power command and on each new connection.
     */
    private val _ergError = MutableStateFlow<String?>(null)
    val ergError: StateFlow<String?> = _ergError.asStateFlow()

    /**
     * Emits the connected trainer's name each time a connection is established
     * (initial connect or auto-reconnect). The UI uses this to show a transient
     * "connected" notification. Replay 0 so a late collector doesn't re-fire it.
     */
    private val _justConnected = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val justConnected: SharedFlow<String> = _justConnected.asSharedFlow()

    private val _isMockTrainer = MutableStateFlow(false)
    val isMockTrainer: StateFlow<Boolean> = _isMockTrainer.asStateFlow()

    /**
     * Manual mock: the mock trainer stops inventing a rider and reports
     * exactly what it is told.
     *
     * The ordinary mock plays a plausible rider, which is what a smoke test
     * wants and what a developer with no bike wants for a route. It is the
     * wrong tool for building a *companion*, where the whole job is provoking
     * a specific reaction: hold 400 W for a moment, drop the cadence to zero,
     * pin the heart rate. That needs a hand on the numbers, not a simulation
     * of a person.
     *
     * Debug-only in practice, because [connectMock] is: the mock trainer is
     * hidden in release builds, and everything here hangs off it.
     */
    private val _mockManual = MutableStateFlow(false)
    val mockManual: StateFlow<Boolean> = _mockManual.asStateFlow()

    /** Last instant (epoch ms) any telemetry was observed; used by UI to show "--" when stale. */
    private val _lastTelemetryEpochMs = MutableStateFlow(0L)
    val lastTelemetryEpochMs: StateFlow<Long> = _lastTelemetryEpochMs.asStateFlow()

    /**
     * What the connected trainer can be told, and so how a wattage and a road
     * each reach it. [TrainerControl.NONE] with no trainer, or one that has no
     * control point.
     */
    private val _control = MutableStateFlow(TrainerControl.NONE)
    val control: StateFlow<TrainerControl> = _control.asStateFlow()

    // Internal state ────────────────────────────────────────────────────────
    // Everything below is touched only on the manager's own thread (see
    // bleManagerScope), apart from what is noted otherwise.
    private var scanJob: Job? = null
    private var mockJob: Job? = null

    // Persistent auto-reconnect, modelled as a single supervisor coroutine
    // (started in `init`) driven by two flags, so there are no job-restart races
    // between the app-lifecycle gate and the startup arm:
    //  - wantReconnect: standing intent to stay on `targetPairedId`. Cleared only
    //    by an explicit disconnect/forget/mock; re-armed by any connect.
    //  - scanningAllowed: lifecycle gate (app foreground OR active ride).
    // The supervisor scans/connects only while BOTH are true, cancels its scan
    // worker the moment either drops, and never tears down an established
    // connection (that's owned by the link) - only scanning pauses.
    private val wantReconnect = MutableStateFlow(false)
    private val scanningAllowed = MutableStateFlow(true)
    private var targetPairedId: String? = null

    /**
     * Whether auto-reconnect was armed when a manual pairing scan took over
     * from it, so backing out of the pairing screen gives it back. See
     * [startScan].
     */
    private var reconnectBeforeScan = false

    /** The current connection, from the first connect attempt until it drops. */
    private var link: BleLink? = null
    private var ftmsControlPointWriter: (suspend (ByteArray) -> Unit)? = null

    /**
     * Whether this link's control point has been looked for yet, found or not.
     * Until it has, a command cannot be routed, so it is held rather than
     * judged: judging it then is what told riders their Kickr "doesn't support
     * ERG" in the second between connecting and finding the control point.
     */
    private var controlKnown = false

    /**
     * Whether the rides have commanded anything since the app started, so a
     * connection restates it. A trainer that drops mid-interval and comes back
     * used to stay out of ERG until the next segment; one connected at app
     * start with nothing to say is left alone.
     */
    private var commanded = false

    /** A command for the FTMS control point: whichever of the two the ride last asked for. */
    private sealed interface ControlWrite {
        data class Power(val watts: Int) : ControlWrite
        data class Road(val parameters: SimulationParameters) : ControlWrite
    }

    /**
     * The FTMS control point's one queue, holding at most the newest command.
     *
     * Every write used to be its own coroutine on a multi-threaded dispatcher,
     * and two launched close together could reach the GATT queue in either
     * order. That is not a theoretical order: a routine road update and a shift
     * a few milliseconds behind it is exactly what a rider shifting on a
     * descent produces. When the older one landed second, the trainer held the
     * pre-shift force, and nothing corrected it until the force next moved
     * enough to be written - seconds, on a steady road. The shift simply had
     * not happened.
     *
     * One consumer writes them in order. Conflated, because only the latest
     * state of the road or the target means anything: a command superseded
     * while the previous write was in flight is dropped rather than written
     * late, so a slow link sheds stale commands instead of queueing them.
     */
    private val controlWrites = Channel<ControlWrite>(Channel.CONFLATED)

    // FTMS control handshake. The Control Point only honors Set Target Power
    // (0x05) after a successful Request Control (0x00); strict trainers (Zwift
    // Hub) reject with "Control Not Permitted" (0x05) otherwise. We confirm the
    // grant from the 0x00 success indication and, until then, pair a Request
    // Control with each target write. If a command is ever rejected we re-request
    // control and re-apply the target rather than giving up. Reset on (re)connect.
    private var ftmsControlGranted = false
    private var ftmsControlRetries = 0

    /**
     * Held by both writers to the control point, the command consumer and the
     * emulation loop, so a command and an emulated restatement never interleave
     * on the GATT queue, and each emulated write reads the command it serves
     * after any newer one has been handled.
     */
    private val controlLock = Mutex()

    // Emulation, for a command the machine cannot take as itself. Guarded by
    // controlLock, apart from the resistance width, which only a refusal flips.
    private var resistanceHold: ResistanceHold? = null
    private var resistanceWide = false
    private var resistanceWidthFlipped = false
    private var emulatedWatts: Int? = null
    private var emulatedLevel: Double? = null
    private var emulatedAtMs = 0L
    private var emulationTickAtMs = 0L

    /**
     * Whether this connection's rider has been told they set the resistance
     * themselves, for a wattage and for a road. Once each: the rides restate a
     * road every few seconds, and a banner that came back each time would be
     * impossible to dismiss.
     */
    private var toldManualPower = false
    private var toldManualRoad = false

    private val sources = TelemetrySources()

    private var isMockPedaling = false
    // Cadence math from CPS uses crank revolution timestamps to derive RPM.
    private var lastCrankRevs: Int? = null
    private var lastCrankEventTime: Int? = null
    /**
     * Wall-clock time of the most recent CPM notification that actually
     * advanced the crank counter. Used to keep cadence stable across single-
     * sample dropouts (the trainer's BLE stack briefly skips events when it's
     * busy processing an FTMS Set-Target write at segment changes — without
     * this, we'd see 0-RPM spikes in saved telemetry charts at every
     * interval boundary, even though the rider never stopped pedaling).
     */
    private var lastCrankActivityEpochMs: Long = 0

    // Public API ─────────────────────────────────────────────────────────────

    /**
     * Whether a [setTargetPower] write would actually hold the rider at it.
     *
     * True for a machine that can be steered onto a wattage through its
     * resistance level as well as for one with ERG. A machine that can be told
     * neither, and one that is not connected, cannot. Asked before a companion
     * is promised a power target, so it can be told which of the two it has
     * run into rather than watching its target quietly do nothing.
     */
    val acceptsTargetPower: Boolean
        get() = _isMockTrainer.value ||
            (_connectionState.value == TrainerConnectionState.Connected &&
                _control.value.power != ControlPath.MANUAL)

    /**
     * Hold the rider at [watts]. Callable from any thread: it records the
     * command and queues it, and the manager's own thread routes it to the
     * machine ([performControlWrite]).
     */
    fun setTargetPower(watts: Int) {
        val clamped = watts.coerceAtLeast(0)
        _targetPower.value = clamped
        // Leaving simulation mode: a Set Target Power write puts the machine
        // back into ERG, so the road we were commanding is no longer in force.
        _simulationParameters.value = null
        writeTargetPower(clamped)
    }

    /**
     * Queue a wattage for the control point, in order behind anything already
     * queued. Also used by the rejection-recovery path.
     */
    private fun writeTargetPower(watts: Int) {
        controlWrites.trySend(ControlWrite.Power(watts.coerceAtLeast(0)))
    }

    /** Dismiss the current ERG error banner. */
    fun clearErgError() {
        _ergError.value = null
    }

    /**
     * Put the trainer into simulation mode on the road described by
     * [parameters]: FTMS Set Indoor Bike Simulation Parameters (0x11), taking
     * control first if the grant has not been confirmed yet.
     *
     * Unlike [setTargetPower] this is not a target the trainer chases. It is a
     * standing description of the road, which the machine applies against its
     * own wheel speed until the next one arrives, so the rider's watts are
     * theirs to choose. The caller re-sends it as the gradient changes.
     *
     * A machine that cannot simulate still gets the road, as the wattage it
     * asks for or as a resistance level ([TrainerControl.road]). One with no
     * control point at all is ridden by hand, and the rider is told so through
     * the same [ergError] banner the ride screen already shows.
     */
    fun setSimulationParameters(parameters: SimulationParameters) {
        _simulationParameters.value = parameters
        // ERG is not driving the trainer any more; a stale target would only
        // mislead the HUD's readouts.
        _targetPower.value = 0
        writeSimulationParameters(parameters)
    }

    /** Queue a road for the control point, in order behind anything already queued. */
    private fun writeSimulationParameters(parameters: SimulationParameters) {
        controlWrites.trySend(ControlWrite.Road(parameters))
    }

    /**
     * Carry one command to the machine by whichever path it can take
     * ([TrainerControl]): as itself, through the emulation, or not at all.
     * Called on the manager's thread with [controlLock] held.
     *
     * A command that arrives before the link's control point has been looked
     * for is held, not judged: it is restated once the link knows what it has
     * ([restateAfterConnect]).
     */
    private suspend fun performControlWrite(write: ControlWrite) {
        commanded = true
        if (!controlKnown) return
        val writer = ftmsControlPointWriter
        val control = _control.value
        val path = when (write) {
            is ControlWrite.Power -> control.power
            is ControlWrite.Road -> control.road
        }
        when (path) {
            ControlPath.NATIVE -> if (writer != null) when (write) {
                is ControlWrite.Power -> sendControl(
                    writer,
                    targetPowerCommand(write.watts),
                    what = "Set Target Power ${write.watts} W",
                    failure = "Couldn't send target power to the trainer. ERG mode may not be working.",
                )
                is ControlWrite.Road -> sendControl(
                    writer,
                    FtmsSimulationCodec.encode(write.parameters),
                    what = "Set Simulation Parameters",
                    failure = "Couldn't send the road gradient to the trainer. " +
                        "Simulation resistance may not be working.",
                )
            }
            // A command the rider caused lands now rather than on the next tick.
            ControlPath.VIA_POWER, ControlPath.VIA_RESISTANCE -> emulate(nowMs(), forced = true)
            ControlPath.MANUAL -> tellManual(write)
        }
    }

    /**
     * Write one command to the control point, taking control first if the grant
     * has not been confirmed yet. Returns whether both writes went out.
     *
     * Both writes wait for the write response, which serialises them on the GATT
     * queue, so the trainer processes the control grant before the command that
     * follows. That is what makes strict trainers (Zwift Hub) honour it instead
     * of replying "Control Not Permitted". A trainer that rejects the command
     * still says so through the control-point indication
     * ([handleControlPointResponse]), which drives recovery.
     *
     * A write going out is not the machine accepting it, so this never clears
     * the error banner: the machine's own success indication does. Clearing it
     * here made a trainer that refuses every road flicker its banner on and off
     * for a whole route, and wiped a "close the other app" message the moment
     * the next write reached the radio.
     */
    private suspend fun sendControl(
        writer: suspend (ByteArray) -> Unit,
        payload: ByteArray,
        what: String,
        failure: String,
    ): Boolean = try {
        if (!ftmsControlGranted) writer(byteArrayOf(0x00)) // Request Control
        writer(payload)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "FTMS $what write failed", e)
        // A write that failed because the link was going away is the drop,
        // which says so for itself; raising this over it would outlive it.
        if (_connectionState.value == TrainerConnectionState.Connected) _ergError.value = failure
        false
    }

    /**
     * One step of whichever emulation the current command needs, if any.
     * Called with [controlLock] held, every [EMULATION_TICK_MS] while one is
     * running and at once ([forced]) when the rides send a command.
     *
     * The command is read from [targetPower] and [simulationParameters] rather
     * than carried in, so a step always serves the newest one.
     */
    private suspend fun emulate(nowMs: Long, forced: Boolean) {
        val writer = ftmsControlPointWriter ?: return
        val control = _control.value
        val road = _simulationParameters.value
        val path = if (road != null) control.road else control.power
        val watts = if (road != null) RoadAsPower.watts(road, _cadence.value) else _targetPower.value
        val dtSeconds = if (emulationTickAtMs == 0L) EMULATION_TICK_MS / 1000.0
        else (nowMs - emulationTickAtMs) / 1000.0
        emulationTickAtMs = nowMs
        val restateDue = nowMs - emulatedAtMs >= EMULATED_RESTATE_MS

        when (path) {
            ControlPath.VIA_POWER -> {
                val last = emulatedWatts
                val due = forced || restateDue || last == null || abs(watts - last) >= EMULATED_WATTS_EPSILON
                if (!due) return
                val sent = sendControl(
                    writer,
                    targetPowerCommand(watts),
                    what = "Set Target Power $watts W (road)",
                    failure = "Couldn't send the road to the trainer. Its resistance may not be following the route.",
                )
                if (sent) {
                    emulatedWatts = watts
                    emulatedAtMs = nowMs
                }
            }
            ControlPath.VIA_RESISTANCE -> {
                val hold = resistanceHold ?: return
                val level = hold.update(watts, _power.value, _cadence.value, dtSeconds)
                if (!forced && !restateDue && level == emulatedLevel) return
                val sent = sendControl(
                    writer,
                    FtmsResistanceCodec.encode(level, resistanceWide),
                    what = "Set Target Resistance Level $level",
                    failure = "Couldn't send a resistance level to the trainer. " +
                        "Its resistance may not be following the ride.",
                )
                if (sent) {
                    emulatedLevel = level
                    emulatedAtMs = nowMs
                }
            }
            ControlPath.NATIVE, ControlPath.MANUAL -> Unit
        }
    }

    /** Whether the current command needs [emulate] running on a clock. */
    private fun needsEmulation(control: TrainerControl, road: SimulationParameters?, watts: Int): Boolean =
        if (road != null) {
            control.road == ControlPath.VIA_POWER || control.road == ControlPath.VIA_RESISTANCE
        } else {
            // A zero target on a resistance-only machine is one write to its
            // lightest level, which the command itself makes.
            watts > 0 && control.power == ControlPath.VIA_RESISTANCE
        }

    /** Fresh emulation state for a machine that has just said what it can take. */
    private fun resetEmulation(control: TrainerControl) {
        resistanceHold = control.resistanceRange?.let(::ResistanceHold)
        resistanceWide = control.resistanceRange?.let(FtmsResistanceCodec::startsWide) ?: false
        resistanceWidthFlipped = false
        emulatedWatts = null
        emulatedLevel = null
        emulatedAtMs = 0L
        emulationTickAtMs = 0L
    }

    /**
     * Tell a rider whose machine cannot be told [write] at all that the
     * resistance is theirs to set, once per connection for each kind. A zero
     * target on a machine nobody is steering changes nothing, so says nothing.
     */
    private fun tellManual(write: ControlWrite) {
        if (_isMockTrainer.value || _connectionState.value != TrainerConnectionState.Connected) return
        when (write) {
            is ControlWrite.Power -> {
                if (write.watts <= 0 || toldManualPower) return
                toldManualPower = true
                _ergError.value = "This trainer's resistance can't be set from the app. Set it " +
                    "yourself to hold the target; your power is still recorded as you ride."
            }
            is ControlWrite.Road -> {
                if (toldManualRoad) return
                toldManualRoad = true
                _ergError.value = "This trainer's resistance can't be set from the app, so the " +
                    "road won't change it. Set it yourself as the road changes; your speed still " +
                    "follows your power."
            }
        }
    }

    private fun targetPowerCommand(watts: Int): ByteArray {
        val w = watts.coerceIn(0, 0x7FFF)
        return byteArrayOf(0x05, (w and 0xFF).toByte(), ((w shr 8) and 0xFF).toByte())
    }

    /**
     * Hand the mock trainer's readings over to the caller, or give them back.
     *
     * Turning it on holds whatever is currently on the flows rather than
     * zeroing them, so a developer flipping the switch mid-ride does not stall
     * the bike underneath themselves.
     */
    fun setMockManual(enabled: Boolean) {
        if (!_isMockTrainer.value) return
        _mockManual.value = enabled
        touchTelemetry()
    }

    /**
     * Write mock readings directly. Only honoured while [mockManual] is on, so
     * a stray call cannot interfere with a real ride or with the automatic
     * mock.
     *
     * Null leaves a reading alone, which is what lets the caller nudge power
     * without having to restate cadence and heart rate every time.
     */
    fun setMockTelemetry(
        powerWatts: Int? = null,
        cadenceRpm: Int? = null,
        heartRateBpm: Int? = null,
    ) {
        if (!_isMockTrainer.value || !_mockManual.value) return
        powerWatts?.let { _power.value = it.coerceAtLeast(0) }
        cadenceRpm?.let { _cadence.value = it.coerceAtLeast(0) }
        heartRateBpm?.let { _heartRate.value = it.coerceAtLeast(0) }
        // Without this the HUD greys the numbers out after a few seconds of a
        // developer not touching a button, which reads as a broken link.
        touchTelemetry()
    }

    fun toggleMockPedaling() = onManager {
        isMockPedaling = !isMockPedaling
        if (!isMockPedaling) zeroTelemetry()
    }

    fun connectMock() = onManager {
        // A mock session is an explicit override: stop chasing the real
        // trainer, and end its link so mock and real telemetry can never both
        // write the same flows.
        wantReconnect.value = false
        reconnectBeforeScan = false
        targetPairedId = null
        scanJob?.cancel()
        scanJob = null
        link?.let { endLink(it) }

        _isMockTrainer.value = true
        _mockManual.value = false
        _connectedTrainerName.value = "Mock Trainer"
        _pairedTrainerId.value = "mock"
        _connectionState.value = TrainerConnectionState.Connected
        startMockTelemetryStream()
    }

    /**
     * Arm persistent auto-reconnect to a previously paired trainer. Unlike a
     * one-shot scan, this records the standing intent to stay connected and
     * keeps retrying (in scan windows with backoff) until the trainer is found -
     * so opening the app before the trainer wakes up still results in a
     * connection once it starts advertising. Scanning is gated to
     * foreground/active-ride via [pauseAutoReconnect] / [resumeAutoReconnect].
     *
     * No-op for the "mock" pseudo-id and while a mock session is active.
     */
    fun autoConnectIfPaired(pairedId: String?) = onManager {
        if (pairedId.isNullOrBlank() || pairedId == "mock") return@onManager
        if (_isMockTrainer.value) return@onManager

        targetPairedId = pairedId
        _pairedTrainerId.value = pairedId
        wantReconnect.value = true
    }

    /**
     * Lifecycle gate: stop scanning while the app is backgrounded with no active
     * ride, to spare the radio. Keeps the standing reconnect intent and does NOT
     * drop an established connection - only scanning pauses. Resume with
     * [resumeAutoReconnect].
     */
    fun pauseAutoReconnect() { scanningAllowed.value = false }

    /** Lifecycle gate: allow scanning again (app foregrounded or ride started). */
    fun resumeAutoReconnect() { scanningAllowed.value = true }

    private fun gateOpen() = combine(wantReconnect, scanningAllowed) { want, allowed -> want && allowed }

    /**
     * Single long-lived coroutine (started in [init]). Parks until both flags
     * are set, then runs [reconnectWorker] until either flag clears, cancelling
     * the in-flight scan promptly. No job is ever restarted from a public
     * method, so concurrent gate flips and arms can't spawn competing loops.
     */
    private suspend fun reconnectSupervisor() {
        while (true) {
            gateOpen().first { it }
            coroutineScope {
                val worker = launch { reconnectWorker() }
                gateOpen().first { !it }
                worker.cancelAndJoin()
            }
            // Don't leave the UI stuck on "Scanning…" after the gate closed.
            if (_connectionState.value == TrainerConnectionState.Scanning) {
                _connectionState.value = TrainerConnectionState.Disconnected
            }
        }
    }

    private suspend fun reconnectWorker() {
        var backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
        while (true) {
            val id = targetPairedId
            if (id == null) {
                delay(BleReconnectPolicy.INITIAL_BACKOFF_MS)
                continue
            }

            // Already up (or coming up): hold for the whole connected lifetime
            // and only rescan once the link drops.
            val state = _connectionState.value
            if (state == TrainerConnectionState.Connected ||
                state == TrainerConnectionState.Connecting
            ) {
                _connectionState.first {
                    it == TrainerConnectionState.Disconnected || it == TrainerConnectionState.Error
                }
                backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
                continue
            }

            // Park while the radio is off rather than scanning into it, and
            // treat that time as not having looked: a backoff grown while we
            // could not scan would otherwise delay the reconnect by up to a
            // minute after the rider turns Bluetooth back on.
            if (awaitBluetoothAvailable(central.availability) { reason -> Log.i(TAG, "Waiting for Bluetooth ($reason)") }) {
                backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
                Log.i(TAG, "Bluetooth available; resuming trainer scans")
            }

            resetDiscoveryCaches()
            _connectionState.value = TrainerConnectionState.Scanning
            val found = withTimeoutOrNull(BleReconnectPolicy.SCAN_WINDOW_MS) {
                try {
                    central.scan(TRAINER_SERVICES)
                        .onEach { addDiscovered(it) }
                        .firstOrNull { it.deviceId == id }
                } catch (e: CancellationException) {
                    // Routine: either the scan window timed out or the lifecycle
                    // gate closed under us. Reporting those as "scan failed" put
                    // a red herring at the top of every bug report drafted after
                    // a launch. Rethrow so `withTimeoutOrNull` sees its timeout.
                    throw e
                } catch (e: BleRadioOffException) {
                    // The radio went off mid-window. Expected, self-explanatory,
                    // and the next pass parks on the adapter, so record the fact
                    // without the stack trace that used to bury a bug report.
                    Log.i(TAG, "Trainer scan stopped: Bluetooth turned off")
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "auto-reconnect scan failed", e)
                    null
                }
            }

            if (found != null) {
                connectInternal(found)
                if (connectionCameUp(_connectionState)) {
                    _connectionState.first {
                        it == TrainerConnectionState.Disconnected || it == TrainerConnectionState.Error
                    }
                    backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
                    delay(BleReconnectPolicy.POST_DROP_DELAY_MS)
                } else {
                    // Found but would not connect: held by another app, or a
                    // GATT error. That used to reset the backoff like any other
                    // ending, so it was retried every few seconds for ever.
                    Log.i(TAG, "Trainer found but didn't connect; next attempt in ${backoff / 1000} s")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(BleReconnectPolicy.MAX_BACKOFF_MS)
                }
            } else {
                // Trainer still asleep / out of range. Settle the UI and back
                // off before the next window (this is the wake-up watch path).
                if (_connectionState.value == TrainerConnectionState.Scanning) {
                    _connectionState.value = TrainerConnectionState.Disconnected
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(BleReconnectPolicy.MAX_BACKOFF_MS)
            }
        }
    }

    /**
     * A manual pairing scan. It takes over from background auto-reconnect so
     * the two don't fight over the radio, and gives it back in [stopScan]:
     * backing out of the pairing screen used to leave auto-reconnect off for
     * the rest of the session, so a paired trainer woken a minute later never
     * came back.
     */
    fun startScan() = onManager {
        if (_connectionState.value == TrainerConnectionState.Connected) return@onManager

        reconnectBeforeScan = reconnectBeforeScan || wantReconnect.value
        wantReconnect.value = false

        _connectionState.value = TrainerConnectionState.Scanning
        // Clears the advertisement map alongside the list. It used to clear
        // only the list, so every manual scan added to a map that nothing ever
        // emptied.
        resetDiscoveryCaches()

        scanJob?.cancel()
        scanJob = central.scan(TRAINER_SERVICES)
            .onEach { advertisement -> addDiscovered(advertisement) }
            .catch { e ->
                Log.e(TAG, "Scan failed", e)
                _connectionState.value = TrainerConnectionState.Error
            }
            .launchIn(scope)
    }

    /** End a manual pairing scan, giving auto-reconnect back if it had it. */
    fun stopScan() = onManager {
        endScan()
        if (reconnectBeforeScan && targetPairedId != null) wantReconnect.value = true
        reconnectBeforeScan = false
    }

    private fun endScan() {
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value == TrainerConnectionState.Scanning) {
            _connectionState.value = TrainerConnectionState.Disconnected
        }
    }

    fun connectToCandidate(candidateId: String) = onManager {
        // Look up the underlying advertisement; we keep it around because the
        // transport connects from the advertisement (its platform handle), not an id.
        val ad = lastAdvertisements[candidateId]
        if (ad == null) {
            Log.w(TAG, "connectToCandidate: unknown id=${redactDeviceId(candidateId)}")
            _connectionState.value = TrainerConnectionState.Error
            return@onManager
        }
        connectInternal(ad)
    }

    fun disconnect() = onManager { disconnectInternal() }

    private fun disconnectInternal() {
        // Explicit user action: stop auto-reconnecting until they reconnect/pair.
        wantReconnect.value = false
        reconnectBeforeScan = false
        link?.let { endLink(it) }
        if (_isMockTrainer.value) {
            _isMockTrainer.value = false
            stopMockTelemetryStream()
        }
        _connectedTrainerName.value = null
        _connectionState.value = TrainerConnectionState.Disconnected
    }

    fun forgetTrainer() = onManager {
        disconnectInternal()
        targetPairedId = null
        _pairedTrainerId.value = null
    }

    /** Cancel all in-flight jobs and BLE connections. Call once at app shutdown. */
    fun dispose() {
        scope.cancel()
    }

    // Internals ──────────────────────────────────────────────────────────────

    private val lastAdvertisements = mutableMapOf<String, BleAdvertisement>()

    /** Run [block] on the manager's thread. Every public entry point that touches its state does. */
    private fun onManager(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    // Declared after all state the supervisor touches, so they're initialised
    // before its coroutine body runs.
    init {
        scope.launch { reconnectSupervisor() }
        scope.launch { for (write in controlWrites) controlLock.withLock { performControlWrite(write) } }
        // The emulation's clock, running only while a command needs it: a road
        // on a machine that cannot simulate, or a wattage on one that has only
        // a resistance level. Both have to follow the rider between commands,
        // the first because the wattage a road asks for moves with cadence, the
        // second because the level is steered by what it measures.
        scope.launch {
            combine(_control, _simulationParameters, _targetPower, ::needsEmulation)
                .distinctUntilChanged()
                .collectLatest { running ->
                    if (!running) return@collectLatest
                    while (true) {
                        delay(EMULATION_TICK_MS)
                        controlLock.withLock { emulate(nowMs(), forced = false) }
                    }
                }
        }
    }

    /**
     * Forget what the previous scan window saw.
     *
     * [lastAdvertisements] had nothing that ever emptied it - not even
     * [startScan], which cleared only the visible list - so it accumulated for
     * the life of the process. This scan is service-filtered to trainers, so
     * the growth is far slower than the HRM's and the controller's unfiltered
     * ones, but a rider whose trainer is asleep still scans in windows for a
     * whole session and every window's advertisements stayed behind it.
     *
     * Clearing both together preserves the invariant the pairing screen relies
     * on: everything listed in [_discoveredDevices] has an advertisement in
     * [lastAdvertisements] for the tap to connect with. The window that follows
     * repopulates both within a second or two of live advertisements, which is
     * fresher than what it replaces.
     */
    private fun resetDiscoveryCaches() {
        lastAdvertisements.clear()
        _discoveredDevices.value = emptyList()
    }

    private fun addDiscovered(ad: BleAdvertisement) {
        lastAdvertisements[ad.deviceId] = ad
        val current = _discoveredDevices.value
        if (current.none { it.id == ad.deviceId }) {
            _discoveredDevices.value = current + TrainerCandidate(
                id = ad.deviceId,
                name = ad.name,
                rssi = ad.rssi
            )
        }
    }

    private fun connectInternal(advertisement: BleAdvertisement) {
        endScan()
        // Whatever the previous link was doing, it is over: a trainer tapped
        // while another was still connecting used to stay connected to both.
        link?.let { endLink(it, settle = false) }

        _connectionState.value = TrainerConnectionState.Connecting
        _pairedTrainerId.value = advertisement.deviceId
        _connectedTrainerName.value = advertisement.name ?: "Unknown Trainer"

        // Connecting to a trainer (manual or auto) means we want to stay on it:
        // arm persistent reconnect so a later drop is chased automatically.
        targetPairedId = advertisement.deviceId
        wantReconnect.value = true
        reconnectBeforeScan = false

        // Tear down any mock-trainer state so a real connect after a mock
        // session doesn't have two telemetry sources racing the StateFlows.
        if (_isMockTrainer.value) {
            stopMockTelemetryStream()
            _isMockTrainer.value = false
        }

        // Reset cadence-derivation state on every connect so a trainer swap
        // (Kickr → Wattbike) doesn't compare the new trainer's first crank
        // sample against the old trainer's timestamp and report a 3-second
        // 0-RPM dropout at the moment of reconnect.
        lastCrankRevs = null
        lastCrankEventTime = null
        lastCrankActivityEpochMs = 0
        // Nor is the new trainer's Indoor Bike Data judged by whether the old
        // one's power service was speaking, or its rider assumed to have been
        // told what the old one could not do.
        sources.reset()
        toldManualPower = false
        toldManualRoad = false

        val newLink = BleLink.open(scope, central, advertisement, TAG, label = "Trainer")
        link = newLink

        newLink.scope.launch {
            newLink.states.collect { state ->
                // A link that has been replaced has no say over the manager.
                if (link !== newLink) return@collect
                when (state) {
                    BleLinkState.Connecting -> _connectionState.value = TrainerConnectionState.Connecting
                    BleLinkState.Connected -> {
                        _connectionState.value = TrainerConnectionState.Connected
                        _ergError.value = null
                        _justConnected.tryEmit(_connectedTrainerName.value ?: "Trainer")
                        startTelemetrySubscriptions(newLink)
                    }
                    BleLinkState.Disconnecting -> _connectionState.value = TrainerConnectionState.Disconnecting
                    BleLinkState.Disconnected -> {
                        Log.i(TAG, "Trainer link dropped")
                        endLink(newLink)
                    }
                }
            }
        }
        newLink.scope.launch {
            try {
                newLink.peripheral.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                if (link === newLink) {
                    endLink(newLink, settle = false)
                    _connectedTrainerName.value = null
                    _connectionState.value = TrainerConnectionState.Error
                }
            }
        }
    }

    /**
     * Retire [ending]: forget everything it established, zero what it was
     * reporting, and close it in the background.
     *
     * The readings are the part that mattered most. They used to stay on the
     * flows after a drop, so a ride went on recording the last 250 W every
     * second with nobody pedalling, never auto-paused, and after a reconnect a
     * rider who had stopped kept their old cadence for good.
     *
     * [settle] reports the manager as Disconnected. Off when something else is
     * about to say what the manager is instead: a new link taking over, which
     * must not look like a drop to the reconnect worker, or a failed connect,
     * which reports an error.
     */
    private fun endLink(ending: BleLink, settle: Boolean = true) {
        if (link === ending) {
            link = null
            ftmsControlPointWriter = null
            ftmsControlGranted = false
            controlKnown = false
            _control.value = TrainerControl.NONE
            // Whatever was waiting was for the trainer that just left. The
            // command itself survives on targetPower / simulationParameters,
            // and is restated to whichever trainer connects next.
            controlWrites.tryReceive()
            zeroTelemetry()
            lastCrankRevs = null
            lastCrankEventTime = null
            lastCrankActivityEpochMs = 0
            if (settle) {
                _connectedTrainerName.value = null
                _ergError.value = null
                _connectionState.value = TrainerConnectionState.Disconnected
            }
        }
        scope.launch { ending.close() }
    }

    private fun zeroTelemetry() {
        _power.value = 0
        _cadence.value = 0
        _heartRate.value = 0
    }

    /**
     * Subscribe to whichever telemetry characteristics the peripheral exposes,
     * find its control point, then restate whatever the rides last commanded.
     *
     * Suspending and sequential, by design, and everything launched here lives
     * in the link's scope, so a link that ends takes all of it with it. Each
     * callback also checks it still belongs to the current link, because a
     * notification already in flight when a newer link took over must not
     * write over it.
     */
    private suspend fun startTelemetrySubscriptions(l: BleLink) {
        val peripheral = l.peripheral

        suspend fun trySubscribe(
            label: String,
            service: String,
            characteristic: String,
            onBytes: (ByteArray) -> Unit,
        ) {
            try {
                val char = peripheral.findCharacteristic(service, characteristic) ?: return
                peripheral.observe(char)
                    .onEach { if (link === l) onBytes(it) }
                    .catch { Log.w(TAG, "$label observe failed", it) }
                    .launchIn(l.scope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$label subscribe failed", e)
            }
        }

        trySubscribe("CPS", CYCLING_POWER_SERVICE, CYCLING_POWER_MEASUREMENT) { onCyclingPower(it) }
        trySubscribe("Indoor Bike Data", FITNESS_MACHINE_SERVICE, INDOOR_BIKE_DATA) { onIndoorBikeData(it) }
        trySubscribe("HR", HEART_RATE_SERVICE, HEART_RATE_MEASUREMENT) { onHeartRate(it) }

        // FTMS Control Point - capture a writer closure so commands can reach
        // it, and observe it for the machine's Response Code indications.
        try {
            val char = peripheral.findCharacteristic(FITNESS_MACHINE_SERVICE, FTMS_CONTROL_POINT)
            if (char != null) {
                // What it can be told, settled before the writer exists, so no
                // command is ever routed on a guess about a different machine.
                val control = readControl(peripheral)
                if (link !== l) return
                controlLock.withLock {
                    _control.value = control
                    resetEmulation(control)
                }
                Log.i(
                    TAG,
                    "FTMS targets: power=${control.targets.power} " +
                        "resistance=${control.targets.resistance} simulation=${control.targets.simulation} " +
                        "range=${control.resistanceRange}; wattage ${control.power}, road ${control.road}",
                )
                // Fresh connection: control hasn't been granted yet.
                ftmsControlGranted = false
                ftmsControlRetries = 0
                ftmsControlPointWriter = { payload ->
                    peripheral.write(char, payload, withResponse = true)
                }
                // The FTMS Control Point is Write + Indicate: per spec the machine
                // answers every command with a Response Code (0x80) indication, and
                // strict trainers (e.g. Zwift Hub) refuse to honor any control write,
                // including Request Control and Set Target Power, until the client has
                // enabled indications on the CCCD. Observing the characteristic enables
                // them and lets us report rejected commands back to the rider.
                peripheral.observe(char)
                    .onEach { bytes -> if (link === l) handleControlPointResponse(bytes) }
                    .catch { Log.w(TAG, "FTMS control point observe failed", it) }
                    .launchIn(l.scope)
                // Best-effort early Request Control (0x00). If it lands and the
                // trainer grants control, the success indication sets
                // `ftmsControlGranted` so later target writes skip the re-request.
                // If it doesn't take (a strict trainer may ignore it when sent
                // before indications are fully enabled), it's not fatal: every
                // command pairs a fresh Request Control with itself until the
                // grant is confirmed, and recovers from "Control Not Permitted".
                try {
                    peripheral.write(char, byteArrayOf(0x00), withResponse = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "FTMS early request-control failed (will retry on the next command)", e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "FTMS control point setup failed", e)
        }
        if (link !== l) return
        controlKnown = true
        restateAfterConnect()
    }

    /**
     * Send the connected trainer whatever the rides last commanded.
     *
     * Before this, nothing did. The road modes restate their road every few
     * seconds anyway, but a structured workout only writes at a segment change,
     * so a trainer that dropped three minutes into a twenty-minute interval and
     * came straight back stayed out of ERG for the other seventeen. A command
     * sent while the trainer was still connecting was dropped the same way.
     */
    private fun restateAfterConnect() {
        if (commanded) resendCurrentCommand()
    }

    /** One Cycling Power Measurement notification. */
    internal fun onCyclingPower(bytes: ByteArray, atMs: Long = nowMs()) {
        val reading = parseCyclingPowerMeasurement(bytes) ?: return
        sources.heardPowerService(atMs, carriesCrank = reading.carriesCrank)
        _power.value = reading.powerWatts
        reading.cadenceRpm?.let { _cadence.value = it }
        touchTelemetry()
    }

    /**
     * One Indoor Bike Data notification, believed wherever the dedicated
     * service is silent, which on a machine that has only this is everywhere.
     * See [TelemetrySources].
     */
    internal fun onIndoorBikeData(bytes: ByteArray, atMs: Long = nowMs()) {
        val data = IndoorBikeData.parse(bytes) ?: return
        if (sources.bikeDataPowerCounts(atMs)) data.powerWatts?.let { _power.value = it }
        if (sources.bikeDataCadenceCounts(atMs)) data.cadenceRpm?.let { _cadence.value = it }
        if (sources.bikeDataHeartRateCounts(atMs)) data.heartRateBpm?.let { _heartRate.value = it }
        touchTelemetry()
    }

    /** One Heart Rate Measurement notification from the trainer's own service. */
    internal fun onHeartRate(bytes: ByteArray, atMs: Long = nowMs()) {
        val hr = parseHeartRateMeasurement(bytes) ?: return
        sources.heardHeartRateService(atMs)
        _heartRate.value = hr
        touchTelemetry()
    }

    /**
     * Ask the machine what its control point takes, and the resistance range
     * it takes if a resistance level is among it.
     *
     * A machine that does not answer is taken to take what this app always
     * sent ([FtmsTargets.ASSUMED]); a refusal later corrects that. A machine
     * that accepts a resistance level but will not say its range is not
     * steered through one: a level sent without knowing the range could be
     * anything from off to a wall.
     */
    private suspend fun readControl(peripheral: BlePeripheral): TrainerControl {
        val targets = readFitnessMachine(peripheral, FITNESS_MACHINE_FEATURE)
            ?.let(FtmsTargets::parse) ?: FtmsTargets.ASSUMED
        val range = if (targets.resistance) {
            readFitnessMachine(peripheral, SUPPORTED_RESISTANCE_LEVEL_RANGE)?.let(ResistanceRange::parse)
        } else {
            null
        }
        return TrainerControl(targets, range, hasControlPoint = true)
    }

    private suspend fun readFitnessMachine(peripheral: BlePeripheral, characteristic: String): ByteArray? = try {
        peripheral.findCharacteristic(FITNESS_MACHINE_SERVICE, characteristic)?.let { peripheral.read(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read FTMS characteristic $characteristic", e)
        null
    }

    /**
     * Parse an FTMS Control Point indication. The machine answers every command
     * with `[0x80, requestOpCode, resultCode, ...]`.
     *
     *  - Request Control (0x00) success → control is granted; remember it so we
     *    stop prepending Request Control to every target write.
     *  - A command's success → clear any error.
     *  - "Control Not Permitted" (0x05) → the grant didn't take (or was lost).
     *    Re-request control and re-apply the target rather than giving up;
     *    surface a helpful error only once recovery is exhausted.
     *  - "Op Code Not Supported" (0x02) → the machine cannot take that command
     *    after all, whatever its features said. Route around it
     *    ([TrainerControl]) and re-send the current command the new way.
     *  - "Invalid Parameter" (0x03) to a resistance level → the other width
     *    ([FtmsResistanceCodec]), once.
     *  - Other result codes → surface to the rider.
     */
    internal fun handleControlPointResponse(bytes: ByteArray) {
        val hex = bytes.joinToString(" ") { b -> (b.toInt() and 0xFF).toString(16) }
        Log.d(TAG, "FTMS control point response: $hex")
        if (bytes.size < 3 || (bytes[0].toInt() and 0xFF) != 0x80) return
        val requestOpCode = bytes[1].toInt() and 0xFF
        val resultCode = bytes[2].toInt() and 0xFF

        // Which mode the rejected command belonged to decides how we recover
        // and what we tell the rider: a simulated ride has no target power to
        // re-send, and being told about ERG while climbing a hill would only
        // be confusing.
        val simulating = _simulationParameters.value != null

        if (resultCode == 0x01) { // Success
            when (requestOpCode) {
                0x00 -> { ftmsControlGranted = true; ftmsControlRetries = 0 }
                0x04, 0x05, 0x11 -> { ftmsControlRetries = 0; _ergError.value = null }
            }
            return
        }

        // Control Not Permitted: the trainer doesn't think we hold control.
        if (resultCode == 0x05) {
            ftmsControlGranted = false
            if (ftmsControlRetries < MAX_CONTROL_RETRIES) {
                ftmsControlRetries++
                Log.w(TAG, "FTMS control not permitted; re-requesting control (attempt $ftmsControlRetries)")
                // Re-send Request Control (granted is false) then the command
                // that was refused, serialised on the GATT queue.
                resendCurrentCommand()
            } else {
                val mode = if (simulating) "simulation" else "ERG"
                _ergError.value = "Trainer rejected $mode control. Close any other app " +
                    "connected to the trainer (e.g. Zwift), then re-pair it in Settings."
            }
            return
        }

        // Not supported after all. A road on a machine that refuses one is
        // still a road: as ERG, or as a resistance level, and only as a last
        // resort by hand, which re-sending it the new way works out and says.
        if (resultCode == 0x02) {
            val before = _control.value
            val after = when (requestOpCode) {
                0x04 -> before.withoutResistance()
                0x05 -> before.withoutPower()
                0x11 -> before.withoutSimulation()
                else -> before
            }
            if (after != before) {
                _control.value = after
                Log.i(TAG, "Trainer refused op 0x${requestOpCode.toString(16)}: wattage ${after.power}, road ${after.road}")
                resendCurrentCommand()
                return
            }
        }

        // The other width, once. A machine that refuses both is not one this
        // can steer, and says so below like any other refusal.
        if (resultCode == 0x03 && requestOpCode == 0x04 && !resistanceWidthFlipped) {
            resistanceWidthFlipped = true
            resistanceWide = !resistanceWide
            Log.i(TAG, "Trainer refused a resistance level; trying ${if (resistanceWide) "two bytes" else "one byte"}")
            resendCurrentCommand()
            return
        }

        val reason = when (resultCode) {
            0x02 -> "the command isn't supported"
            0x03 -> when {
                requestOpCode == 0x04 -> "the resistance level was out of range"
                simulating -> "the road parameters were out of range"
                else -> "the target power was out of range"
            }
            else -> "the trainer reported an error"
        }
        val mode = when {
            requestOpCode == 0x04 -> "resistance"
            simulating -> "simulation"
            else -> "ERG"
        }
        _ergError.value = "Trainer rejected the $mode command: $reason."
    }

    /**
     * Queue whatever the rides last asked for again, to go out by whichever
     * path it now takes. After a refusal, a lost control grant or a change of
     * width, it is the command the machine is not currently following.
     */
    private fun resendCurrentCommand() {
        emulatedWatts = null
        emulatedLevel = null
        val road = _simulationParameters.value
        if (road != null) writeSimulationParameters(road) else writeTargetPower(_targetPower.value)
    }

    private fun touchTelemetry() {
        // monotonic stamp via System.currentTimeMillis equivalent — KMM uses commonMain util
        _lastTelemetryEpochMs.value = nowMs()
    }

    private fun startMockTelemetryStream() {
        mockJob?.cancel()
        mockJob = scope.launch {
            while (true) {
                if (_isMockTrainer.value && _mockManual.value) {
                    // Manual: the readings are whatever setMockTelemetry last
                    // wrote. Nothing to generate, but the stamp still has to
                    // be kept fresh or the readings go stale on screen while
                    // they are in fact exactly what was asked for.
                    touchTelemetry()
                } else if (_isMockTrainer.value && isMockPedaling) {
                    val target = _targetPower.value
                    val road = _simulationParameters.value
                    if (road != null) {
                        // Simulation mode has no target to chase: the rider
                        // chooses their effort. The mock plays a rider who
                        // works harder as the road tilts up, which is enough
                        // to drive the route forward and exercise the whole
                        // gradient / gear / speed loop without a trainer.
                        val climbWatts = (road.gradePercent * 12.0).toInt().coerceIn(-60, 220)
                        _power.value = (170 + climbWatts + (-8..8).random()).coerceAtLeast(0)
                        _cadence.value = (80..92).random()
                        _heartRate.value = (135..160).random()
                    } else if (target > 0) {
                        _power.value = target + (-5..5).random()
                        _cadence.value = (85..95).random()
                        _heartRate.value = (140..155).random()
                    } else {
                        _power.value = (20..50).random()
                        _cadence.value = (60..80).random()
                        _heartRate.value = (110..120).random()
                    }
                    touchTelemetry()
                } else if (!_isMockTrainer.value) {
                    // No active mock, no need to keep ticking; bail out and let connectMock restart us.
                    break
                }
                delay(1000)
            }
        }
    }

    private fun stopMockTelemetryStream() {
        mockJob?.cancel()
        mockJob = null
        isMockPedaling = false
        _mockManual.value = false
        _power.value = 0
        _cadence.value = 0
        _heartRate.value = 0
    }

    // BLE byte parsing ───────────────────────────────────────────────────────

    /**
     * @param carriesCrank whether the packet had crank data at all, which is
     *   not the same as a cadence: most packets carry the same crank count as
     *   the last and yield none.
     */
    private data class CpsReading(val powerWatts: Int, val cadenceRpm: Int?, val carriesCrank: Boolean)

    private fun parseCyclingPowerMeasurement(bytes: ByteArray): CpsReading? {
        if (bytes.size < 4) return null
        // Bytes 0-1: flags (LE). Bytes 2-3: instantaneous power (sint16, LE),
        // clamped at zero for the reason IndoorBikeData.parse gives.
        val flags = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        val watts = ((bytes[2].toInt() and 0xFF) or (bytes[3].toInt() shl 8)).coerceAtLeast(0)
        var i = 4

        val pedalPowerBalancePresent = (flags and 0x01) != 0
        if (pedalPowerBalancePresent) i += 1
        val accumulatedTorquePresent = (flags and 0x04) != 0
        if (accumulatedTorquePresent) i += 2
        val wheelRevsPresent = (flags and 0x10) != 0
        if (wheelRevsPresent) i += 6 // u32 cumulative + u16 last event time
        val crankRevsPresent = (flags and 0x20) != 0

        var cadence: Int? = null
        if (crankRevsPresent && bytes.size >= i + 4) {
            val crankRevs = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            val crankEventTime = (bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)
            val prevRevs = lastCrankRevs
            val prevTime = lastCrankEventTime
            if (prevRevs != null && prevTime != null) {
                val dRevs = (crankRevs - prevRevs) and 0xFFFF
                val dTime = (crankEventTime - prevTime) and 0xFFFF
                if (dRevs > 0 && dTime > 0) {
                    // crank event time is 1/1024 s ticks
                    val seconds = dTime.toDouble() / 1024.0
                    val rpm = (dRevs * 60.0 / seconds).toInt().coerceIn(0, 240)
                    cadence = rpm
                    lastCrankActivityEpochMs = nowMs()
                } else if (dRevs == 0) {
                    // No new crank event since the previous notification. This
                    // happens routinely when the trainer's notification rate
                    // outpaces the rider's pedal cadence (e.g. a 250ms notify
                    // interval at 80 rpm = a crank every 750ms, so 2 of every
                    // 3 notifies will see dRevs == 0). Treating it as 0 RPM
                    // produces sub-second drop-outs in charts. Only commit a
                    // 0 reading if it's been quiet long enough that the rider
                    // has clearly stopped pedaling.
                    val now = nowMs()
                    // A link whose crank has not turned since it connected
                    // starts the quiet clock now. Waiting for a first turn
                    // instead meant a rider who had stopped before a reconnect
                    // kept their old cadence for the rest of the ride.
                    if (lastCrankActivityEpochMs == 0L) lastCrankActivityEpochMs = now
                    if (now - lastCrankActivityEpochMs > CADENCE_STALE_MS) {
                        cadence = 0
                    }
                    // else: leave `cadence` null so the StateFlow keeps its
                    // last value — auto-pause still triggers via the power
                    // signal, and FIT/Intervals.icu charts stay continuous.
                }
            }
            lastCrankRevs = crankRevs
            lastCrankEventTime = crankEventTime
        }
        return CpsReading(watts, cadence, carriesCrank = crankRevsPresent)
    }

    private fun parseHeartRateMeasurement(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        val flags = bytes[0].toInt() and 0xFF
        val isUInt16 = (flags and 0x01) != 0
        return if (isUInt16) {
            if (bytes.size < 3) null
            else (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        } else {
            if (bytes.size < 2) null
            else bytes[1].toInt() and 0xFF
        }
    }

    companion object {
        private const val TAG = "TrainerManager"

        /**
         * How many times a "Control Not Permitted" rejection triggers an
         * automatic re-request of control + target re-apply before we stop and
         * surface an error. Reset on each app-driven target change and on connect.
         */
        internal const val MAX_CONTROL_RETRIES = 3

        /**
         * Quiet period without a new crank revolution before we commit cadence
         * to 0. Long enough to span normal between-rotation gaps even at very
         * low rpm (e.g. 30 rpm = one rotation every 2 seconds), but short
         * enough that auto-pause kicks in promptly when the rider truly stops.
         */
        private const val CADENCE_STALE_MS = 3_000L

        /**
         * The emulation's clock. Fast enough that a road held as ERG follows a
         * change of cadence within half a second, and that a resistance level
         * gets a fresh measurement between steps; slow enough to leave the
         * GATT queue to the telemetry.
         */
        const val EMULATION_TICK_MS = 500L

        /** Change in an emulated wattage worth a write. */
        const val EMULATED_WATTS_EPSILON = 3

        /** An emulated command unchanged this long is sent again anyway. */
        const val EMULATED_RESTATE_MS = 5_000L

        // Service UUIDs (16-bit assigned numbers, expanded to 128-bit base).
        const val CYCLING_POWER_SERVICE = "00001818-0000-1000-8000-00805f9b34fb"
        const val CYCLING_POWER_MEASUREMENT = "00002a63-0000-1000-8000-00805f9b34fb"
        const val FITNESS_MACHINE_SERVICE = "00001826-0000-1000-8000-00805f9b34fb"
        const val FITNESS_MACHINE_FEATURE = "00002acc-0000-1000-8000-00805f9b34fb"
        const val INDOOR_BIKE_DATA = "00002ad2-0000-1000-8000-00805f9b34fb"
        const val SUPPORTED_RESISTANCE_LEVEL_RANGE = "00002ad6-0000-1000-8000-00805f9b34fb"
        const val FTMS_CONTROL_POINT = "00002ad9-0000-1000-8000-00805f9b34fb"
        const val HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
        const val HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"

        /** What a trainer scan looks for: either service makes a device a trainer. */
        private val TRAINER_SERVICES = listOf(CYCLING_POWER_SERVICE, FITNESS_MACHINE_SERVICE)
    }
}

// nowMs() is provided by util.TimeUtils — re-exported for readability inside this file.
private fun nowMs(): Long = app.indoorbike.shared.util.getCurrentEpochMillis()
