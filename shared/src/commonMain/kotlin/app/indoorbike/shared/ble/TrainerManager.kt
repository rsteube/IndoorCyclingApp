package app.indoorbike.shared.ble

import app.indoorbike.shared.util.Log
import com.benasher44.uuid.uuidFrom
import com.juul.kable.Advertisement
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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

    private var activePeripheral: Peripheral? = null
    private var ftmsControlPointWriter: (suspend (ByteArray) -> Unit)? = null

    private var isMockPedaling = false
    // Cadence math from CPS uses crank revolution timestamps to derive RPM.
    private var lastCrankRevs: Int? = null
    private var lastCrankEventTime: Int? = null

    // Public API ─────────────────────────────────────────────────────────────

    fun setTargetPower(watts: Int) {
        val clamped = watts.coerceAtLeast(0)
        _targetPower.value = clamped
        ftmsControlPointWriter?.let { writer ->
            scope.launch {
                try {
                    // FTMS Control Point: 0x05 = Set Target Power, int16 LE watts
                    val payload = byteArrayOf(
                        0x05,
                        (clamped and 0xFF).toByte(),
                        ((clamped shr 8) and 0xFF).toByte()
                    )
                    writer(payload)
                } catch (e: Exception) {
                    Log.w(TAG, "FTMS Set Target Power write failed: $clamped W", e)
                }
            }
        }
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
        scanJob?.cancel()
        _isMockTrainer.value = true
        _connectedTrainerName.value = "Mock Trainer"
        _pairedTrainerId.value = "mock"
        _connectionState.value = TrainerConnectionState.Connected
        activePeripheral = null
        startMockTelemetryStream()
    }

    fun startScan() {
        if (_connectionState.value == TrainerConnectionState.Scanning ||
            _connectionState.value == TrainerConnectionState.Connected
        ) return

        _connectionState.value = TrainerConnectionState.Scanning
        _discoveredDevices.value = emptyList()

        scanJob?.cancel()
        scanJob = Scanner {
            filters = listOf(
                Filter.Service(uuidFrom(CYCLING_POWER_SERVICE)),
                Filter.Service(uuidFrom(FITNESS_MACHINE_SERVICE))
            )
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
            Log.w(TAG, "connectToCandidate: unknown id=$candidateId")
            _connectionState.value = TrainerConnectionState.Error
            return
        }
        connectInternal(ad)
    }

    fun disconnect() {
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

        // Cancel any prior connection state listener and telemetry subscriptions.
        stateListenerJob?.cancel()
        telemetryJobs.forEach { it.cancel() }
        telemetryJobs = emptyList()
        connectionJob?.cancel()

        connectionJob = scope.launch {
            try {
                val peripheral = scope.peripheral(advertisement)
                activePeripheral = peripheral

                stateListenerJob = peripheral.state.onEach { state ->
                    when (state) {
                        is State.Connecting -> _connectionState.value = TrainerConnectionState.Connecting
                        is State.Connected -> {
                            _connectionState.value = TrainerConnectionState.Connected
                            startTelemetrySubscriptions(peripheral)
                        }
                        is State.Disconnecting -> _connectionState.value = TrainerConnectionState.Disconnecting
                        is State.Disconnected -> {
                            _connectionState.value = TrainerConnectionState.Disconnected
                            telemetryJobs.forEach { it.cancel() }
                            telemetryJobs = emptyList()
                            ftmsControlPointWriter = null
                            activePeripheral = null
                            _connectedTrainerName.value = null
                        }
                    }
                }.launchIn(scope)

                peripheral.connect()
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
     */
    private fun startTelemetrySubscriptions(peripheral: Peripheral) {
        telemetryJobs.forEach { it.cancel() }
        val jobs = mutableListOf<Job>()

        // Cycling Power Measurement
        scope.launch {
            try {
                val char = peripheral.findCharacteristic(CYCLING_POWER_SERVICE, CYCLING_POWER_MEASUREMENT)
                if (char != null) {
                    val job = peripheral.observe(char)
                        .onEach { bytes ->
                            parseCyclingPowerMeasurement(bytes)?.let { reading ->
                                _power.value = reading.powerWatts
                                reading.cadenceRpm?.let { _cadence.value = it }
                                touchTelemetry()
                            }
                        }
                        .catch { Log.w(TAG, "CPS observe failed", it) }
                        .launchIn(scope)
                    jobs += job
                }
            } catch (e: Exception) {
                Log.w(TAG, "CPS subscribe failed", e)
            }
        }

        // Indoor Bike Data (fallback for power/cadence; primary on some FTMS-only trainers)
        scope.launch {
            try {
                val char = peripheral.findCharacteristic(FITNESS_MACHINE_SERVICE, INDOOR_BIKE_DATA)
                if (char != null) {
                    val job = peripheral.observe(char)
                        .onEach { bytes ->
                            parseIndoorBikeData(bytes)?.let { reading ->
                                if (_power.value == 0) reading.powerWatts?.let { _power.value = it }
                                if (_cadence.value == 0) reading.cadenceRpm?.let { _cadence.value = it }
                                touchTelemetry()
                            }
                        }
                        .catch { Log.w(TAG, "Indoor Bike Data observe failed", it) }
                        .launchIn(scope)
                    jobs += job
                }
            } catch (e: Exception) {
                Log.w(TAG, "Indoor Bike Data subscribe failed", e)
            }
        }

        // Heart Rate (separate optional service)
        scope.launch {
            try {
                val char = peripheral.findCharacteristic(HEART_RATE_SERVICE, HEART_RATE_MEASUREMENT)
                if (char != null) {
                    val job = peripheral.observe(char)
                        .onEach { bytes ->
                            parseHeartRateMeasurement(bytes)?.let { hr ->
                                _heartRate.value = hr
                                touchTelemetry()
                            }
                        }
                        .catch { Log.w(TAG, "HR observe failed", it) }
                        .launchIn(scope)
                    jobs += job
                }
            } catch (e: Exception) {
                Log.w(TAG, "HR subscribe failed", e)
            }
        }

        // FTMS Control Point — capture a writer closure so setTargetPower works.
        scope.launch {
            try {
                val char = peripheral.findCharacteristic(FITNESS_MACHINE_SERVICE, FTMS_CONTROL_POINT)
                if (char != null) {
                    ftmsControlPointWriter = { payload ->
                        peripheral.write(char, payload, WriteType.WithResponse)
                    }
                    // Request control: opcode 0x00.
                    runCatching { peripheral.write(char, byteArrayOf(0x00), WriteType.WithResponse) }
                        .onFailure { Log.w(TAG, "FTMS request control failed", it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "FTMS control point setup failed", e)
            }
        }

        telemetryJobs = jobs
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
                } else if (dRevs == 0) {
                    cadence = 0
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

/**
 * Helper: walks `peripheral.services` (after connect) and returns the matching
 * characteristic, or null if absent.
 */
private suspend fun Peripheral.findCharacteristic(
    serviceUuidString: String,
    characteristicUuidString: String,
): com.juul.kable.Characteristic? {
    val sUuid = uuidFrom(serviceUuidString)
    val cUuid = uuidFrom(characteristicUuidString)
    val service = services?.firstOrNull { it.serviceUuid == sUuid } ?: return null
    return service.characteristics.firstOrNull { it.characteristicUuid == cUuid }
}
