package app.indoorbike.shared.ble

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.juul.kable.Filter
import com.benasher44.uuid.uuidFrom

enum class TrainerConnectionState {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Disconnecting,
    Error
}

object TrainerManager {
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _connectionState = MutableStateFlow(TrainerConnectionState.Disconnected)
    val connectionState: StateFlow<TrainerConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<Advertisement>>(emptyList())
    val discoveredDevices: StateFlow<List<Advertisement>> = _discoveredDevices.asStateFlow()

    private val _pairedTrainerId = MutableStateFlow<String?>(null)
    val pairedTrainerId: StateFlow<String?> = _pairedTrainerId.asStateFlow()

    private val _connectedTrainerName = MutableStateFlow<String?>(null)
    val connectedTrainerName: StateFlow<String?> = _connectedTrainerName.asStateFlow()

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    
    private var activePeripheral: Peripheral? = null

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

    private var isMockPedaling = false

    private var mockJob: Job? = null

    fun setTargetPower(watts: Int) {
        _targetPower.value = watts
        // TODO: Replace with real FTMS Control Point write
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
        _connectionState.value = TrainerConnectionState.Connected
        activePeripheral = null
        startMockDataStream()
    }

    private fun startMockDataStream() {
        mockJob?.cancel()
        mockJob = scope.launch {
            while(true) {
                if (isMockPedaling) {
                    val target = _targetPower.value
                    if (target > 0) {
                        // Simulate holding the target power (ERG mode)
                        _power.value = target + (-5..5).random()
                        _cadence.value = (85..95).random()
                        _heartRate.value = (140..155).random()
                    } else {
                        // Simulate pedaling lightly
                        _power.value = (20..50).random()
                        _cadence.value = (60..80).random()
                        _heartRate.value = (110..120).random()
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun stopMockDataStream() {
        mockJob?.cancel()
        isMockPedaling = false
        _power.value = 0
        _cadence.value = 0
        _heartRate.value = 0
    }

    fun startScan() {
        if (_connectionState.value == TrainerConnectionState.Scanning || _connectionState.value == TrainerConnectionState.Connected) return
        
        _connectionState.value = TrainerConnectionState.Scanning
        _discoveredDevices.value = emptyList()

        // 1818 = Cycling Power Service
        // 1826 = Fitness Machine Service
        scanJob = Scanner {
            filters = listOf(
                Filter.Service(uuidFrom("00001818-0000-1000-8000-00805f9b34fb")),
                Filter.Service(uuidFrom("00001826-0000-1000-8000-00805f9b34fb"))
            )
        }.advertisements
            .onEach { advertisement ->
                val current = _discoveredDevices.value.toMutableList()
                if (current.none { it.identifier == advertisement.identifier }) {
                    current.add(advertisement)
                    _discoveredDevices.value = current
                }
            }
            .catch { 
                _connectionState.value = TrainerConnectionState.Error 
            }
            .launchIn(scope)
    }

    fun stopScan() {
        scanJob?.cancel()
        if (_connectionState.value == TrainerConnectionState.Scanning) {
            _connectionState.value = TrainerConnectionState.Disconnected
        }
    }

    fun connectToDevice(advertisement: Advertisement) {
        stopScan()
        _connectionState.value = TrainerConnectionState.Connecting
        _pairedTrainerId.value = advertisement.identifier
        _connectedTrainerName.value = advertisement.name ?: "Unknown Trainer"

        connectionJob?.cancel()
        connectionJob = scope.launch {
            try {
                val peripheral = scope.peripheral(advertisement)
                activePeripheral = peripheral
                
                peripheral.state.onEach { state ->
                    when (state) {
                        is State.Connecting -> _connectionState.value = TrainerConnectionState.Connecting
                        is State.Connected -> {
                            _connectionState.value = TrainerConnectionState.Connected
                            startMockDataStream()
                        }
                        is State.Disconnecting -> _connectionState.value = TrainerConnectionState.Disconnecting
                        is State.Disconnected -> {
                            _connectionState.value = TrainerConnectionState.Disconnected
                            activePeripheral = null
                            _connectedTrainerName.value = null
                            stopMockDataStream()
                        }
                    }
                }.launchIn(scope)

                peripheral.connect()
            } catch (e: Exception) {
                _connectionState.value = TrainerConnectionState.Error
                activePeripheral = null
                stopMockDataStream()
            }
        }
    }

    fun disconnect() {
        scope.launch {
            activePeripheral?.disconnect()
        }
        if (_isMockTrainer.value) {
            _isMockTrainer.value = false
            _connectionState.value = TrainerConnectionState.Disconnected
            _connectedTrainerName.value = null
            stopMockDataStream()
        }
    }

    fun forgetTrainer() {
        disconnect()
        _pairedTrainerId.value = null
        _isMockTrainer.value = false
        _connectedTrainerName.value = null
        _connectionState.value = TrainerConnectionState.Disconnected
    }
}
