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

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    
    private var activePeripheral: Peripheral? = null

    fun startScan() {
        if (_connectionState.value == TrainerConnectionState.Scanning || _connectionState.value == TrainerConnectionState.Connected) return
        
        _connectionState.value = TrainerConnectionState.Scanning
        _discoveredDevices.value = emptyList()

        // You can add Filter to Scanner to only find cycling power (1818) or FTMS (1826)
        scanJob = Scanner().advertisements
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

        connectionJob?.cancel()
        connectionJob = scope.launch {
            try {
                val peripheral = scope.peripheral(advertisement)
                activePeripheral = peripheral
                
                peripheral.state.onEach { state ->
                    when (state) {
                        is State.Connecting -> _connectionState.value = TrainerConnectionState.Connecting
                        is State.Connected -> _connectionState.value = TrainerConnectionState.Connected
                        is State.Disconnecting -> _connectionState.value = TrainerConnectionState.Disconnecting
                        is State.Disconnected -> {
                            _connectionState.value = TrainerConnectionState.Disconnected
                            activePeripheral = null
                        }
                    }
                }.launchIn(scope)

                peripheral.connect()
            } catch (e: Exception) {
                _connectionState.value = TrainerConnectionState.Error
                activePeripheral = null
            }
        }
    }

    fun disconnect() {
        scope.launch {
            activePeripheral?.disconnect()
        }
    }
}
