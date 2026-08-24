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
package app.indoorbike.shared.ble

import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.redactDeviceId
import com.benasher44.uuid.uuidFrom
import com.juul.kable.Advertisement
import com.juul.kable.BluetoothDisabledException
import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
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
 *  - **Fitness Machine Service (0x1826)** → Indoor Bike Data (0x2AD2): also
 *    yields power/cadence (Wahoo Kickr supports both; CPS is preferred).
 *    FTMS Control Point (0x2AD9) is used to write target power in ERG mode.
 *  - **Heart Rate Service (0x180D)** → Heart Rate Measurement (0x2A37): if
 *    a chest strap is present.
 *
 * If a real trainer is paired, [setTargetPower] writes to the FTMS Control
 * Point. The mock trainer path is gated explicitly via [connectMock] and is
 * only used when no real BLE connection is active.
 */
class TrainerManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    /** Last instant (epoch ms) any telemetry was observed; used by UI to show "--" when stale. */
    private val _lastTelemetryEpochMs = MutableStateFlow(0L)
    val lastTelemetryEpochMs: StateFlow<Long> = _lastTelemetryEpochMs.asStateFlow()

    // Internal state ────────────────────────────────────────────────────────
    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var stateListenerJob: Job? = null
    private var telemetryJobs: List<Job> = emptyList()
    private var mockJob: Job? = null

    // Persistent auto-reconnect, modelled as a single supervisor coroutine
    // (started in `init`) driven by two flags, so there are no job-restart races
    // between the app-lifecycle gate and the startup arm:
    //  - wantReconnect: standing intent to stay on `targetPairedId`. Cleared only
    //    by an explicit disconnect/forget/mock; re-armed by any connect.
    //  - scanningAllowed: lifecycle gate (app foreground OR active ride).
    // The supervisor scans/connects only while BOTH are true, cancels its scan
    // worker the moment either drops, and never tears down an established
    // connection (that's owned by `connectionJob`) — only scanning pauses.
    private val wantReconnect = MutableStateFlow(false)
    private val scanningAllowed = MutableStateFlow(true)
    private var targetPairedId: String? = null

    private var activePeripheral: Peripheral? = null
    private var ftmsControlPointWriter: (suspend (ByteArray) -> Unit)? = null

    // FTMS control handshake. The Control Point only honors Set Target Power
    // (0x05) after a successful Request Control (0x00); strict trainers (Zwift
    // Hub) reject with "Control Not Permitted" (0x05) otherwise. We confirm the
    // grant from the 0x00 success indication and, until then, pair a Request
    // Control with each target write. If a command is ever rejected we re-request
    // control and re-apply the target rather than giving up. Reset on (re)connect.
    private var ftmsControlGranted = false
    private var ftmsControlRetries = 0

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

    fun setTargetPower(watts: Int) {
        val clamped = watts.coerceAtLeast(0)
        _targetPower.value = clamped
        if (ftmsControlPointWriter == null) {
            // No FTMS Control Point on this peripheral. Only a problem for a real
            // trainer we're actually connected to — the mock path and the
            // disconnected state legitimately have no writer.
            if (_connectionState.value == TrainerConnectionState.Connected && !_isMockTrainer.value) {
                _ergError.value = "This trainer doesn't support ERG mode " +
                    "(no FTMS control point). Target power can't be set automatically."
            }
            return
        }
        // App-driven target change: fresh recovery budget for the control handshake.
        ftmsControlRetries = 0
        writeTargetPower(clamped)
    }

    /**
     * Write Set Target Power (0x05) to the FTMS Control Point, first taking
     * control (0x00) if the grant hasn't been confirmed yet. Both writes use
     * WriteType.WithResponse, which serialises them on the GATT queue, so the
     * trainer processes the control grant before the target that follows — this
     * is what makes strict trainers (Zwift Hub) actually honor ERG instead of
     * replying "Control Not Permitted". Also used by the rejection-recovery path.
     */
    private fun writeTargetPower(watts: Int) {
        val writer = ftmsControlPointWriter ?: return
        val clamped = watts.coerceAtLeast(0)
        scope.launch {
            try {
                if (!ftmsControlGranted) {
                    writer(byteArrayOf(0x00)) // Request Control
                }
                writer(
                    byteArrayOf(
                        0x05,
                        (clamped and 0xFF).toByte(),
                        ((clamped shr 8) and 0xFF).toByte()
                    )
                )
                // The BLE write was acknowledged. A trainer that rejects the
                // command still reports it via the control-point indication
                // (handleControlPointResponse), which drives recovery / errors;
                // for now clear any stale transient failure.
                _ergError.value = null
            } catch (e: Exception) {
                Log.w(TAG, "FTMS Set Target Power write failed: $clamped W", e)
                _ergError.value = "Couldn't send target power to the trainer. " +
                    "ERG mode may not be working."
            }
        }
    }

    /** Dismiss the current ERG error banner. */
    fun clearErgError() {
        _ergError.value = null
    }

    fun toggleMockPedaling() {
        isMockPedaling = !isMockPedaling
        if (!isMockPedaling) {
            _power.value = 0
            _cadence.value = 0
            _heartRate.value = 0
        }
    }

    fun connectMock() {
        // Tear down any in-flight real connection first so mock and real
        // telemetry can never both write the same StateFlows. Without this,
        // toggling Mock while a real trainer is connected leaves the BLE
        // observe jobs running and racing the mock stream.
        // A mock session is an explicit override — stop chasing the real trainer.
        wantReconnect.value = false
        targetPairedId = null

        scope.launch {
            try {
                activePeripheral?.disconnect()
            } catch (_: Exception) { /* best-effort */ }
        }
        scanJob?.cancel()
        stateListenerJob?.cancel()
        telemetryJobs.forEach { it.cancel() }
        telemetryJobs = emptyList()
        ftmsControlPointWriter = null
        activePeripheral = null

        _isMockTrainer.value = true
        _connectedTrainerName.value = "Mock Trainer"
        _pairedTrainerId.value = "mock"
        _connectionState.value = TrainerConnectionState.Connected
        startMockTelemetryStream()
    }

    /**
     * Arm persistent auto-reconnect to a previously paired trainer. Unlike a
     * one-shot scan, this records the standing intent to stay connected and
     * keeps retrying (in scan windows with backoff) until the trainer is found —
     * so opening the app before the trainer wakes up still results in a
     * connection once it starts advertising. Scanning is gated to
     * foreground/active-ride via [pauseAutoReconnect] / [resumeAutoReconnect].
     *
     * No-op for the "mock" pseudo-id and while a mock session is active.
     */
    fun autoConnectIfPaired(pairedId: String?) {
        if (pairedId.isNullOrBlank() || pairedId == "mock") return
        if (_isMockTrainer.value) return

        targetPairedId = pairedId
        _pairedTrainerId.value = pairedId
        wantReconnect.value = true
    }

    /**
     * Lifecycle gate: stop scanning while the app is backgrounded with no active
     * ride, to spare the radio. Keeps the standing reconnect intent and does NOT
     * drop an established connection — only scanning pauses. Resume with
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
            if (awaitBluetoothAvailable { reason -> Log.i(TAG, "Waiting for Bluetooth ($reason)") }) {
                backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
                Log.i(TAG, "Bluetooth available; resuming trainer scans")
            }

            resetDiscoveryCaches()
            _connectionState.value = TrainerConnectionState.Scanning
            val found = withTimeoutOrNull(BleReconnectPolicy.SCAN_WINDOW_MS) {
                try {
                    Scanner {
                        filters = listOf(
                            Filter.Service(uuidFrom(CYCLING_POWER_SERVICE)),
                            Filter.Service(uuidFrom(FITNESS_MACHINE_SERVICE))
                        )
                        logging(scannerLogging())
                    }.advertisements
                        .onEach { addDiscovered(it) }
                        .firstOrNull { it.identifier == id }
                } catch (e: CancellationException) {
                    // Routine: either the scan window timed out or the lifecycle
                    // gate closed under us. Reporting those as "scan failed" put
                    // a red herring at the top of every bug report drafted after
                    // a launch. Rethrow so `withTimeoutOrNull` sees its timeout.
                    throw e
                } catch (e: BluetoothDisabledException) {
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
                // Returns on a failed attempt or a later drop, then we rescan.
                _connectionState.first {
                    it == TrainerConnectionState.Disconnected || it == TrainerConnectionState.Error
                }
                backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
                delay(BleReconnectPolicy.POST_DROP_DELAY_MS)
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

    fun startScan() {
        // A manual pairing scan takes over from any background auto-reconnect so
        // the two don't fight over the radio; a successful pick re-arms it.
        wantReconnect.value = false

        if (_connectionState.value == TrainerConnectionState.Connected) return

        _connectionState.value = TrainerConnectionState.Scanning
        // Clears the advertisement map alongside the list. It used to clear
        // only the list, so every manual scan added to a map that nothing ever
        // emptied.
        resetDiscoveryCaches()

        scanJob?.cancel()
        scanJob = Scanner {
            filters = listOf(
                Filter.Service(uuidFrom(CYCLING_POWER_SERVICE)),
                Filter.Service(uuidFrom(FITNESS_MACHINE_SERVICE))
            )
            logging(scannerLogging())
        }.advertisements
            .onEach { advertisement -> addDiscovered(advertisement) }
            .catch { e ->
                Log.e(TAG, "Scan failed", e)
                _connectionState.value = TrainerConnectionState.Error
            }
            .launchIn(scope)
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value == TrainerConnectionState.Scanning) {
            _connectionState.value = TrainerConnectionState.Disconnected
        }
    }

    fun connectToCandidate(candidateId: String) {
        // Look up the underlying advertisement; we keep it around because Kable
        // requires the Advertisement (or the specific platform handle) to peripheral().
        val ad = lastAdvertisements[candidateId] ?: run {
            Log.w(TAG, "connectToCandidate: unknown id=${redactDeviceId(candidateId)}")
            _connectionState.value = TrainerConnectionState.Error
            return
        }
        connectInternal(ad)
    }

    fun disconnect() {
        // Explicit user action: stop auto-reconnecting until they reconnect/pair.
        wantReconnect.value = false
        scope.launch {
            try {
                activePeripheral?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Peripheral disconnect failed", e)
            }
        }
        if (_isMockTrainer.value) {
            _isMockTrainer.value = false
            _connectionState.value = TrainerConnectionState.Disconnected
            _connectedTrainerName.value = null
            stopMockTelemetryStream()
        }
    }

    fun forgetTrainer() {
        disconnect()
        targetPairedId = null
        _pairedTrainerId.value = null
        _isMockTrainer.value = false
        _connectedTrainerName.value = null
        _connectionState.value = TrainerConnectionState.Disconnected
    }

    /** Cancel all in-flight jobs and BLE connections. Call once at app shutdown. */
    fun dispose() {
        scope.cancel()
    }

    // Internals ──────────────────────────────────────────────────────────────

    private val lastAdvertisements = mutableMapOf<String, Advertisement>()

    // Declared after all state the supervisor touches, so they're initialised
    // before its coroutine body runs.
    init { scope.launch { reconnectSupervisor() } }

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

    private fun addDiscovered(ad: Advertisement) {
        lastAdvertisements[ad.identifier] = ad
        val current = _discoveredDevices.value
        if (current.none { it.id == ad.identifier }) {
            _discoveredDevices.value = current + TrainerCandidate(
                id = ad.identifier,
                name = ad.name,
                rssi = ad.rssi
            )
        }
    }

    private fun connectInternal(advertisement: Advertisement) {
        stopScan()
        _connectionState.value = TrainerConnectionState.Connecting
        _pairedTrainerId.value = advertisement.identifier
        _connectedTrainerName.value = advertisement.name ?: "Unknown Trainer"

        // Connecting to a trainer (manual or auto) means we want to stay on it:
        // arm persistent reconnect so a later drop is chased automatically.
        targetPairedId = advertisement.identifier
        wantReconnect.value = true

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

        // Cancel any prior connection state listener and telemetry subscriptions.
        stateListenerJob?.cancel()
        telemetryJobs.forEach { it.cancel() }
        telemetryJobs = emptyList()
        connectionJob?.cancel()

        connectionJob = scope.launch {
            try {
                val peripheral = scope.peripheral(advertisement) {
                    logging(peripheralLogging(advertisement.identifier))
                    // With a handler registered, Kable sends observation
                    // failures here rather than through the observe flow. Log
                    // with device context, then rethrow to keep the existing
                    // behaviour of terminating the flow.
                    observationExceptionHandler { cause ->
                        Log.w(
                            TAG,
                            "Trainer observation failed on ${redactDeviceId(advertisement.identifier)}",
                            cause,
                        )
                        throw cause
                    }
                }
                activePeripheral = peripheral

                stateListenerJob = peripheral.state.onEach { state ->
                    when (state) {
                        is State.Connecting -> _connectionState.value = TrainerConnectionState.Connecting
                        is State.Connected -> {
                            _connectionState.value = TrainerConnectionState.Connected
                            _ergError.value = null
                            _justConnected.tryEmit(_connectedTrainerName.value ?: "Trainer")
                            startTelemetrySubscriptions(peripheral)
                        }
                        is State.Disconnecting -> _connectionState.value = TrainerConnectionState.Disconnecting
                        is State.Disconnected -> {
                            _connectionState.value = TrainerConnectionState.Disconnected
                            telemetryJobs.forEach { it.cancel() }
                            telemetryJobs = emptyList()
                            ftmsControlPointWriter = null
                            ftmsControlGranted = false
                            activePeripheral = null
                            _connectedTrainerName.value = null
                            _ergError.value = null
                        }
                    }
                }.launchIn(scope)

                peripheral.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                _connectionState.value = TrainerConnectionState.Error
                activePeripheral = null
            }
        }
    }

    /**
     * Subscribe to whichever telemetry characteristics the peripheral exposes.
     * Power/cadence prefer CPS (Cycling Power Measurement); FTMS Indoor Bike Data
     * is used as a fallback. Heart Rate is a separate service if available.
     *
     * Suspending + sequential, by design. The previous implementation launched
     * each `findCharacteristic + observe` in its own `scope.launch { ... }` and
     * assigned `telemetryJobs` immediately afterwards — but the assignment
     * fired before the launches had populated the list, so concurrent
     * cancellations (e.g. a fast trainer swap) saw an empty `telemetryJobs`
     * and leaked the observe jobs from the previous connection. Going
     * sequential makes the bookkeeping trivially correct: each job is added
     * to `telemetryJobs` the moment it's launched, and there's no window
     * where a cancellation can miss it.
     */
    private suspend fun startTelemetrySubscriptions(peripheral: Peripheral) {
        // Cancel any existing subscriptions and reset the tracker. Bind the
        // mutable list to `telemetryJobs` up front so an interleaved
        // disconnection callback's `telemetryJobs.forEach { it.cancel() }`
        // sees additions as we go, not just the post-completion snapshot.
        telemetryJobs.forEach { it.cancel() }
        val jobs = mutableListOf<Job>()
        telemetryJobs = jobs

        suspend fun trySubscribe(
            label: String,
            service: String,
            characteristic: String,
            onBytes: (ByteArray) -> Unit,
        ) {
            try {
                val char = peripheral.findCharacteristic(service, characteristic) ?: return
                jobs += peripheral.observe(char)
                    .onEach { onBytes(it) }
                    .catch { Log.w(TAG, "$label observe failed", it) }
                    .launchIn(scope)
            } catch (e: Exception) {
                Log.w(TAG, "$label subscribe failed", e)
            }
        }

        trySubscribe("CPS", CYCLING_POWER_SERVICE, CYCLING_POWER_MEASUREMENT) { bytes ->
            parseCyclingPowerMeasurement(bytes)?.let { reading ->
                _power.value = reading.powerWatts
                reading.cadenceRpm?.let { _cadence.value = it }
                touchTelemetry()
            }
        }
        trySubscribe("Indoor Bike Data", FITNESS_MACHINE_SERVICE, INDOOR_BIKE_DATA) { bytes ->
            parseIndoorBikeData(bytes)?.let { reading ->
                if (_power.value == 0) reading.powerWatts?.let { _power.value = it }
                if (_cadence.value == 0) reading.cadenceRpm?.let { _cadence.value = it }
                touchTelemetry()
            }
        }
        trySubscribe("HR", HEART_RATE_SERVICE, HEART_RATE_MEASUREMENT) { bytes ->
            parseHeartRateMeasurement(bytes)?.let { hr ->
                _heartRate.value = hr
                touchTelemetry()
            }
        }

        // FTMS Control Point — capture a writer closure so setTargetPower works,
        // and observe it for the machine's Response Code indications. The writer
        // reference is invalidated on disconnect via `ftmsControlPointWriter = null`;
        // the observe job is tracked in `jobs` so it's cancelled on disconnect.
        try {
            val char = peripheral.findCharacteristic(FITNESS_MACHINE_SERVICE, FTMS_CONTROL_POINT)
            if (char != null) {
                // Fresh connection: control hasn't been granted yet.
                ftmsControlGranted = false
                ftmsControlRetries = 0
                ftmsControlPointWriter = { payload ->
                    peripheral.write(char, payload, WriteType.WithResponse)
                }
                // The FTMS Control Point is Write + Indicate: per spec the machine
                // answers every command with a Response Code (0x80) indication, and
                // strict trainers (e.g. Zwift Hub) refuse to honor any control write —
                // including Request Control and Set Target Power — until the client has
                // enabled indications on the CCCD. Observing the characteristic enables
                // them and lets us report rejected commands back to the rider.
                jobs += peripheral.observe(char)
                    .onEach { bytes -> handleControlPointResponse(bytes) }
                    .catch { Log.w(TAG, "FTMS control point observe failed", it) }
                    .launchIn(scope)
                // Best-effort early Request Control (0x00). If it lands and the
                // trainer grants control, the success indication sets
                // `ftmsControlGranted` so later target writes skip the re-request.
                // If it doesn't take (a strict trainer may ignore it when sent
                // before indications are fully enabled), it's not fatal:
                // setTargetPower pairs a fresh Request Control with the target
                // write and recovers from any "Control Not Permitted" reply.
                runCatching { peripheral.write(char, byteArrayOf(0x00), WriteType.WithResponse) }
                    .onFailure { Log.w(TAG, "FTMS early request-control failed (will retry on target write)", it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTMS control point setup failed", e)
        }
    }

    /**
     * Parse an FTMS Control Point indication. The machine answers every command
     * with `[0x80, requestOpCode, resultCode, ...]`.
     *
     *  - Request Control (0x00) success → control is granted; remember it so we
     *    stop prepending Request Control to every target write.
     *  - Set Target Power (0x05) success → clear any error.
     *  - "Control Not Permitted" (0x05) → the grant didn't take (or was lost).
     *    Re-request control and re-apply the target rather than giving up;
     *    surface a helpful error only once recovery is exhausted.
     *  - Other result codes → surface to the rider.
     */
    internal fun handleControlPointResponse(bytes: ByteArray) {
        val hex = bytes.joinToString(" ") { b -> (b.toInt() and 0xFF).toString(16) }
        Log.d(TAG, "FTMS control point response: $hex")
        if (bytes.size < 3 || (bytes[0].toInt() and 0xFF) != 0x80) return
        val requestOpCode = bytes[1].toInt() and 0xFF
        val resultCode = bytes[2].toInt() and 0xFF

        if (resultCode == 0x01) { // Success
            when (requestOpCode) {
                0x00 -> { ftmsControlGranted = true; ftmsControlRetries = 0 }
                0x05 -> { ftmsControlRetries = 0; _ergError.value = null }
            }
            return
        }

        // Control Not Permitted: the trainer doesn't think we hold control.
        if (resultCode == 0x05) {
            ftmsControlGranted = false
            if (ftmsControlRetries < MAX_CONTROL_RETRIES) {
                ftmsControlRetries++
                Log.w(TAG, "FTMS control not permitted; re-requesting control (attempt $ftmsControlRetries)")
                // writeTargetPower re-sends Request Control (granted is false) then
                // the target, serialised on the GATT queue.
                writeTargetPower(_targetPower.value)
            } else {
                _ergError.value = "Trainer rejected ERG control. Close any other app " +
                    "connected to the trainer (e.g. Zwift), then re-pair it in Settings."
            }
            return
        }

        val reason = when (resultCode) {
            0x02 -> "the command isn't supported"
            0x03 -> "the target power was out of range"
            else -> "the trainer reported an error"
        }
        _ergError.value = "Trainer rejected the ERG command: $reason."
    }

    private fun touchTelemetry() {
        // monotonic stamp via System.currentTimeMillis equivalent — KMM uses commonMain util
        _lastTelemetryEpochMs.value = nowMs()
    }

    private fun startMockTelemetryStream() {
        mockJob?.cancel()
        mockJob = scope.launch {
            while (true) {
                if (_isMockTrainer.value && isMockPedaling) {
                    val target = _targetPower.value
                    if (target > 0) {
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
        _power.value = 0
        _cadence.value = 0
        _heartRate.value = 0
    }

    // BLE byte parsing ───────────────────────────────────────────────────────

    private data class CpsReading(val powerWatts: Int, val cadenceRpm: Int?)

    private fun parseCyclingPowerMeasurement(bytes: ByteArray): CpsReading? {
        if (bytes.size < 4) return null
        // Bytes 0-1: flags (LE). Bytes 2-3: instantaneous power (sint16, LE).
        val flags = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        val watts = (bytes[2].toInt() and 0xFF) or (bytes[3].toInt() shl 8)
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
                    if (lastCrankActivityEpochMs > 0 && now - lastCrankActivityEpochMs > CADENCE_STALE_MS) {
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
        return CpsReading(watts, cadence)
    }

    private data class IbdReading(val powerWatts: Int?, val cadenceRpm: Int?)

    private fun parseIndoorBikeData(bytes: ByteArray): IbdReading? {
        // FTMS Indoor Bike Data flags (u16 LE) drive what's present after.
        if (bytes.size < 2) return null
        val flags = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        var i = 2
        val moreData = (flags and 0x0001) != 0
        if (!moreData && bytes.size >= i + 2) {
            // Instantaneous Speed (u16, LE, 0.01 km/h) — present but skipped.
            i += 2
        }
        val avgSpeedPresent = (flags and 0x0002) != 0
        if (avgSpeedPresent) i += 2
        val instCadencePresent = (flags and 0x0004) != 0
        var cadence: Int? = null
        if (instCadencePresent && bytes.size >= i + 2) {
            val raw = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            cadence = (raw / 2) // unit is 0.5 / minute
            i += 2
        }
        val avgCadencePresent = (flags and 0x0008) != 0
        if (avgCadencePresent) i += 2
        val totalDistancePresent = (flags and 0x0010) != 0
        if (totalDistancePresent) i += 3
        val resistanceLevelPresent = (flags and 0x0020) != 0
        if (resistanceLevelPresent) i += 2
        val instPowerPresent = (flags and 0x0040) != 0
        var power: Int? = null
        if (instPowerPresent && bytes.size >= i + 2) {
            // sint16 little-endian
            val low = bytes[i].toInt() and 0xFF
            val high = bytes[i + 1].toInt()
            power = ((high shl 8) or low).toShort().toInt()
            i += 2
        }
        return IbdReading(power, cadence)
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

        // Service UUIDs (16-bit assigned numbers, expanded to 128-bit base).
        const val CYCLING_POWER_SERVICE = "00001818-0000-1000-8000-00805f9b34fb"
        const val CYCLING_POWER_MEASUREMENT = "00002a63-0000-1000-8000-00805f9b34fb"
        const val FITNESS_MACHINE_SERVICE = "00001826-0000-1000-8000-00805f9b34fb"
        const val INDOOR_BIKE_DATA = "00002ad2-0000-1000-8000-00805f9b34fb"
        const val FTMS_CONTROL_POINT = "00002ad9-0000-1000-8000-00805f9b34fb"
        const val HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
        const val HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"
    }
}

// nowMs() is provided by util.TimeUtils — re-exported for readability inside this file.
private fun nowMs(): Long = app.indoorbike.shared.util.getCurrentEpochMillis()
