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
