/*
 * Copyright (C) 2026 Juhani Lehtimäki
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reconnect loops park here rather than scanning into a disabled radio.
 * Measured on a Fairphone 4 before this existed: after Bluetooth came back the
 * next scan was about 45 seconds away, because the backoff had grown while the
 * adapter was off. Since the UI tells riders with a silent strap to restart
 * Bluetooth, that delay reads as the advice not working.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothAvailabilityGateTest {

    private val available = Bluetooth.Availability.Available
    private fun unavailable() = Bluetooth.Availability.Unavailable(reason = null)

    @Test
    fun anAvailableAdapterDoesNotWait() = runTest {
        val state = MutableStateFlow<Bluetooth.Availability>(available)

        val waited = awaitBluetoothAvailable(state)

        assertFalse(waited, "an adapter that is already on must not report a wait")
    }

    /**
     * The load-bearing case: the caller has to learn that it waited, because
     * that is what tells it to reset its backoff. Time spent unable to scan is
     * not evidence that the device is absent.
     */
    @Test
    fun aDisabledAdapterSuspendsUntilItReturnsAndReportsTheWait() = runTest {
        val state = MutableStateFlow<Bluetooth.Availability>(unavailable())
        var waited: Boolean? = null

        val gate = launch { waited = awaitBluetoothAvailable(state) }
        runCurrent() // let the gate subscribe before the adapter changes

        // Still parked: nothing should have been scanned in the meantime.
        assertEquals(null, waited)

        state.value = available
        gate.join()

        assertTrue(waited == true, "a wait must be reported so the caller resets its backoff")
    }

    /**
     * A bug report drafted afterwards should say why nothing was happening.
     * Below API 31 this also covers location services being off, where BLE
     * scans return nothing rather than failing, so silence was previously the
     * only symptom.
     */
    @Test
    fun theReasonForWaitingIsReported() = runTest {
        val state = MutableStateFlow<Bluetooth.Availability>(unavailable())
        val reasons = mutableListOf<String>()

        val gate = launch { awaitBluetoothAvailable(state) { reasons += it } }
        runCurrent()
        state.value = available
        gate.join()

        assertEquals(1, reasons.size, "one park should log one reason, got $reasons")
    }

    @Test
    fun anAvailableAdapterLogsNothing() = runTest {
        val state = MutableStateFlow<Bluetooth.Availability>(available)
        val reasons = mutableListOf<String>()

        awaitBluetoothAvailable(state) { reasons += it }

        assertTrue(reasons.isEmpty(), "a healthy adapter must stay silent, got $reasons")
    }
}
