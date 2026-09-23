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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetrySourcesTest {

    private val stale = TelemetrySources.STALE_AFTER_MS

    @Test
    fun `with no dedicated service Indoor Bike Data is always believed`() {
        val s = TelemetrySources()
        assertTrue(s.bikeDataPowerCounts(0))
        assertTrue(s.bikeDataCadenceCounts(0))
        assertTrue(s.bikeDataHeartRateCounts(0))
    }

    @Test
    fun `a speaking power service outranks it and a silent one does not`() {
        val s = TelemetrySources()
        s.heardPowerService(10_000, carriesCrank = true)
        assertFalse(s.bikeDataPowerCounts(10_000 + stale))
        assertFalse(s.bikeDataCadenceCounts(10_000 + stale))
        assertTrue(s.bikeDataPowerCounts(10_000 + stale + 1))
    }

    @Test
    fun `power without crank data leaves cadence to Indoor Bike Data`() {
        val s = TelemetrySources()
        s.heardPowerService(10_000, carriesCrank = false)
        assertFalse(s.bikeDataPowerCounts(10_001))
        assertTrue(s.bikeDataCadenceCounts(10_001))
    }

    @Test
    fun `a new connection forgets the last one's services`() {
        val s = TelemetrySources()
        s.heardPowerService(10_000, carriesCrank = true)
        s.heardHeartRateService(10_000)
        s.reset()
        assertTrue(s.bikeDataPowerCounts(10_001))
        assertTrue(s.bikeDataHeartRateCounts(10_001))
    }
}
