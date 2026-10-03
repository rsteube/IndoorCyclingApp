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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * The radio on Sailfish OS before the BlueZ bridge exists (milestone 2 of
 * docs/SAILFISH_PORT.md): Bluetooth reads as unavailable, so the managers'
 * reconnect loops park on it instead of scanning, and the demo trainer is the
 * only one there is.
 */
internal object NoRadio : BleCentral {
    private const val REASON = "no BLE transport on this build"

    override val availability: Flow<BleAvailability> = flowOf(BleAvailability.Unavailable(REASON))

    override fun scan(services: List<String>): Flow<BleAdvertisement> = flow { throw BleRadioOffException(REASON) }

    override fun peripheral(
        scope: CoroutineScope,
        advertisement: BleAdvertisement,
        logTag: String,
        label: String,
    ): BlePeripheral = throw BleRadioOffException(REASON)
}

actual fun platformBleCentral(): BleCentral = NoRadio
