package app.indoorbike.shared.ble

import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.getCurrentEpochMillis
import com.benasher44.uuid.uuidFrom
import com.juul.kable.Advertisement
import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

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
 * Filters scan results to devices advertising the standard BLE Heart Rate
 * Service (0x180D), so any modern HRM (Polar H10/H9, Wahoo Tickr, Garmin
 * HRM-Dual/Pro, Coros, Suunto, generic chest straps) shows up.
 *
 * ANT+-only HRMs are NOT supported — Android lacks a universal ANT+ stack
 * and the available SDKs depend on Google Play services not allowed in F-Droid.
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

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var stateListenerJob: Job? = null
    private var observeJob: Job? = null
    private var activePeripheral: Peripheral? = null
    private val advertisementById = mutableMapOf<String, Advertisement>()

    fun startScan() {
        if (_connectionState.value == HrmConnectionState.Scanning ||
            _connectionState.value == HrmConnectionState.Connected
        ) return

        _connectionState.value = HrmConnectionState.Scanning
        _discoveredDevices.value = emptyList()
        advertisementById.clear()

        scanJob?.cancel()
        scanJob = Scanner {
            filters = listOf(Filter.Service(uuidFrom(HEART_RATE_SERVICE_UUID)))
        }.advertisements
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
            Log.w(TAG, "connectToCandidate: unknown HRM id=$candidateId")
            _connectionState.value = HrmConnectionState.Error
            return
        }
        connectInternal(ad)
    }

    fun disconnect() {
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
        _pairedDeviceId.value = null
        _connectedDeviceName.value = null
        _connectionState.value = HrmConnectionState.Disconnected
        _heartRate.value = 0
    }

    fun dispose() {
        scope.cancel()
    }

    // ─── internals ───────────────────────────────────────────────────────

    private fun addDiscovered(ad: Advertisement) {
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

    private fun connectInternal(advertisement: Advertisement) {
        stopScan()
        _connectionState.value = HrmConnectionState.Connecting
        _pairedDeviceId.value = advertisement.identifier
        _connectedDeviceName.value = advertisement.name ?: "Unknown HRM"

        stateListenerJob?.cancel()
        observeJob?.cancel()
        connectionJob?.cancel()

        connectionJob = scope.launch {
            try {
                val peripheral = scope.peripheral(advertisement)
                activePeripheral = peripheral

                stateListenerJob = peripheral.state.onEach { state ->
                    when (state) {
                        is State.Connecting -> _connectionState.value = HrmConnectionState.Connecting
                        is State.Connected -> {
                            _connectionState.value = HrmConnectionState.Connected
                            startHeartRateObserve(peripheral)
                        }
                        is State.Disconnecting -> _connectionState.value = HrmConnectionState.Disconnecting
                        is State.Disconnected -> {
                            _connectionState.value = HrmConnectionState.Disconnected
                            observeJob?.cancel()
                            observeJob = null
                            _heartRate.value = 0
                            activePeripheral = null
                        }
                    }
                }.launchIn(scope)

                peripheral.connect()
            } catch (e: Exception) {
                Log.e(TAG, "HRM connect failed", e)
                _connectionState.value = HrmConnectionState.Error
                activePeripheral = null
            }
        }
    }

    private fun startHeartRateObserve(peripheral: Peripheral) {
        observeJob?.cancel()
        scope.launch {
            try {
                val sUuid = uuidFrom(HEART_RATE_SERVICE_UUID)
                val cUuid = uuidFrom(HEART_RATE_MEASUREMENT_UUID)
                val service = peripheral.services?.firstOrNull { it.serviceUuid == sUuid }
                val char = service?.characteristics?.firstOrNull { it.characteristicUuid == cUuid }
                if (char == null) {
                    Log.w(TAG, "Heart Rate Measurement characteristic not found")
                    return@launch
                }
                observeJob = peripheral.observe(char)
                    .onEach { bytes ->
                        parseHeartRateMeasurement(bytes)?.let { bpm ->
                            _heartRate.value = bpm
                            _lastSampleEpochMs.value = getCurrentEpochMillis()
                        }
                    }
                    .catch { Log.w(TAG, "HRM observe failed", it) }
                    .launchIn(scope)
            } catch (e: Exception) {
                Log.w(TAG, "HRM subscribe failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "HeartRateMonitor"
    }
}
