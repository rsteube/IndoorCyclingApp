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

import app.indoorbike.shared.ble.zwift.ZwiftHandshake
import app.indoorbike.shared.ble.zwift.ZwiftKeyPair
import app.indoorbike.shared.ble.zwift.ZwiftSession
import app.indoorbike.shared.ble.zwift.generateZwiftKeyPair
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.redactDeviceId
import com.benasher44.uuid.uuidFrom
import com.juul.kable.Advertisement
import com.juul.kable.BluetoothDisabledException
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
internal enum class ControllerProtocol { Unknown, Plaintext, Encrypted }

sealed class ControllerEvent {
    object Plus : ControllerEvent()
    object Minus : ControllerEvent()
}

/**
 * Optional external rider-controller (Zwift Click, Zwift Ride) for in-ride
 * ±difficulty input. Lives next to [TrainerManager] / [HeartRateMonitor] but
 * uses an independent BLE peripheral.
 *
 * All of them expose the same custom service and the same three channels:
 *  - Custom service `00000001-19CA-4651-86E5-FA29DCDD09D1`
 *  - Control point (write)  `00000003-...` opens the session.
 *  - Async (notify)         `00000002-...` carries keypad frames and idle pings.
 *  - Sync TX (indicate)     `00000004-...` carries the reply to the handshake.
 *
 * What differs is the handshake, and there are two families of it. Older units
 * take the literal bytes "RideOn" and answer with the same, after which
 * everything is in clear. Newer ones, the Click included, take "RideOn"
 * followed by an ephemeral P-256 public key and answer with their own, after
 * which every frame is AES-CCM encrypted under a key derived from the two.
 * [ZwiftSession] handles the second case; which family a controller belongs to
 * is decided by what it answers, never by its name. Zwift Play, whose
 * handshake differs again, is not supported.
 *
 * Keypad frames, once decrypted if they needed it:
 *  `[ 0x37, <protobuf body> ]`
 *  - The leading byte 0x37 is the keypad-notification message type.
 *  - The body is a tiny protobuf with two varint fields:
 *      field 1 (tag 0x08) is the Plus  button: 0 = pressed, 1 = released
 *      field 2 (tag 0x10) is the Minus button: 0 = pressed, 1 = released
 *  - Idle pings are 0x19 / 0x15 and are ignored. The 0x19 body carries the
 *    battery level, which is also readable from the standard service.
 *
 * Only press-edges (released to pressed) reach [events]; auto-repeat is left to
 * the consumer, so a long press produces one Plus or Minus today.
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

    /** Emits the device name on each (re)connect; drives the UI "connected" toast. */
    private val _justConnected = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val justConnected: SharedFlow<String> = _justConnected.asSharedFlow()

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var stateListenerJob: Job? = null
    private var observeJobs: List<Job> = emptyList()

    /**
     * The retried RideOn handshake. Tracked so a disconnect or a fresh
     * connect cancels it rather than leaving it writing to a peripheral
     * that has gone away.
     */
    private var handshakeJob: Job? = null
    private var activePeripheral: Peripheral? = null

    /**
     * The ephemeral key pair offered to the controller, and the session its
     * reply establishes. Both are per-connection: a new key pair every connect,
     * and no session until the controller has answered the handshake.
     *
     * A null [session] after the handshake is not a failure. Ride controllers
     * and older firmware answer a bare "RideOn" and then talk in clear, so the
     * absence of a session is how the plaintext protocol is selected.
     */
    private var keyPair: ZwiftKeyPair? = null
    private var session: ZwiftSession? = null

    /** Whether a frame has been read successfully since this connect. */
    private val _keypadLive = MutableStateFlow(false)

    /**
     * A controller that is connected, has been asked, and has given no answer.
     *
     * Only set once the handshake has run out of attempts, so it means "this
     * will not start working", not "not yet" - the same distinction
     * [HeartRateMonitor.sensorSilent] draws, and for the same reason: the rider
     * needs to know the difference between waiting and acting. Without it a
     * controller whose protocol the app cannot speak looks exactly like one
     * that works, which is precisely how this went unnoticed through two
     * releases.
     */
    private val _keypadSilent = MutableStateFlow(false)
    val keypadSilent: StateFlow<Boolean> = _keypadSilent.asStateFlow()
    private val advertisementById = mutableMapOf<String, Advertisement>()

    // Persistent auto-reconnect supervisor (see TrainerManager for the model).
    private val wantReconnect = MutableStateFlow(false)
    private val scanningAllowed = MutableStateFlow(true)
    private var targetPairedId: String? = null

    init { scope.launch { reconnectSupervisor() } }

    // Edge-tracking for press detection — only emit on released → pressed.
/**
     * Which protocol this controller turned out to speak.
     *
     * Nothing can be parsed until this is decided. A controller starts
     * streaming on its data channel before its handshake reply arrives on the
     * indication channel - six milliseconds earlier, measured - and those
     * early frames are ciphertext. Handing them to the keypad parser reads
     * random bytes as button state, and roughly one frame in 256 opens with
     * the keypad message type and is taken seriously.
     *
     * A StateFlow rather than a flag because the handshake watchdog waits on
     * it while the observe coroutines write it.
     */
    private val protocol = MutableStateFlow(ControllerProtocol.Unknown)

    /** How many of the controller's channels have a live subscription. */
    private val subscriptionsReady = MutableStateFlow(0)

    private var plusPressed = false
    private var minusPressed = false

    /**
     * Whether [plusPressed] and [minusPressed] describe the controller rather
     * than just their initial value. Until they do, a frame reporting a held
     * button is a starting position, not a press.
     */
    private var keypadStateKnown = false

    // Public API ─────────────────────────────────────────────────────────────

    /**
     * Arm persistent auto-reconnect to a previously paired controller. Records
     * the standing intent and keeps retrying (windowed scans + backoff) until
     * the controller is found. Scanning is gated to foreground/active-ride via
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
            if (_connectionState.value == ControllerConnectionState.Scanning) {
                _connectionState.value = ControllerConnectionState.Disconnected
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
            if (state == ControllerConnectionState.Connected ||
                state == ControllerConnectionState.Connecting
            ) {
                _connectionState.first {
                    it == ControllerConnectionState.Disconnected || it == ControllerConnectionState.Error
                }
                backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
                continue
            }

            // See TrainerManager: wait out a disabled radio instead of scanning
            // into it, and do not let that wait push the retry cadence back.
            if (awaitBluetoothAvailable { reason -> Log.i(TAG, "Waiting for Bluetooth ($reason)") }) {
                backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
                Log.i(TAG, "Bluetooth available; resuming controller scans")
            }

            resetDiscoveryCaches()
            _connectionState.value = ControllerConnectionState.Scanning
            val found = withTimeoutOrNull(BleReconnectPolicy.SCAN_WINDOW_MS) {
                try {
                    Scanner { logging(scannerLogging()) }.advertisements
                        .onEach { addDiscovered(it) }
                        .firstOrNull { it.identifier == id }
                } catch (e: CancellationException) {
                    // Scan-window timeout or lifecycle gate close: routine, and
                    // not something a bug report should be led with. Rethrow so
                    // `withTimeoutOrNull` sees its own timeout.
                    throw e
                } catch (e: BluetoothDisabledException) {
                    // See TrainerManager: expected, and the stack adds nothing.
                    Log.i(TAG, "Controller scan stopped: Bluetooth turned off")
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "Controller auto-reconnect scan failed", e)
                    null
                }
            }

            if (found != null) {
                connectInternal(found)
                _connectionState.first {
                    it == ControllerConnectionState.Disconnected || it == ControllerConnectionState.Error
                }
                backoff = BleReconnectPolicy.INITIAL_BACKOFF_MS
                delay(BleReconnectPolicy.POST_DROP_DELAY_MS)
            } else {
                if (_connectionState.value == ControllerConnectionState.Scanning) {
                    _connectionState.value = ControllerConnectionState.Disconnected
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(BleReconnectPolicy.MAX_BACKOFF_MS)
            }
        }
    }

    fun startScan() {
        // Manual pairing takes over from background auto-reconnect.
        wantReconnect.value = false

        if (_connectionState.value == ControllerConnectionState.Connected) return

        _connectionState.value = ControllerConnectionState.Scanning
        resetDiscoveryCaches()

        scanJob?.cancel()
        // Scan unfiltered: Zwift Click v1 sometimes omits its 128-bit custom
        // service from the advertisement payload (it's only visible after
        // GATT connect), so a service-UUID filter would never match. Same
        // trick we use for Garmin HRM-Pro.
        scanJob = Scanner { logging(scannerLogging()) }.advertisements
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
            Log.w(TAG, "connectToCandidate: unknown controller id=${redactDeviceId(candidateId)}")
            _connectionState.value = ControllerConnectionState.Error
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
                Log.w(TAG, "Controller disconnect failed", e)
            }
        }
    }

    fun forgetDevice() {
        disconnect()
        targetPairedId = null
        _pairedDeviceId.value = null
        _connectedDeviceName.value = null
        _connectionState.value = ControllerConnectionState.Disconnected
    }

    fun dispose() {
        scope.cancel()
    }

    // Internals ──────────────────────────────────────────────────────────────

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
        if (!looksLikeController(ad)) return
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

        // Stay on this controller: arm persistent reconnect for future drops.
        targetPairedId = advertisement.identifier
        wantReconnect.value = true

        stateListenerJob?.cancel()
        observeJobs.forEach { it.cancel() }
        observeJobs = emptyList()
        handshakeJob?.cancel()
        handshakeJob = null
        connectionJob?.cancel()
        keyPair = null
        session = null
        _keypadLive.value = false
        _keypadSilent.value = false
        plusPressed = false
        minusPressed = false
        keypadStateKnown = false

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
                            "Controller observation failed on ${redactDeviceId(advertisement.identifier)}",
                            cause,
                        )
                        throw cause
                    }
                }
                activePeripheral = peripheral

                stateListenerJob = peripheral.state.onEach { state ->
                    when (state) {
                        is State.Connecting -> _connectionState.value = ControllerConnectionState.Connecting
                        is State.Connected -> {
                            _connectionState.value = ControllerConnectionState.Connected
                            _justConnected.tryEmit(_connectedDeviceName.value ?: "Controller")
                            startKeypadObserve(peripheral)
                        }
                        is State.Disconnecting -> _connectionState.value = ControllerConnectionState.Disconnecting
                        is State.Disconnected -> {
                            _connectionState.value = ControllerConnectionState.Disconnected
                            observeJobs.forEach { it.cancel() }
                            observeJobs = emptyList()
                            // Otherwise a handshake still in its confirm wait
                            // keeps writing RideOn to a peripheral that has
                            // gone away, once every eight seconds.
                            handshakeJob?.cancel()
                            handshakeJob = null
                            activePeripheral = null
                        }
                    }
                }.launchIn(scope)

                peripheral.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Controller connect failed", e)
                _connectionState.value = ControllerConnectionState.Error
                activePeripheral = null
            }
        }
    }

    /**
     * Write the controller's GATT profile to the log, once per connect.
     *
     * Zwift's controllers are a family, not a device: Click, Play and Ride all
     * advertise the same custom service, newer units add an encrypted handshake
     * this app does not implement, and a rider reporting "it connects but the
     * buttons do nothing" has no way to tell us which one they are holding.
     * The UUIDs it exposes do. Cheap enough at one connect, and it turns the
     * next report of this into something answerable instead of a guess.
     */
    private fun logGattProfile(peripheral: Peripheral) {
        val services = peripheral.services
        if (services.isNullOrEmpty()) {
            Log.w(TAG, "Controller exposed no services")
            return
        }
        Log.i(TAG, "Controller GATT profile: ${services.size} service(s)")
        services.forEach { service ->
            Log.i(TAG, "  service ${service.serviceUuid}")
            service.characteristics.forEach { c ->
                // Properties are what actually assign the roles: whichever
                // characteristic is writable is the control point, whichever
                // notifies is the data path. Assuming that mapping from the
                // UUID's last digit is how this ended up talking to the wrong
                // one for however long.
                Log.i(TAG, "    ${c.characteristicUuid} props=${describeProperties(c.properties.value)}")
            }
        }
    }

    /**
     * Read the standard Device Information service and log what it says.
     *
     * "Zwift Click" covers more than one piece of hardware and more than one
     * firmware, and the protocol differs between them. A rider cannot be
     * expected to know which one is in their hand; the device will say.
     *
     * The serial number is deliberately not read: it identifies the unit, and
     * these lines can leave the phone in a bug report.
     */
    private suspend fun logDeviceInfo(peripheral: Peripheral) {
        suspend fun readString(label: String, uuid: String) {
            try {
                val char = peripheral.findCharacteristic(DEVICE_INFO_SERVICE, uuid) ?: return
                val text = peripheral.read(char).decodeToString().trim { it <= ' ' }
                if (text.isNotEmpty()) Log.i(TAG, "  $label: $text")
            } catch (e: Exception) {
                Log.w(TAG, "  $label unreadable", e)
            }
        }
        Log.i(TAG, "Controller device information:")
        readString("manufacturer", DEVICE_INFO_MANUFACTURER)
        readString("hardware", DEVICE_INFO_HARDWARE_REV)
        readString("firmware", DEVICE_INFO_FIRMWARE_REV)
    }

    /**
     * Subscribe to the Click's keypad, then open its pipe.
     *
     * Order matters, and this used to have none: the handshake write and the
     * two subscriptions were launched as three independent coroutines that
     * raced. "RideOn" is what makes the device start notifying, so losing that
     * race means it starts talking into a characteristic whose notifications
     * the phone has not switched on yet, and the reply to the handshake lands
     * on an indication nobody is listening for. The controller connects, says
     * it is connected, and every button press goes nowhere - with no error
     * anywhere, because from the app's side nothing failed.
     *
     * This is the third time this shape of bug has appeared here. The FTMS
     * control point needs its indications enabled before any command is
     * written, or strict trainers reject the write; TrainerManager's telemetry
     * subscriptions were made sequential because launching them concurrently
     * raced the job bookkeeping. Same fix both times, and the same one here:
     * suspend, and do it in order.
     *
     * [observeJobs] is bound to the list up front rather than at the end, so a
     * disconnect arriving midway cancels the subscriptions made so far instead
     * of seeing an empty list and leaking them.
     */
    private suspend fun startKeypadObserve(peripheral: Peripheral) {
        observeJobs.forEach { it.cancel() }
        val jobs = mutableListOf<Job>()
        observeJobs = jobs
        protocol.value = ControllerProtocol.Unknown
        subscriptionsReady.value = 0

        logGattProfile(peripheral)
        logDeviceInfo(peripheral)

        val service = peripheral.services?.firstOrNull { it.serviceUuid == uuidFrom(ZWIFT_CUSTOM_SERVICE) }
        if (service == null) {
            Log.w(TAG, "Zwift service not present - not a Zwift controller?")
            return
        }

        // The two channels the protocol defines, and no others. 0005 and 0006
        // exist on newer firmware and carry nothing this app wants; enabling
        // indications on them achieves nothing and is one more thing to go
        // wrong on hardware that cannot be tested here.
        val listenable = service.characteristics.filter {
            it.characteristicUuid == uuidFrom(ZWIFT_ASYNC_CHAR) ||
                it.characteristicUuid == uuidFrom(ZWIFT_SYNC_TX_CHAR)
        }
        Log.i(TAG, "Subscribing to ${listenable.size} controller channel(s)")
        listenable.forEach { char ->
            val label = char.characteristicUuid.toString().substringBefore('-')
            try {
                jobs += peripheral
                    .observe(char) {
                        Log.i(TAG, "$label subscription established")
                        subscriptionsReady.value += 1
                    }
                    .onEach { bytes -> onControllerFrame(label, bytes) }
                    .catch { Log.w(TAG, "Controller $label observe failed", it) }
                    .launchIn(scope)
            } catch (e: Exception) {
                Log.w(TAG, "Controller $label subscribe failed", e)
            }
        }

        val controlPoint = service.characteristics.firstOrNull {
            it.characteristicUuid == uuidFrom(ZWIFT_SYNC_RX_CHAR)
        }
        if (controlPoint == null) {
            Log.w(TAG, "No control point on the Zwift service; cannot open the pipe")
            return
        }

        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            // Kable sets subscriptions up asynchronously, so having called
            // observe() first is not the same as being subscribed. Without this
            // the device can answer before the phone is listening, and the
            // answer is simply lost.
            val ready = withTimeoutOrNull(SUBSCRIBE_WAIT_MS) {
                subscriptionsReady.first { it >= listenable.size }
            } != null
            if (!ready) {
                Log.w(TAG, "Only ${subscriptionsReady.value}/${listenable.size} subscriptions live; continuing anyway")
            }

            val keys = generateZwiftKeyPair()
            keyPair = keys

            repeat(RIDE_ON_ATTEMPTS) { round ->
                val sent = runCatching {
                    peripheral.write(
                        controlPoint,
                        ZwiftHandshake.hello(keys.publicKey),
                        WriteType.WithResponse,
                    )
                }.onFailure {
                    Log.w(TAG, "Handshake write failed (round ${round + 1})", it)
                }.isSuccess

                if (sent) Log.i(TAG, "Handshake sent (round ${round + 1})")
                delay(RIDE_ON_CONFIRM_MS)
                if (protocol.value != ControllerProtocol.Unknown) return@launch
            }
            _keypadSilent.value = true
            Log.w(
                TAG,
                "Controller connected but never answered the handshake; " +
                    "buttons will do nothing. Use the in-app +/- stepper.",
            )
        }
    }

    /**
     * One frame from the controller, on whichever channel it arrived.
     *
     * The first frame after a handshake is the controller's half of the key
     * exchange, and it arrives on the same channel as everything else, so this
     * has to recognise it rather than wait for it somewhere separate. After
     * that every frame is encrypted and the counter that prefixes it is both
     * the replay guard and part of the nonce.
     */
    private fun onControllerFrame(label: String, bytes: ByteArray) {
        if (protocol.value == ControllerProtocol.Unknown) {
            keyPair?.let { keys ->
                ZwiftSession.establish(keys, bytes)?.let {
                    session = it
                    protocol.value = ControllerProtocol.Encrypted
                    Log.i(TAG, "Encrypted session established on $label")
                    return
                }
            }
            if (ZwiftHandshake.isPlaintextAck(bytes)) {
                // Nothing to derive: this controller talks in clear.
                protocol.value = ControllerProtocol.Plaintext
                Log.i(TAG, "Controller answered in plaintext; no session needed")
                return
            }
            // The controller talking before its reply has been read. Dropped
            // rather than guessed at: see [protocol].
            return
        }

        val plaintext = if (protocol.value == ControllerProtocol.Encrypted) {
            session?.decrypt(bytes) ?: run {
                Log.w(TAG, "$label frame did not authenticate; dropped")
                return
            }
        } else {
            bytes
        }

        // Only the first frame, and then only presses. The controller sends a
        // keepalive every five seconds and a burst at ~9 Hz while a button is
        // held; logging all of it would push everything else out of the
        // diagnostics buffer within minutes of riding, which is the opposite of
        // what a bug report needs.
        if (!_keypadLive.value) {
            _keypadLive.value = true
            _keypadSilent.value = false
            Log.i(TAG, "Controller pipe open, first frame on $label: ${plaintext.toHex()}")
        }
        handleControllerBytes(plaintext)
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

        // The first frame of a connect states where the buttons are, not that
        // they moved. A sleeping controller is woken by pressing one, so firing
        // on it would step the rider's target every single time it reconnects -
        // and the button they happened to press decides which way.
        if (!keypadStateKnown) {
            keypadStateKnown = true
            plusPressed = newPlus
            minusPressed = newMinus
            return
        }

        // Edge-detect press transitions. Holding the button is *not* an
        // auto-repeat; we expect the caller to press again for another step.
        if (newPlus && !plusPressed) {
            Log.i(TAG, "Controller +")
            _events.tryEmit(ControllerEvent.Plus)
        }
        if (newMinus && !minusPressed) {
            Log.i(TAG, "Controller -")
            _events.tryEmit(ControllerEvent.Minus)
        }

        plusPressed = newPlus
        minusPressed = newMinus
    }

    companion object {
        private const val TAG = "ControllerManager"

        /** How many times to send RideOn before giving up on the pipe opening. */
        private const val DEVICE_INFO_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb"
        private const val DEVICE_INFO_MANUFACTURER = "00002a29-0000-1000-8000-00805f9b34fb"
        private const val DEVICE_INFO_HARDWARE_REV = "00002a27-0000-1000-8000-00805f9b34fb"
        private const val DEVICE_INFO_FIRMWARE_REV = "00002a26-0000-1000-8000-00805f9b34fb"

        /** How long to wait for the notify subscription before sending RideOn regardless. */
        private const val SUBSCRIBE_WAIT_MS = 5_000L

        private const val RIDE_ON_ATTEMPTS = 2

        /**
         * How long to wait for any controller traffic after a RideOn before
         * assuming it did not land. Generous: the cost of resending is one
         * write, and the cost of giving up too early is a dead controller.
         */
        private const val RIDE_ON_CONFIRM_MS = 6_000L

        // Zwift custom GATT service + characteristics (used by Click, Play, Ride
        // — newer Ride firmware also exposes a 16-bit alias `0xFC82`, not used here).
        const val ZWIFT_CUSTOM_SERVICE = "00000001-19ca-4651-86e5-fa29dcdd09d1"
        const val ZWIFT_ASYNC_CHAR = "00000002-19ca-4651-86e5-fa29dcdd09d1"
        const val ZWIFT_SYNC_RX_CHAR = "00000003-19ca-4651-86e5-fa29dcdd09d1"
        const val ZWIFT_SYNC_TX_CHAR = "00000004-19ca-4651-86e5-fa29dcdd09d1"


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

/** Lowercase hex, space separated. For reading a controller's wire format off real hardware. */
private fun ByteArray.toHex(): String =
    joinToString(" ") { b ->
        val v = b.toInt() and 0xFF
        val hi = "0123456789abcdef"[v shr 4]
        val lo = "0123456789abcdef"[v and 0x0F]
        "$hi$lo"
    }

/**
 * Decode a GATT characteristic property bitmask into names. Kable exposes the
 * raw value; the bit meanings are from the Bluetooth core spec.
 */
private fun describeProperties(bits: Int): String {
    val names = buildList {
        if (bits and 0x01 != 0) add("broadcast")
        if (bits and 0x02 != 0) add("read")
        if (bits and 0x04 != 0) add("writeNoResponse")
        if (bits and 0x08 != 0) add("write")
        if (bits and 0x10 != 0) add("notify")
        if (bits and 0x20 != 0) add("indicate")
        if (bits and 0x40 != 0) add("signedWrite")
        if (bits and 0x80 != 0) add("extended")
    }
    return if (names.isEmpty()) "none(0x${bits.toString(16)})" else names.joinToString("|")
}
