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

/**
 * One FTMS Indoor Bike Data (0x2AD2) notification: what a fitness machine
 * says about the rider, in the units this app uses.
 *
 * On a trainer that also has a Cycling Power service this is the second
 * opinion. On a smart bike or a spin bike that has only FTMS it is the only
 * one, and it carries everything: power, cadence, and often a heart rate from
 * the grips or a strap the bike itself is paired with.
 *
 * Every field is optional, because the flags decide what a packet carries and
 * a machine may split one reading across two packets ("More Data"). A null is
 * "not in this packet", never zero.
 */
data class IndoorBikeData(
    /** Instantaneous power in watts. Never negative: see [parse]. */
    val powerWatts: Int? = null,
    /** Instantaneous cadence in rpm. */
    val cadenceRpm: Int? = null,
    /** Heart rate in bpm, as the machine measures it. */
    val heartRateBpm: Int? = null,
) {
    companion object {
        /**
         * Walk the fields the flags say are present, in the order the
         * specification lays them out, all little-endian:
         *
         * | flag bit | field                  | bytes | unit            |
         * |----------|------------------------|-------|-----------------|
         * | 0 clear  | instantaneous speed    | 2     | 0.01 km/h       |
         * | 1        | average speed          | 2     |                 |
         * | 2        | instantaneous cadence  | 2     | 0.5 rpm         |
         * | 3        | average cadence        | 2     |                 |
         * | 4        | total distance         | 3     |                 |
         * | 5        | resistance level       | 2     |                 |
         * | 6        | instantaneous power    | 2     | W, signed       |
         * | 7        | average power          | 2     |                 |
         * | 8        | expended energy        | 5     |                 |
         * | 9        | heart rate             | 1     | bpm             |
         *
         * Bit 0 is inverted: "More Data" set means speed is *absent*. Fields
         * after heart rate (metabolic equivalent, elapsed and remaining time)
         * are never needed, so the walk stops there.
         *
         * A truncated packet yields whatever was complete before the cut
         * rather than nothing: a machine that sends power and then runs out of
         * bytes has still told us its power.
         *
         * Power is clamped at zero. Several machines report a few negative
         * watts while the rider coasts or the brake settles, which is the
         * sensor, not a rider pedalling backwards, and a negative sample would
         * pull every average and the virtual speed below what was ridden.
         */
        fun parse(bytes: ByteArray): IndoorBikeData? {
            if (bytes.size < 2) return null
            val flags = u16(bytes, 0)
            var i = 2

            fun has(bit: Int) = (flags and (1 shl bit)) != 0
            fun fits(size: Int) = i + size <= bytes.size

            if (!has(0)) i += 2 // instantaneous speed
            if (has(1)) i += 2 // average speed
            var cadence: Int? = null
            if (has(2)) {
                if (!fits(2)) return IndoorBikeData()
                cadence = u16(bytes, i) / 2
                i += 2
            }
            if (has(3)) i += 2 // average cadence
            if (has(4)) i += 3 // total distance
            if (has(5)) i += 2 // resistance level
            var power: Int? = null
            if (has(6)) {
                if (!fits(2)) return IndoorBikeData(cadenceRpm = cadence)
                power = s16(bytes, i).coerceAtLeast(0)
                i += 2
            }
            if (has(7)) i += 2 // average power
            if (has(8)) i += 5 // expended energy: total, per hour, per minute
            var heartRate: Int? = null
            if (has(9) && fits(1)) {
                // Zero is how a machine with nobody holding the grips says it
                // has no reading, not a rider whose heart has stopped.
                heartRate = (bytes[i].toInt() and 0xFF).takeIf { it > 0 }
            }
            return IndoorBikeData(powerWatts = power, cadenceRpm = cadence, heartRateBpm = heartRate)
        }

        private fun u16(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun s16(b: ByteArray, at: Int): Int = u16(b, at).toShort().toInt()
    }
}
