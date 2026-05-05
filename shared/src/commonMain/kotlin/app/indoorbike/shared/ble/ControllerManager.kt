package app.indoorbike.shared.ble

import app.indoorbike.shared.util.Log
import com.benasher44.uuid.uuidFrom
import com.juul.kable.Advertisement
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Reuse the trainer's connection-state shape — same lifecycle. */
typealias ControllerConnectionState = TrainerConnectionState
typealias ControllerCandidate = TrainerCandidate

/**
 * Discrete user-input events surfaced from a paired controller. The session
 * layer translates these into difficulty steps (or anything else); the
 * controller itself stays oblivious to what the app does with them.
 *
 * `Plus` and `Minus` are intentionally generic so that future controllers
 * (Zwift Play / Ride, generic remotes) can map their own buttons onto the
 * same event surface without each ride feature having to know the source.
 */
sealed class ControllerEvent {
    object Plus : ControllerEvent()
    object Minus : ControllerEvent()
}

/**
 * Optional external rider-controller (currently Zwift Click v1) for in-ride
 * ±difficulty input. Lives next to [TrainerManager] / [HeartRateMonitor] but
 * uses an independent BLE peripheral.
 *
 * Zwift Click v1 (BC1) protocol — unencrypted, reverse-engineered:
 *  - Custom service `0x00000001-19CA-4651-86E5-FA29DCDD09D1`
 *  - Sync RX (write)   `0x00000003-…` — host writes the literal bytes "RideOn"
 *    on connect; this opens the pipe.
 *  - Async  (notify)   `0x00000002-…` — keypad messages and idle pings arrive here.
 *  - Sync TX (indicate)`0x00000004-…` — handshake reply ("RideOn") arrives here.
 *
 * Click keypad message wire format:
 *  `[ 0x37, <protobuf body> ]`
 *  - The leading byte 0x37 is the Click-notification message type.
 *  - The body is a tiny protobuf with two varint fields:
 *      field 1 (tag 0x08) → Plus  button: 0 = pressed, 1 = released
 *      field 2 (tag 0x10) → Minus button: 0 = pressed, 1 = released
 *  - Idle pings are 0x19 / 0x15 — ignored.
 *
 * We surface only press-edges (released → pressed) on [events]; auto-repeat is
 * left to the consumer (a long press just produces one Plus/Minus today).
 *
 * Encrypted Zwift devices (Play, Ride) require ECDH/AES-CCM and are out of
 * scope here; the manager is structured so a sibling class could plug into
 * [events] without rippling through the UI.
 */
class ControllerManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow(ControllerConnectionState.Disconnected)
    val connectionState: StateFlow<ControllerConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<ControllerCandidate>>(emptyList())
    val discoveredDevices: StateFlow<List<ControllerCandidate>> = _discoveredDevices.asStateFlow()

    private val _pairedDeviceId = MutableStateFlow<String?>(null)
    val pairedDeviceId: StateFlow<String?> = _pairedDeviceId.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    /** Hot stream of press-edge events; replay-0 so consumers only see new presses. */
    private val _events = MutableSharedFlow<ControllerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ControllerEvent> = _events.asSharedFlow()

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var stateListenerJob: Job? = null
    private var observeJobs: List<Job> = emptyList()
    private var activePeripheral: Peripheral? = null
    private val advertisementById = mutableMapOf<String, Advertisement>()

    // Edge-tracking for press detection — only emit on released → pressed.
    private var plusPressed = false
    private var minusPressed = false

    // Public API ─────────────────────────────────────────────────────────────

    /** Try to silently re-connect to a previously paired controller at app launch. */
    fun autoConnectIfPaired(pairedId: String?, timeoutMillis: Long = 15_000) {
        if (pairedId.isNullOrBlank()) return
        if (_connectionState.value != ControllerConnectionState.Disconnected) return

        _pairedDeviceId.value = pairedId
        _connectionState.value = ControllerConnectionState.Scanning
        _discoveredDevices.value = emptyList()
        advertisementById.clear()

        scanJob?.cancel()
        scanJob = scope.launch {
            val found = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
                try {
                    Scanner().advertisements
                        .onEach { addDiscovered(it) }
                        .firstOrNull { it.identifier == pairedId }
                } catch (e: Exception) {
                    Log.w(TAG, "Controller auto-connect scan failed", e)
                    null
                }
            }
            if (found != null) {
                connectInternal(found)
            } else if (_connectionState.value == ControllerConnectionState.Scanning) {
                _connectionState.value = ControllerConnectionState.Disconnected
            }
        }
    }

    fun startScan() {
        if (_connectionState.value == ControllerConnectionState.Scanning ||
            _connectionState.value == ControllerConnectionState.Connected
        ) return

        _connectionState.value = ControllerConnectionState.Scanning
        _discoveredDevices.value = emptyList()
        advertisementById.clear()

        scanJob?.cancel()
        // Scan unfiltered: Zwift Click v1 sometimes omits its 128-bit custom
        // service from the advertisement payload (it's only visible after
        // GATT connect), so a service-UUID filter would never match. Same
        // trick we use for Garmin HRM-Pro.
        scanJob = Scanner().advertisements
            .onEach { ad -> addDiscovered(ad) }
            .catch { e ->
                Log.e(TAG, "Controller scan failed", e)
                _connectionState.value = ControllerConnectionState.Error
            }
            .launchIn(scope)
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value == ControllerConnectionState.Scanning) {
            _connectionState.value = ControllerConnectionState.Disconnected
        }
    }

    fun connectToCandidate(candidateId: String) {
        val ad = advertisementById[candidateId] ?: run {
            Log.w(TAG, "connectToCandidate: unknown controller id=$candidateId")
            _connectionState.value = ControllerConnectionState.Error
            return
        }
        connectInternal(ad)
    }

    fun disconnect() {
        scope.launch {
            try {
                activePeripheral?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Controller disconnect failed", e)
            }
        }
    }

    fun forgetDevice() {
        disconnect()
        _pairedDeviceId.value = null
        _connectedDeviceName.value = null
        _connectionState.value = ControllerConnectionState.Disconnected
    }

    fun dispose() {
        scope.cancel()
    }

    // Internals ──────────────────────────────────────────────────────────────

    private fun addDiscovered(ad: Advertisement) {
        // Always remember the advertisement so auto-connect can match by id,
        // even if the heuristic below would hide it from the visible list.
        advertisementById[ad.identifier] = ad
        if (!looksLikeController(ad)) return
        val current = _discoveredDevices.value
        if (current.none { it.id == ad.identifier }) {
            _discoveredDevices.value = current + ControllerCandidate(
                id = ad.identifier,
                name = ad.name,
                rssi = ad.rssi
            )
        }
    }

    private fun looksLikeController(ad: Advertisement): Boolean {
        // The Zwift custom service may or may not be in the advertisement —
        // accept both signals.
        val zwiftService = uuidFrom(ZWIFT_CUSTOM_SERVICE)
        if (ad.uuids.any { it == zwiftService }) return true
        val name = ad.name ?: ad.peripheralName ?: return false
        return CONTROLLER_NAME_KEYWORDS.any { name.contains(it, ignoreCase = true) }
    }

    private fun connectInternal(advertisement: Advertisement) {
        stopScan()
        _connectionState.value = ControllerConnectionState.Connecting
        _pairedDeviceId.value = advertisement.identifier
        _connectedDeviceName.value = advertisement.name ?: "Unknown Controller"

        stateListenerJob?.cancel()
        observeJobs.forEach { it.cancel() }
        observeJobs = emptyList()
        connectionJob?.cancel()
        plusPressed = false
        minusPressed = false

        connectionJob = scope.launch {
            try {
                val peripheral = scope.peripheral(advertisement)
                activePeripheral = peripheral

                stateListenerJob = peripheral.state.onEach { state ->
                    when (state) {
                        is State.Connecting -> _connectionState.value = ControllerConnectionState.Connecting
                        is State.Connected -> {
                            _connectionState.value = ControllerConnectionState.Connected
                            startKeypadObserve(peripheral)
                        }
                        is State.Disconnecting -> _connectionState.value = ControllerConnectionState.Disconnecting
                        is State.Disconnected -> {
                            _connectionState.value = ControllerConnectionState.Disconnected
                            observeJobs.forEach { it.cancel() }
                            observeJobs = emptyList()
                            activePeripheral = null
                        }
                    }
                }.launchIn(scope)

                peripheral.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Controller connect failed", e)
                _connectionState.value = ControllerConnectionState.Error
                activePeripheral = null
            }
        }
    }

    private fun startKeypadObserve(peripheral: Peripheral) {
        observeJobs.forEach { it.cancel() }
        val jobs = mutableListOf<Job>()

        // Send the unencrypted "RideOn" handshake on the Sync-RX characteristic
        // — this opens the pipe so the device starts notifying keypad state.
        scope.launch {
            try {
                val syncRx = peripheral.findCharacteristic(ZWIFT_CUSTOM_SERVICE, ZWIFT_SYNC_RX_CHAR)
                if (syncRx == null) {
                    Log.w(TAG, "Zwift sync-RX characteristic not found — not a Click?")
                    return@launch
                }
                runCatching {
                    peripheral.write(syncRx, RIDE_ON_BYTES, WriteType.WithResponse)
                }.onFailure { Log.w(TAG, "RideOn handshake write failed", it) }
            } catch (e: Exception) {
                Log.w(TAG, "RideOn handshake failed", e)
            }
        }

        // Async (notify) — keypad messages live here on the Click.
        scope.launch {
            try {
                val async = peripheral.findCharacteristic(ZWIFT_CUSTOM_SERVICE, ZWIFT_ASYNC_CHAR)
                if (async != null) {
                    val job = peripheral.observe(async)
                        .onEach { bytes -> handleControllerBytes(bytes) }
                        .catch { Log.w(TAG, "Controller async observe failed", it) }
                        .launchIn(scope)
                    jobs += job
                }
            } catch (e: Exception) {
                Log.w(TAG, "Controller async subscribe failed", e)
            }
        }

        // Sync TX (indicate) — handshake reply, occasional state messages.
        scope.launch {
            try {
                val syncTx = peripheral.findCharacteristic(ZWIFT_CUSTOM_SERVICE, ZWIFT_SYNC_TX_CHAR)
                if (syncTx != null) {
                    val job = peripheral.observe(syncTx)
                        .onEach { bytes -> handleControllerBytes(bytes) }
                        .catch { Log.w(TAG, "Controller sync-TX observe failed", it) }
                        .launchIn(scope)
                    jobs += job
                }
            } catch (e: Exception) {
                Log.w(TAG, "Controller sync-TX subscribe failed", e)
            }
        }

        observeJobs = jobs
    }

    /**
     * Visible for unit tests: exposed via reflection-free indirection. Keep
     * `internal` so platform code can't call it but tests in the same module can.
     */
    internal fun handleControllerBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        when (bytes[0].toInt() and 0xFF) {
            CLICK_NOTIFICATION_MESSAGE_TYPE -> handleClickKeypad(bytes)
            // 0x19 / 0x15 are idle / battery-level pings; nothing to do here yet.
            else -> { /* ignore other message types for now */ }
        }
    }

    private fun handleClickKeypad(bytes: ByteArray) {
        val parsed = parseClickKeypadStatus(bytes) ?: return
        val newPlus = parsed.plusPressed
        val newMinus = parsed.minusPressed

        // Edge-detect press transitions. Holding the button is *not* an
        // auto-repeat; we expect the caller to press again for another step.
        if (newPlus && !plusPressed) _events.tryEmit(ControllerEvent.Plus)
        if (newMinus && !minusPressed) _events.tryEmit(ControllerEvent.Minus)

        plusPressed = newPlus
        minusPressed = newMinus
    }

    companion object {
        private const val TAG = "ControllerManager"

        // Zwift custom GATT service + characteristics (used by Click, Play, Ride
        // — newer Ride firmware also exposes a 16-bit alias `0xFC82`, not used here).
        const val ZWIFT_CUSTOM_SERVICE = "00000001-19ca-4651-86e5-fa29dcdd09d1"
        const val ZWIFT_ASYNC_CHAR = "00000002-19ca-4651-86e5-fa29dcdd09d1"
        const val ZWIFT_SYNC_RX_CHAR = "00000003-19ca-4651-86e5-fa29dcdd09d1"
        const val ZWIFT_SYNC_TX_CHAR = "00000004-19ca-4651-86e5-fa29dcdd09d1"

        // "RideOn" — host-to-device handshake to start unencrypted comms.
        // Used in plain form for the Click; Play/Ride wrap it with a public key.
        val RIDE_ON_BYTES: ByteArray = byteArrayOf(
            0x52, 0x69, 0x64, 0x65, 0x4F, 0x6E
        )

        // First byte of a Zwift Click keypad-status notification.
        const val CLICK_NOTIFICATION_MESSAGE_TYPE = 0x37 // 55

        // Substrings (case-insensitive) used to recognise controllers when the
        // Zwift custom service isn't present in the advertisement payload.
        // Keep this conservative — false positives can hide other devices.
        private val CONTROLLER_NAME_KEYWORDS = listOf(
            "Zwift Click",
            "Click",          // some firmware advertises just the model token
            "BC1",            // Click v1 internal model id
            "Zwift Play",     // listed for visibility — not yet supported (encrypted)
            "Zwift Ride",
        )
    }
}

// ─── Pure parser, lifted out of the class so it's unit-testable ─────────────

internal data class ClickKeypadStatus(
    val plusPressed: Boolean,
    val minusPressed: Boolean,
)

/**
 * Parse a Zwift Click keypad notification.
 *
 * Wire format (after the 0x37 header byte): a tiny protocol-buffer message
 * with two varint fields. Each "pressed" value is inverse logic — the device
 * reports 0 when held down, 1 when released.
 *
 *   field 1 (tag 0x08, varint) → Plus  button state  (0 = pressed)
 *   field 2 (tag 0x10, varint) → Minus button state  (0 = pressed)
 *
 * Other fields (battery, analog values for Play) may follow and are ignored.
 * Returns null only when the payload is too short to be a keypad message.
 */
internal fun parseClickKeypadStatus(bytes: ByteArray): ClickKeypadStatus? {
    if (bytes.size < 2 || (bytes[0].toInt() and 0xFF) != ControllerManager.CLICK_NOTIFICATION_MESSAGE_TYPE) {
        return null
    }
    var plusReleased = true
    var minusReleased = true
    var i = 1
    while (i < bytes.size) {
        val tag = bytes[i].toInt() and 0xFF
        i += 1
        // We only know how to walk the wire types we care about. Any unknown
        // tag aborts the scan; we don't risk reading past the end of a length-
        // delimited field we don't understand.
        val wireType = tag and 0x07
        val fieldNumber = tag ushr 3
        when (wireType) {
            0 -> {
                // varint
                val (value, consumed) = readVarint(bytes, i) ?: return ClickKeypadStatus(
                    plusPressed = !plusReleased,
                    minusPressed = !minusReleased,
                )
                i += consumed
                when (fieldNumber) {
                    1 -> plusReleased = value != 0L
                    2 -> minusReleased = value != 0L
                    // Other varint fields (e.g. battery on idle messages) are ignored.
                }
            }
            2 -> {
                // length-delimited — skip it
                val (len, consumed) = readVarint(bytes, i) ?: break
                i += consumed + len.toInt()
            }
            else -> break // unknown wire type; bail out safely
        }
    }
    return ClickKeypadStatus(
        plusPressed = !plusReleased,
        minusPressed = !minusReleased,
    )
}

/** Reads a protobuf varint starting at [offset]. Returns (value, bytesConsumed) or null on overflow. */
private fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
    var result = 0L
    var shift = 0
    var i = offset
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        result = result or ((b.toLong() and 0x7FL) shl shift)
        i += 1
        if ((b and 0x80) == 0) return result to (i - offset)
        shift += 7
        if (shift >= 64) return null
    }
    return null
}
