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
package app.indoorbike.shared.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The kinematic half of the drivetrain: cadence and a gear into a road speed. */
class DriveSpeedTest {

    @Test
    fun `until a crank turns, the road speed comes from the caller`() {
        val d = DriveSpeed()
        assertFalse(d.available)
        assertEquals(8.5, d.speedMps(developmentMeters = 6.0, fallbackMps = 8.5))

        // A trainer reporting a cadence field of zero forever is a trainer that
        // cannot count cadence, and has to keep the behaviour it always had.
        repeat(20) { d.update(cadenceRpm = 0, dtSeconds = 0.25) }
        assertFalse(d.available)
        assertEquals(8.5, d.speedMps(developmentMeters = 6.0, fallbackMps = 8.5))
    }

    @Test
    fun `the first turn of the crank is adopted whole`() {
        val d = DriveSpeed()
        d.update(cadenceRpm = 90, dtSeconds = 0.25)
        assertTrue(d.available)
        // 90 rpm through a 6 m gear is 9 m/s. Easing into that from a standstill
        // the rider was never at would be a lie about their legs.
        assertEquals(9.0, d.speedMps(developmentMeters = 6.0, fallbackMps = 0.0), 1e-9)
    }

    @Test
    fun `a settled cadence lands on cadence times development`() {
        val d = DriveSpeed()
        repeat(40) { d.update(cadenceRpm = 80, dtSeconds = 0.25) }
        assertEquals(
            80.0 / 60.0 * 5.0,
            d.speedMps(developmentMeters = 5.0, fallbackMps = 0.0),
            1e-6,
        )
    }

    /**
     * The gear is the one thing that must not be filtered. A shift has to move
     * the speed the road is evaluated at *now*, because that step is the whole
     * sensation of shifting.
     */
    @Test
    fun `a shift steps the drive speed at once`() {
        val d = DriveSpeed()
        repeat(40) { d.update(cadenceRpm = 90, dtSeconds = 0.25) }
        val big = d.speedMps(developmentMeters = 7.0, fallbackMps = 0.0)
        val small = d.speedMps(developmentMeters = 6.5, fallbackMps = 0.0)
        assertEquals(6.5 / 7.0, small / big, 1e-9)
    }

    @Test
    fun `cadence is smoothed rather than followed step for step`() {
        val d = DriveSpeed()
        repeat(40) { d.update(cadenceRpm = 90, dtSeconds = 0.25) }
        // One crank counter tick landing late must not arrive at the legs as a
        // notch in the resistance.
        d.update(cadenceRpm = 60, dtSeconds = 0.25)
        assertTrue(d.cadenceRpm > 70.0, "one low sample dragged cadence to ${d.cadenceRpm}")
        assertTrue(d.cadenceRpm < 90.0, "the sample was ignored entirely")
    }

    @Test
    fun `a long gap re-anchors instead of filtering across it`() {
        val d = DriveSpeed()
        repeat(40) { d.update(cadenceRpm = 100, dtSeconds = 0.25) }
        d.update(cadenceRpm = 60, dtSeconds = 30.0)
        assertEquals(60.0, d.cadenceRpm, 1e-9)
    }

    @Test
    fun `a rider who stops pedalling is turning nothing out`() {
        val d = DriveSpeed()
        repeat(40) { d.update(cadenceRpm = 90, dtSeconds = 0.25) }
        repeat(40) { d.update(cadenceRpm = 0, dtSeconds = 0.25) }
        // Not the fallback: once cadence is known, a reported zero is a rider
        // freewheeling, and their drivetrain really is delivering nothing.
        val coasting = d.speedMps(developmentMeters = 6.0, fallbackMps = 15.0)
        assertTrue(coasting < 0.05, "coasting still turned out $coasting m/s")
    }

    /**
     * The complaint this rule exists for: a rider who stops and starts again
     * should see their speed at once, not creep back over a second while a
     * filter eases up from a standstill they are no longer at.
     */
    @Test
    fun `pulling away again is instant, not filtered up from zero`() {
        val d = DriveSpeed()
        repeat(40) { d.update(cadenceRpm = 90, dtSeconds = 0.25) }
        repeat(20) { d.update(cadenceRpm = 0, dtSeconds = 0.25) }
        assertTrue(d.cadenceRpm < DriveSpeed.STOPPED_RPM, "not stopped: ${d.cadenceRpm}")

        d.update(cadenceRpm = 80, dtSeconds = 0.25)
        assertEquals(80.0, d.cadenceRpm, 1e-9)
    }

    @Test
    fun `a cadence that merely dips is still smoothed`() {
        val d = DriveSpeed()
        repeat(40) { d.update(cadenceRpm = 90, dtSeconds = 0.25) }
        // Slowing to a crawl is not stopping, so this one is filtered.
        d.update(cadenceRpm = 30, dtSeconds = 0.25)
        assertTrue(d.cadenceRpm > 40.0, "one dip dragged cadence to ${d.cadenceRpm}")
    }

    @Test
    fun `absurd input cannot command a road nobody could ride`() {
        val d = DriveSpeed()
        d.update(cadenceRpm = 5_000, dtSeconds = 0.25)
        assertTrue(d.cadenceRpm <= DriveSpeed.MAX_CADENCE_RPM.toDouble())
        assertEquals(
            DriveSpeed.MAX_DRIVE_SPEED_MPS,
            d.speedMps(developmentMeters = 9.6, fallbackMps = 0.0),
        )
    }
}
