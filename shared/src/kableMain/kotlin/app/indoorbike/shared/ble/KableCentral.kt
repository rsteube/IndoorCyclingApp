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
import com.benasher44.uuid.uuidFrom
import com.juul.kable.Advertisement
import com.juul.kable.Bluetooth
import com.juul.kable.BluetoothDisabledException
import com.juul.kable.Characteristic
import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * [BleCentral] on Kable, for Android and iOS: exactly what the managers did
 * with Kable before the interface existed, behind it.
 */
internal object KableCentral : BleCentral {
    override val availability: Flow<BleAvailability>
        get() = Bluetooth.availability.map { it.toBle() }

    override fun scan(services: List<String>): Flow<BleAdvertisement> =
        Scanner {
            if (services.isNotEmpty()) filters = services.map { Filter.Service(uuidFrom(it)) }
            logging(scannerLogging())
        }.advertisements
            .map { it.toBle() }
            .catch { e -> throw if (e is BluetoothDisabledException) BleRadioOffException(e.message, e) else e }

    override fun peripheral(
        scope: CoroutineScope,
        advertisement: BleAdvertisement,
        logTag: String,
        label: String,
    ): BlePeripheral {
        val ad = advertisement.platform as Advertisement
        return KablePeripheral(
            scope.peripheral(ad) {
                logging(peripheralLogging(advertisement.deviceId))
                // With a handler registered, Kable sends observation failures
                // here rather than through the observe flow. Log with device
                // context, then rethrow to keep the behaviour of terminating
                // the flow.
                observationExceptionHandler { cause ->
                    Log.w(logTag, "$label observation failed on ${redactDeviceId(advertisement.deviceId)}", cause)
                    throw cause
                }
            },
        )
    }
}

private class KablePeripheral(private val peripheral: Peripheral) : BlePeripheral {
    override val state: Flow<BleLinkState>
        get() = peripheral.state.map { it.toBle() }

    override val services: List<BleService>?
        get() = peripheral.services?.map { service ->
            val serviceUuid = service.serviceUuid.toString()
            BleService(
                uuid = serviceUuid,
                characteristics = service.characteristics.map { c ->
                    BleCharacteristic(serviceUuid, c.characteristicUuid.toString(), c.properties.value, platform = c)
                },
            )
        }

    override suspend fun connect() {
        peripheral.connect()
    }

    override suspend fun disconnect() {
        peripheral.disconnect()
    }

    override fun observe(characteristic: BleCharacteristic, onSubscription: suspend () -> Unit): Flow<ByteArray> =
        peripheral.observe(characteristic.kable()) { onSubscription() }

    override suspend fun write(characteristic: BleCharacteristic, data: ByteArray, withResponse: Boolean) {
        peripheral.write(
            characteristic.kable(),
            data,
            if (withResponse) WriteType.WithResponse else WriteType.WithoutResponse,
        )
    }

    override suspend fun read(characteristic: BleCharacteristic): ByteArray =
        peripheral.read(characteristic.kable())
}

/** The Kable characteristic behind one of [KablePeripheral.services]. */
private fun BleCharacteristic.kable(): Characteristic = platform as Characteristic

private fun Advertisement.toBle() = BleAdvertisement(
    deviceId = identifier.toString(),
    name = name,
    peripheralName = peripheralName,
    rssi = rssi,
    serviceUuids = uuids.map { it.toString() },
    platform = this,
)

private fun State.toBle(): BleLinkState = when (this) {
    is State.Connecting -> BleLinkState.Connecting
    is State.Connected -> BleLinkState.Connected
    is State.Disconnecting -> BleLinkState.Disconnecting
    is State.Disconnected -> BleLinkState.Disconnected
}

/** Kable's availability as the managers read it; the reason keeps Kable's own name. */
internal fun Bluetooth.Availability.toBle(): BleAvailability = when (this) {
    is Bluetooth.Availability.Available -> BleAvailability.Available
    is Bluetooth.Availability.Unavailable -> BleAvailability.Unavailable(reason?.toString())
}

actual fun platformBleCentral(): BleCentral = KableCentral
