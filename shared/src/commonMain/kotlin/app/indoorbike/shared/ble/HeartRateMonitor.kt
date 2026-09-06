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
import app.indoorbike.shared.util.getCurrentEpochMillis
import app.indoorbike.shared.util.redactDeviceId
import com.benasher44.uuid.uuidFrom
import com.juul.kable.Advertisement
import com.juul.kable.BluetoothDisabledException
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
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

/** Reuse the trainer's connection-state shape — same lifecycle. */
typealias HrmConnectionState = TrainerConnectionState
typealias HrmCandidate = TrainerCandidate

/**
 * Optional external Heart Rate Monitor connection. Lives next to [TrainerManager]
 * but uses an independent BLE peripheral so users can ride with:
 *  - just a trainer (no HR),
 *  - just an HRM (no trainer — useful for outdoor rides imported later),
 *  - both (HRM data takes precedence over any HR the trainer might surface).
 *
 * Scans without a service-UUID filter and post-filters by either (a) the
 * Heart Rate Service (0x180D) being present in the advertisement payload,
 * or (b) the device name matching a known HRM keyword. The unfiltered scan
 * matters because Garmin HRM-Pro / HRM-Pro Plus omit 0x180D from their
 * advertisement to save bytes — the service is only visible after GATT
 * connect — so a service-UUID filter would never match them.
 *
 * ANT+-only HRMs are NOT supported — Android lacks a universal ANT+ stack
 * and the available SDKs are built on Google Play services, which the app
 * rules out so it can run on a device that has none.
 */
class HeartRateMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow(HrmConnectionState.Disconnected)
    val connectionState: StateFlow<HrmConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<HrmCandidate>>(emptyList())
    val discoveredDevices: StateFlow<List<HrmCandidate>> = _discoveredDevices.asStateFlow()

    private val _pairedDeviceId = MutableStateFlow<String?>(null)
    val pairedDeviceId: StateFlow<String?> = _pairedDeviceId.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    /** Epoch ms of last received sample, for stale-data detection in the UI. */
    private val _lastSampleEpochMs = MutableStateFlow(0L)
    val lastSampleEpochMs: StateFlow<Long> = _lastSampleEpochMs.asStateFlow()

    /**
     * True once the strap has connected and stayed silent through
     * [MAX_SILENT_CONNECTS] attempts. This is a phone-side fault, not a strap
     * one: the link is up, the app is subscribed, and no notification ever
     * arrives. The UI uses it to stop saying "waiting for first sample" - a
     * message that implies patience will help - and tell the rider to cycle
     * Bluetooth instead. See [armFirstSampleWatchdog].
     */
    private val _sensorSilent = MutableStateFlow(false)
    val sensorSilent: StateFlow<Boolean> = _sensorSilent.asStateFlow()

    /** Emits the device name on each (re)connect; drives the UI "connected" toast. */
    private val _justConnected = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val justConnected: SharedFlow<String> = _justConnected.asSharedFlow()

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var stateListenerJob: Job? = null
    private var observeJob: Job? = null
    private var watchdogJob: Job? = null
    private var activePeripheral: Peripheral? = null
    private val advertisementById = mutableMapOf<String, Advertisement>()

    /** Consecutive connections that produced no sample. Reset by the first one. */
    private var silentConnects = 0

    // Persistent auto-reconnect supervisor (see TrainerManager for the model).
    private val wantReconnect = MutableStateFlow(false)
    private val scanningAllowed = MutableStateFlow(true)
    private var targetPairedId: String? = null

    init { scope.launch { reconnectSupervisor() } }

    /**
     * Arm persistent auto-reconnect to a previously paired HRM. Mirrors
     * [TrainerManager.autoConnectIfPaired]: records the standing intent and keeps
     * retrying (windowed scans + backoff) until the strap is found, instead of
     * giving up after one scan. Scanning is gated to foreground/active-ride via
     * [pauseAutoReconnect] / [resumeAutoReconnect].
     */
    fun autoConnectIfPaired(pairedId: String?) {
        if (pairedId.isNullOrBlank()) return
        targetPairedId = pairedId
        _pairedDeviceId.value = pairedId
        wantReconnect.value = true
    }

    /** Lifecycle gate: stop scanning (keeps intent + any live connection). */
    fun pauseAutoReconnect() { scanningAllowed.value = false }

    /** Lifecycle gate: allow scanning again. */
    fun resumeAutoReconnect() { scanningAllowed.value = true }

    private fun gateOpen() = combine(wantReconnect, scanningAllowed) { want, allowed -> want && allowed }

    private suspend fun reconnectSupervisor() {
        while (true) {
            gateOpen().first { it }
            coroutineScope {
                val worker = launch { reconnectWorker() }
                gateOpen().first { !it }
                worker.cancelAndJoin()
            }
            if (_connectionState.value == HrmConnectionState.Scanning) {
                _connectionState.value = HrmConnectionState.Disconnected
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

            val state = _connectionState.value
            if (state == HrmConnectionState.Connected || state == HrmConnectionState.Connecting) {
                _connectionState.first {
                    it == HrmConnectionState.Disconnected || it == HrmConnectionState.Error
                }
                backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
                continue
            }

            // See TrainerManager: wait out a disabled radio instead of scanning
            // into it, and do not let that wait push the retry cadence back.
            if (awaitBluetoothAvailable { reason -> Log.i(TAG, "Waiting for Bluetooth ($reason)") }) {
                backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
                Log.i(TAG, "Bluetooth available; resuming HRM scans")
            }

            resetDiscoveryCaches()
            _connectionState.value = HrmConnectionState.Scanning
            val found = withTimeoutOrNull(BleReconnectPolicy.SCAN_WINDOW_MS) {
                try {
                    Scanner { logging(scannerLogging()) }.advertisements
                        .onEach { addDiscovered(it) }
                        .firstOrNull { it.identifier == id }
                } catch (e: CancellationException) {
                    // The scan window timing out, or the lifecycle gate closing,
                    // is routine - not a failure worth a line in a bug report.
                    // Let it through so `withTimeoutOrNull` sees its own timeout.
                    throw e
                } catch (e: BluetoothDisabledException) {
                    // See TrainerManager: expected, and the stack adds nothing.
                    Log.i(TAG, "HRM scan stopped: Bluetooth turned off")
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "HRM auto-reconnect scan failed", e)
                    null
                }
            }

            if (found != null) {
                connectInternal(found)
                _connectionState.first {
                    it == HrmConnectionState.Disconnected || it == HrmConnectionState.Error
                }
                backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
                delay(BleReconnectPolicy.POST_DROP_DELAY_MS)
            } else {
                if (_connectionState.value == HrmConnectionState.Scanning) {
                    _connectionState.value = HrmConnectionState.Disconnected
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(BleReconnectPolicy.MAX_BACKOFF_MS)
            }
        }
    }

    fun startScan() {
        // Manual pairing takes over from background auto-reconnect.
        wantReconnect.value = false

        // A rider who taps "pair" is retrying by hand, so give the next connect
        // a clean slate rather than judging it by earlier silent ones.
        silentConnects = 0
        _sensorSilent.value = false

        if (_connectionState.value == HrmConnectionState.Connected) return

        _connectionState.value = HrmConnectionState.Scanning
        resetDiscoveryCaches()

        scanJob?.cancel()
        scanJob = Scanner { logging(scannerLogging()) }.advertisements
            .onEach { ad -> addDiscovered(ad) }
            .catch { e ->
                Log.e(TAG, "HRM scan failed", e)
                _connectionState.value = HrmConnectionState.Error
            }
            .launchIn(scope)
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value == HrmConnectionState.Scanning) {
            _connectionState.value = HrmConnectionState.Disconnected
        }
    }

    fun connectToCandidate(candidateId: String) {
        val ad = advertisementById[candidateId] ?: run {
            Log.w(TAG, "connectToCandidate: unknown HRM id=${redactDeviceId(candidateId)}")
            _connectionState.value = HrmConnectionState.Error
            return
        }
        connectInternal(ad)
    }

    fun disconnect() {
        wantReconnect.value = false
        scope.launch {
            try {
                activePeripheral?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "HRM disconnect failed", e)
            }
        }
    }

    fun forgetDevice() {
        disconnect()
        targetPairedId = null
        _pairedDeviceId.value = null
        _connectedDeviceName.value = null
        _connectionState.value = HrmConnectionState.Disconnected
        _heartRate.value = 0
        watchdogJob?.cancel()
        watchdogJob = null
        silentConnects = 0
        _sensorSilent.value = false
    }

    fun dispose() {
        scope.cancel()
    }

    // ─── internals ───────────────────────────────────────────────────────

    /**
     * Forget what the previous scan window saw.
     *
     * The auto-reconnect scan runs in windows for as long as the device is
     * missing, which on a ride with a paired-but-absent sensor means the whole
     * session. Every advertisement it saw was being retained for the life of
     * the process, and BLE privacy addresses rotate every few minutes, so the
     * same nearby phone or pair of earbuds arrives under a fresh key each time
     * and none of the old ones is ever reachable again. Left alone that grows
     * without bound for exactly as long as the rider keeps riding.
     *
     * Clearing both together preserves the invariant the pairing screen relies
     * on: everything listed in [_discoveredDevices] has an advertisement in
     * [advertisementById] for the tap to connect with. The window that follows
     * repopulates both within a second or two of live advertisements, which is
     * fresher than what it replaces.
     */
    private fun resetDiscoveryCaches() {
        advertisementById.clear()
        _discoveredDevices.value = emptyList()
    }

    private fun addDiscovered(ad: Advertisement) {
        if (!looksLikeHrm(ad)) return
        // Only what the list shows is worth keeping. This used to be stored
        // above the filter, on the grounds that auto-connect needed every
        // advertisement to match its target by identifier - but it doesn't:
        // the reconnect worker matches on the live scan flow and hands that
        // advertisement straight to connectInternal. A tap on a listed device
        // is the only thing that reads this map, so the unfiltered scan was
        // retaining every radio in range on behalf of no one.
        advertisementById[ad.identifier] = ad
        val current = _discoveredDevices.value
        if (current.none { it.id == ad.identifier }) {
            _discoveredDevices.value = current + HrmCandidate(
                id = ad.identifier,
                name = ad.name,
                rssi = ad.rssi
            )
        }
    }

    private fun looksLikeHrm(ad: Advertisement): Boolean {
        val hrServiceUuid = uuidFrom(HEART_RATE_SERVICE_UUID)
        if (ad.uuids.any { it == hrServiceUuid }) return true
        val name = ad.name ?: ad.peripheralName ?: return false
        return HRM_NAME_KEYWORDS.any { name.contains(it, ignoreCase = true) }
    }

    private fun connectInternal(advertisement: Advertisement) {
        stopScan()
        _connectionState.value = HrmConnectionState.Connecting
        _pairedDeviceId.value = advertisement.identifier
        _connectedDeviceName.value = advertisement.name ?: "Unknown HRM"

        // Stay on this strap: arm persistent reconnect for future drops.
        targetPairedId = advertisement.identifier
        wantReconnect.value = true

        stateListenerJob?.cancel()
        observeJob?.cancel()
        watchdogJob?.cancel()
        connectionJob?.cancel()

        connectionJob = scope.launch {
            try {
                val peripheral = scope.peripheral(advertisement) {
                    logging(peripheralLogging(advertisement.identifier))
                    // Kable normally routes observation failures through the
                    // observe flow; with a handler registered they come here
                    // instead. Log with device context, then rethrow so the
                    // flow still terminates and the watchdog rebuilds the link.
                    observationExceptionHandler { cause ->
                        Log.w(
                            TAG,
                            "HRM observation failed on ${redactDeviceId(advertisement.identifier)}",
                            cause,
                        )
                        throw cause
                    }
                }
                activePeripheral = peripheral

                stateListenerJob = peripheral.state.onEach { state ->
                    when (state) {
                        is State.Connecting -> _connectionState.value = HrmConnectionState.Connecting
                        is State.Connected -> {
                            _connectionState.value = HrmConnectionState.Connected
                            _justConnected.tryEmit(_connectedDeviceName.value ?: "Heart rate monitor")
                            startHeartRateObserve(peripheral)
                            armFirstSampleWatchdog(peripheral)
                        }
                        is State.Disconnecting -> _connectionState.value = HrmConnectionState.Disconnecting
                        is State.Disconnected -> {
                            _connectionState.value = HrmConnectionState.Disconnected
                            observeJob?.cancel()
                            observeJob = null
                            watchdogJob?.cancel()
                            watchdogJob = null
                            _heartRate.value = 0
                            activePeripheral = null
                        }
                    }
                }.launchIn(scope)

                peripheral.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "HRM connect failed", e)
                _connectionState.value = HrmConnectionState.Error
                activePeripheral = null
            }
        }
    }

    /**
     * Watch for the first sample after a connect, and cycle the link if it
     * never arrives.
     *
     * A strap that connects but never notifies is the failure this exists for.
     * It happens when the phone's Bluetooth stack refuses to enable
     * notifications: Kable suppresses that refusal (see [KableLogEngine]), so
     * the observation flow stays subscribed to a stream that will never emit,
     * and the app has a connection it believes is healthy. Nothing recovers on
     * its own, because nothing has failed as far as either side is concerned -
     * killing and reopening the app does not help either, since the wedged
     * state lives in the system Bluetooth process rather than in ours.
     *
     * So: give the strap [FIRST_SAMPLE_TIMEOUT_MS] to say something, and if it
     * doesn't, drop the link and let the reconnect supervisor build a fresh
     * peripheral. After [MAX_SILENT_CONNECTS] attempts stop cycling and raise
     * [sensorSilent] instead - by then the fault is clearly not going to clear
     * by itself, and a reconnect loop that runs for the rest of the ride costs
     * battery without ever fixing anything. Recovery from there needs the rider
     * to toggle Bluetooth, which tears the link down and re-arms all of this.
     */
    private fun armFirstSampleWatchdog(peripheral: Peripheral) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            val gotSample = awaitFirstSample(_lastSampleEpochMs, FIRST_SAMPLE_TIMEOUT_MS)
            if (!gotSample) silentConnects += 1

            when (firstSampleOutcome(gotSample, silentConnects, MAX_SILENT_CONNECTS)) {
                FirstSampleOutcome.Delivering -> {
                    if (silentConnects > 0) {
                        Log.i(TAG, "HRM delivering samples again after $silentConnects silent connect(s)")
                    }
                    silentConnects = 0
                    _sensorSilent.value = false
                }

                FirstSampleOutcome.Cycle -> {
                    Log.w(
                        TAG,
                        "No HR sample within $FIRST_SAMPLE_TIMEOUT_MS ms of connect " +
                            "(attempt $silentConnects of $MAX_SILENT_CONNECTS); cycling the link",
                    )
                    cycleLink(peripheral)
                }

                FirstSampleOutcome.GiveUp -> {
                    _sensorSilent.value = true
                    Log.e(
                        TAG,
                        "HRM connected but silent through $silentConnects attempts - the Bluetooth " +
                            "stack is not delivering notifications; leaving the link up",
                    )
                }
            }
        }
    }

    /**
     * Drop the link while keeping the reconnect intent, so the supervisor picks
     * the strap back up with a fresh peripheral. Deliberately launched on its
     * own coroutine rather than run inline in the watchdog: the disconnect it
     * asks for is what cancels the watchdog job, so doing it inline would
     * cancel the call halfway through.
     */
    private fun cycleLink(peripheral: Peripheral) {
        scope.launch {
            try {
                peripheral.disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "HRM watchdog disconnect failed", e)
            }
        }
    }

    /**
     * Suspending and sequential, for the same reason
     * [TrainerManager.startTelemetrySubscriptions] is: the previous version did
     * the lookup and subscribe inside a `scope.launch { ... }` and assigned
     * [observeJob] from within it, so a disconnect arriving in that window ran
     * `observeJob?.cancel()` against the *previous* connection's job (or null)
     * and leaked this one. Assigning the job on the same coroutine that starts
     * it removes the window entirely.
     */
    private suspend fun startHeartRateObserve(peripheral: Peripheral) {
        observeJob?.cancel()
        observeJob = null
        try {
            val sUuid = uuidFrom(HEART_RATE_SERVICE_UUID)
            val cUuid = uuidFrom(HEART_RATE_MEASUREMENT_UUID)
            val service = peripheral.services?.firstOrNull { it.serviceUuid == sUuid }
            val char = service?.characteristics?.firstOrNull { it.characteristicUuid == cUuid }
            if (char == null) {
                Log.w(TAG, "Heart Rate Measurement characteristic not found")
                return
            }
            observeJob = peripheral.observe(char)
                .onEach { bytes ->
                    parseHeartRateMeasurement(bytes)?.let { bpm ->
                        _heartRate.value = bpm
                        _lastSampleEpochMs.value = getCurrentEpochMillis()
                        // Data is the only proof that matters, so clear the
                        // fault here rather than only where the watchdog
                        // happens to look. Without this, a link that recovers
                        // after the watchdog gave up would keep showing the
                        // "sending nothing" warning while samples arrive.
                        _sensorSilent.value = false
                    }
                }
                .catch { Log.w(TAG, "HRM observe failed", it) }
                .launchIn(scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "HRM subscribe failed", e)
        }
    }

    companion object {
        private const val TAG = "HeartRateMonitor"

        /**
         * How long a freshly connected strap gets to produce its first sample.
         * HRMs notify at roughly 1 Hz from the moment the link is up, so this
         * is generous by an order of magnitude - deliberately, since a strap
         * being woken by skin contact can take a few seconds to get going and
         * a false trip costs a reconnect.
         */
        internal const val FIRST_SAMPLE_TIMEOUT_MS = 15_000L

        /** Silent connects tolerated before [sensorSilent] is raised. */
        internal const val MAX_SILENT_CONNECTS = 3

        // Substrings (case-insensitive) used to recognise HRMs that omit the
        // 0x180D service from their advertisement payload — most notably
        // Garmin HRM-Pro / HRM-Pro Plus, which advertise only their name.
        private val HRM_NAME_KEYWORDS = listOf(
            "HRM",      // Garmin HRM-Pro / HRM-Dual / HRM-Tri / HRM 200
            "Polar",    // Polar H10 / H9 / OH1 / Verity Sense
            "TICKR",    // Wahoo TICKR / TICKR X / TICKR FIT
            "Coros",
            "Suunto",
            "Magene",
            "Verity",
            "Heart",
        )
    }
}

// ─── Watchdog logic, lifted out of the class so it's unit-testable ──────────

/** What to do with a connection that has just been judged for its first sample. */
internal enum class FirstSampleOutcome {
    /** A sample arrived: the link is healthy, clear any fault state. */
    Delivering,

    /** Nothing arrived and retries remain: drop the link so it can be rebuilt. */
    Cycle,

    /**
     * Nothing arrived and the retries are spent. Keep the link and raise the
     * fault instead of reconnecting forever: three fresh peripherals in a row
     * producing nothing is a wedged Bluetooth stack, and no number of further
     * attempts will unwedge it. Cycling for the rest of a ride would only cost
     * battery, and the rider needs to be told to act rather than watched over.
     */
    GiveUp,
}

internal fun firstSampleOutcome(
    gotSample: Boolean,
    silentConnects: Int,
    maxSilentConnects: Int,
): FirstSampleOutcome = when {
    gotSample -> FirstSampleOutcome.Delivering
    silentConnects >= maxSilentConnects -> FirstSampleOutcome.GiveUp
    else -> FirstSampleOutcome.Cycle
}

/**
 * Waits up to [timeoutMs] for [lastSampleEpochMs] to move, returning whether it
 * did.
 *
 * Compares against a snapshot taken on entry rather than against zero, because
 * the flow carries the timestamp of the last sample from *any* connection. A
 * reconnect that follows a session which did deliver samples would otherwise
 * see a non-zero value and count as healthy immediately, which is precisely the
 * case where the silent-strap fault tends to appear.
 */
internal suspend fun awaitFirstSample(
    lastSampleEpochMs: StateFlow<Long>,
    timeoutMs: Long,
): Boolean {
    val before = lastSampleEpochMs.value
    return withTimeoutOrNull(timeoutMs) { lastSampleEpochMs.first { it != before } } != null
}
