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

import com.juul.kable.Bluetooth
import com.juul.kable.Reason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Android half of the availability gate, where the real [Reason] values
 * exist. `Reason` is an expect enum with no shared entries, so a common test
 * cannot name one, and the sequence that matters here is Android's specifically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothAvailabilityGateAndroidTest {

    /**
     * Toggling Bluetooth on a real device does not go straight from off to on:
     * the adapter reports `Off`, then `TurningOn`, then `Available`. Each of
     * those is a distinct value, so a naive implementation would write a line
     * per step. One park is one explanation.
     */
    @Test
    fun aRealAdapterToggleLogsOneReasonNotOnePerIntermediateState() = runTest {
        val state = MutableStateFlow<Bluetooth.Availability>(
            Bluetooth.Availability.Unavailable(Reason.Off)
        )
        val reasons = mutableListOf<String>()

        val gate = launch { awaitBluetoothAvailable(state) { reasons += it } }
        runCurrent()

        state.value = Bluetooth.Availability.Unavailable(Reason.TurningOn)
        runCurrent()
        state.value = Bluetooth.Availability.Available
        gate.join()

        assertEquals(listOf("Off"), reasons, "expected the first reason only, got $reasons")
    }

    /**
     * Below API 31 a BLE scan with location services off returns nothing at all
     * rather than failing, so the app used to scan forever and find nothing with
     * no explanation anywhere. Parking on it names the cause in the log instead.
     */
    @Test
    fun locationServicesDisabledIsReportedAsTheReason() = runTest {
        val state = MutableStateFlow<Bluetooth.Availability>(
            Bluetooth.Availability.Unavailable(Reason.LocationServicesDisabled)
        )
        val reasons = mutableListOf<String>()
        var waited = false

        val gate = launch { waited = awaitBluetoothAvailable(state) { reasons += it } }
        runCurrent()
        state.value = Bluetooth.Availability.Available
        gate.join()

        assertTrue(waited, "a location-services park is still a park")
        assertEquals(listOf("LocationServicesDisabled"), reasons)
    }
}
