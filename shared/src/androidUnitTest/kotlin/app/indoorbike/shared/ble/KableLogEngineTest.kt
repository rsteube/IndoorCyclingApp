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

import app.indoorbike.shared.util.DiagnosticsLog
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * The bridge exists so that a strap which connects and then says nothing leaves
 * a trace in a bug report. The report attached to the issue that prompted it
 * contained no line from the heart rate path at all, because the only component
 * that noticed the failure was Kable, and Kable was logging into the void.
 */
class KableLogEngineTest {

    @BeforeTest
    fun setUp() = DiagnosticsLog.resetForTest()

    @AfterTest
    fun tearDown() = DiagnosticsLog.resetForTest()

    /**
     * The line this whole bridge is for. Kable logs a failed notification-enable
     * at verbose level and swallows the exception, so this is the only evidence
     * that exists anywhere when the Bluetooth stack refuses the CCCD write.
     */
    @Test
    fun suppressedObservationFailuresReachTheBugReport() {
        KableLogEngine.verbose(null, "Kable/Observation", "Suppressed failure: GattRequestRejectedException")

        assertContains(DiagnosticsLog.snapshot(), "Suppressed failure")
    }

    @Test
    fun connectionLifecycleAndFailuresAreKept() {
        KableLogEngine.info(null, "Kable/Peripheral", "Connected")
        KableLogEngine.warn(null, "Kable/Peripheral", "Characteristic is missing config descriptor.")
        KableLogEngine.error(null, "Kable/Scanner", "Scan could not be started, error code 2.")

        val snapshot = DiagnosticsLog.snapshot()
        assertContains(snapshot, "Connected")
        assertContains(snapshot, "missing config descriptor")
        assertContains(snapshot, "error code 2")
    }

    /**
     * Debug is where every per-notification GATT callback lands. At one line per
     * sample per characteristic it would push the whole 300-line buffer out
     * within a couple of minutes of riding, so a report drafted at the end of a
     * ride would show nothing but the last few seconds of telemetry - the
     * opposite of what the bridge is for.
     */
    @Test
    fun thePerNotificationFirehoseIsDropped() {
        repeat(500) {
            KableLogEngine.debug(null, "Kable/Callback", "onCharacteristicChanged")
        }
        KableLogEngine.info(null, "Kable/Peripheral", "Connecting")

        val snapshot = DiagnosticsLog.snapshot()
        assertTrue(
            "onCharacteristicChanged" !in snapshot,
            "per-notification callbacks must not reach the diagnostics buffer",
        )
        assertContains(snapshot, "Connecting")
    }
}
