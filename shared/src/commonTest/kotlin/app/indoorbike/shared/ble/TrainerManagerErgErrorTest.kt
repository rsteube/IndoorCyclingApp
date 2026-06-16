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

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers how FTMS Control Point Response Code indications map to the rider-facing
 * [TrainerManager.ergError]. Format is `[0x80, requestOpCode, resultCode, ...]`.
 */
class TrainerManagerErgErrorTest {

    private fun bytes(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }

    @Test
    fun successfulSetTargetPowerClearsError() {
        val tm = TrainerManager()
        // A prior failure left an error showing.
        tm.handleControlPointResponse(bytes(0x80, 0x05, 0x04)) // Operation Failed
        assertTrue(tm.ergError.value != null)
        // Trainer then confirms a Set Target Power command.
        tm.handleControlPointResponse(bytes(0x80, 0x05, 0x01)) // Success
        assertNull(tm.ergError.value)
    }

    @Test
    fun controlNotPermittedRecoversThenSurfaces() {
        val tm = TrainerManager()
        // Early "Control Not Permitted" rejections trigger silent recovery
        // (re-request control + re-apply target), so no banner appears yet.
        tm.handleControlPointResponse(bytes(0x80, 0x05, 0x05))
        assertNull(tm.ergError.value)
        // Once the retry budget is exhausted, surface a helpful error.
        repeat(TrainerManager.MAX_CONTROL_RETRIES) {
            tm.handleControlPointResponse(bytes(0x80, 0x05, 0x05))
        }
        val msg = tm.ergError.value
        assertTrue(msg != null && msg.contains("ERG control"))
    }

    @Test
    fun setTargetPowerSuccessAfterControlGrantedResetsRetries() {
        val tm = TrainerManager()
        // A Set Target Power success clears the retry budget so a later isolated
        // rejection gets the full recovery allowance again (no immediate banner).
        tm.handleControlPointResponse(bytes(0x80, 0x05, 0x01)) // Set Target Power OK
        tm.handleControlPointResponse(bytes(0x80, 0x05, 0x05)) // one rejection
        assertNull(tm.ergError.value)
    }

    @Test
    fun invalidParameterIsSurfaced() {
        val tm = TrainerManager()
        tm.handleControlPointResponse(bytes(0x80, 0x05, 0x03)) // Invalid Parameter
        val msg = tm.ergError.value
        assertTrue(msg != null && msg.contains("out of range"))
    }

    @Test
    fun nonResponseOpcodeIsIgnored() {
        val tm = TrainerManager()
        // A regular notification, not a 0x80 Response Code, must not raise an error.
        tm.handleControlPointResponse(bytes(0x05, 0x01, 0x02))
        assertNull(tm.ergError.value)
    }

    @Test
    fun truncatedResponseIsIgnored() {
        val tm = TrainerManager()
        tm.handleControlPointResponse(bytes(0x80, 0x05))
        assertNull(tm.ergError.value)
    }
}
