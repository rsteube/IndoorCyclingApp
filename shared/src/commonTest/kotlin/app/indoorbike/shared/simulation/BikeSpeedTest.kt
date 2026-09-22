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

import app.indoorbike.shared.physics.VirtualSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BikeSpeedTest {

    private val mass = 84.0

    private fun BikeSpeed.ride(
        seconds: Double,
        power: Double,
        drive: Double?,
        gradient: Double = 0.0,
        crr: Double = VirtualSpeed.CRR,
    ) {
        var t = 0.0
        while (t < seconds) {
            update(power, drive, gradient, crr, mass, 0.25)
            t += 0.25
        }
    }

    @Test
    fun `pulling away, the legs' speed is there on the first turn of the crank`() {
        val b = BikeSpeed()
        b.update(powerWatts = 150.0, driveSpeedMps = 3.0, gradient = 0.0, crr = VirtualSpeed.CRR, totalMassKg = mass, dtSeconds = 0.25)
        assertEquals(3.0, b.mps, absoluteTolerance = 1e-9)
    }

    /**
     * The whole reason this exists. The legs are still at the old cadence when
     * a shift lands, so the drivetrain's speed jumps by the ratio. The bike's
     * does not: it has 84 kg of momentum and a shift adds no energy.
     */
    @Test
    fun `once moving, a shift cannot make the bike's speed jump`() {
        val b = BikeSpeed()
        b.ride(seconds = 90.0, power = 200.0, drive = 8.0)
        val settled = b.mps
        assertTrue(settled > 7.0, "never got going: $settled m/s")

        // An upshift: the same legs now turn the road out 15 % faster.
        b.update(200.0, settled * 1.154, 0.0, VirtualSpeed.CRR, mass, 0.25)
        assertEquals(settled, b.mps, absoluteTolerance = 0.02)
    }

    @Test
    fun `it coasts down when the power stops`() {
        val b = BikeSpeed()
        b.ride(seconds = 90.0, power = 200.0, drive = 8.0)
        val riding = b.mps
        b.ride(seconds = 5.0, power = 0.0, drive = 0.0)
        assertTrue(b.mps < riding, "stopped pedalling and kept ${b.mps} m/s")
    }

    @Test
    fun `without a crank to go by it integrates from a standstill`() {
        val b = BikeSpeed()
        b.update(200.0, null, 0.0, VirtualSpeed.CRR, mass, 0.25)
        assertTrue(b.mps < 1.0, "adopted a speed with no drivetrain to adopt it from")
    }

    @Test
    fun `a rougher surface settles slower for the same watts`() {
        val tarmac = BikeSpeed().also { it.ride(seconds = 120.0, power = 200.0, drive = 3.0) }
        val gravel = BikeSpeed().also { it.ride(seconds = 120.0, power = 200.0, drive = 3.0, crr = 0.012) }
        assertTrue(gravel.mps < tarmac.mps - 0.3, "gravel ${gravel.mps} against tarmac ${tarmac.mps}")
    }
}
