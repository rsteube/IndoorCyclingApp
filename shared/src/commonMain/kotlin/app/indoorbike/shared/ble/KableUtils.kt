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

import com.benasher44.uuid.uuidFrom
import com.juul.kable.Characteristic
import com.juul.kable.Peripheral

/**
 * Walks `peripheral.services` (after connect) and returns the matching
 * characteristic, or null if absent. Shared by [TrainerManager],
 * [HeartRateMonitor], and [ControllerManager] so each manager doesn't need
 * its own copy.
 */
internal suspend fun Peripheral.findCharacteristic(
    serviceUuidString: String,
    characteristicUuidString: String,
): Characteristic? {
    val sUuid = uuidFrom(serviceUuidString)
    val cUuid = uuidFrom(characteristicUuidString)
    val service = services?.firstOrNull { it.serviceUuid == sUuid } ?: return null
    return service.characteristics.firstOrNull { it.characteristicUuid == cUuid }
}
