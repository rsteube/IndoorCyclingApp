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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * The BLE radio, as the device managers need it: whether it can scan, what is
 * advertising, and a connection to one of them.
 *
 * Android and iOS do this through Kable (`KableCentral`, in `kableMain`).
 * Sailfish OS has no Kable: there the transport is BlueZ, driven from the app's
 * C++ side. Everything above this interface - the managers, their reconnect
 * loops, FTMS control, the parsers - is the same code on all three.
 *
 * UUIDs are strings in the 128-bit form, compared ignoring case ([sameUuid]).
 */
interface BleCentral {
    /** The radio's state, current value first. */
    val availability: Flow<BleAvailability>

    /**
     * Advertisements while collected, from devices offering any of [services]
     * (all devices when empty). Throws [BleRadioOffException] when the radio is
     * off, or goes off mid-scan.
     */
    fun scan(services: List<String> = emptyList()): Flow<BleAdvertisement>

    /**
     * A peripheral for [advertisement], not yet connected, living in [scope].
     * A failure inside an observation is logged under [logTag] with [label]
     * and ends that observation's flow.
     */
    fun peripheral(
        scope: CoroutineScope,
        advertisement: BleAdvertisement,
        logTag: String,
        label: String,
    ): BlePeripheral
}

/** Whether the radio can scan. */
sealed interface BleAvailability {
    data object Available : BleAvailability

    /** [reason] is the platform's own word for it, for the log, or null when it gave none. */
    data class Unavailable(val reason: String?) : BleAvailability
}

/** Scanning or connecting with the radio off. */
class BleRadioOffException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/**
 * One advertisement. [deviceId] is what a device is paired by: the MAC address
 * on Android and Sailfish, a CoreBluetooth UUID on iOS.
 *
 * [platform] is the transport's own handle for connecting to it, which only the
 * transport that made it reads.
 */
class BleAdvertisement(
    val deviceId: String,
    val name: String?,
    val peripheralName: String?,
    val rssi: Int,
    val serviceUuids: List<String>,
    val platform: Any? = null,
) {
    fun offers(service: String): Boolean = serviceUuids.any { sameUuid(it, service) }
}

/** A connection's state, as the managers see it. */
enum class BleLinkState { Connecting, Connected, Disconnecting, Disconnected }

/** A connection to one device. Its work ends with the scope it was made in. */
interface BlePeripheral {
    /** The connection's state, current value first, Disconnected before any connect. */
    val state: Flow<BleLinkState>

    /** The GATT table, once connected; null before discovery. */
    val services: List<BleService>?

    suspend fun connect()

    suspend fun disconnect()

    /**
     * Notifications or indications from [characteristic], switched on while
     * collected. [onSubscription] runs each time the subscription is live on
     * the device, which is later than the call to collect.
     */
    fun observe(
        characteristic: BleCharacteristic,
        onSubscription: suspend () -> Unit = {},
    ): Flow<ByteArray>

    suspend fun write(characteristic: BleCharacteristic, data: ByteArray, withResponse: Boolean)

    suspend fun read(characteristic: BleCharacteristic): ByteArray
}

class BleService(val uuid: String, val characteristics: List<BleCharacteristic>)

/**
 * A characteristic of a discovered service. [properties] is the GATT
 * properties bit field. [platform] as on [BleAdvertisement].
 */
class BleCharacteristic(
    val serviceUuid: String,
    val uuid: String,
    val properties: Int,
    val platform: Any? = null,
)

/** Whether two UUID strings name the same UUID. */
fun sameUuid(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

internal fun BlePeripheral.findService(service: String): BleService? =
    services?.firstOrNull { sameUuid(it.uuid, service) }

internal fun BlePeripheral.findCharacteristic(service: String, characteristic: String): BleCharacteristic? =
    findService(service)?.characteristics?.firstOrNull { sameUuid(it.uuid, characteristic) }

/**
 * The radio on this platform: Kable's on Android and iOS, the app's own on
 * Sailfish OS. The managers use it unless given another.
 */
expect fun platformBleCentral(): BleCentral
