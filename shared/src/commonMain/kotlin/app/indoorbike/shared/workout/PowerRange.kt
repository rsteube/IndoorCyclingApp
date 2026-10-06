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
package app.indoorbike.shared.workout

import app.indoorbike.shared.PowerZone
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A segment's power range: "anywhere from 90 to 105% of FTP, your call".
 *
 * ERG holds one wattage, so a range cannot be handed to the trainer as it
 * stands. What it means here is that the trainer starts at the segment's
 * [PowerZone.powerPercentFTP] and the rider's ± taps move the target as they
 * always do, but never out of the range. The same taps on a segment without a
 * range are only held to [MAX_BIAS_PERCENT].
 *
 * The start doubles as the fallback: apps from before ranges existed ignore
 * the two bounds and ride the start as a constant, which is why a range needs
 * no schema version of its own.
 */
data class PowerRange(
    val minPercent: Int,
    val maxPercent: Int,
) {
    init {
        require(minPercent < maxPercent) { "power range min ($minPercent) must be below max ($maxPercent)" }
    }

    /** "90-105", in % of FTP. */
    val label: String get() = "$minPercent-$maxPercent"

    companion object {
        /** The same limits a segment's own power has. */
        val VALID_PERCENT = 0..300

        /**
         * Build a range from the raw pair, or null when the segment doesn't
         * have one.
         *
         * Forgiving the way [CadenceTarget.of] is, because these values come
         * from hand-edited JSON and links as much as from the editor: a
         * reversed pair is read as the range the author meant. Unlike cadence,
         * one bound alone is not a range, and neither is a pair of equal
         * bounds: both are a constant segment, which the start already says.
         */
        fun of(minPercent: Int?, maxPercent: Int?): PowerRange? {
            val low = minPercent?.takeIf { it in VALID_PERCENT } ?: return null
            val high = maxPercent?.takeIf { it in VALID_PERCENT } ?: return null
            if (low == high) return null
            return PowerRange(minOf(low, high), maxOf(low, high))
        }
    }
}

/** How far the ± taps can move a workout's targets, either way, in percent. */
const val MAX_BIAS_PERCENT = 30

/** This segment's power range, or null when it asks for one constant power. */
val PowerZone.powerRange: PowerRange?
    get() = PowerRange.of(powerMinPercentFTP, powerMaxPercentFTP)

/**
 * Where the trainer starts on this segment, in % of FTP. The segment's own
 * power, pulled into its range should an author have put it outside.
 */
val PowerZone.startPercent: Int
    get() = powerRange?.let { powerPercentFTP.coerceIn(it.minPercent, it.maxPercent) } ?: powerPercentFTP

/**
 * The target this segment asks for once the rider's taps are applied, in % of
 * FTP. [biasPercent] scales the start; a range then has the last word.
 */
fun PowerZone.targetPercent(biasPercent: Int): Double {
    val biased = startPercent * (1.0 + biasPercent / 100.0)
    val range = powerRange ?: return biased
    return biased.coerceIn(range.minPercent.toDouble(), range.maxPercent.toDouble())
}

/** [targetPercent] as the wattage the trainer is sent, for a rider whose FTP is [ftp]. */
fun PowerZone.targetWatts(ftp: Int, biasPercent: Int): Int =
    (targetPercent(biasPercent) / 100.0 * ftp).roundToInt().coerceAtLeast(0)

/**
 * The bias after a tap of [deltaPercent] during this segment.
 *
 * A range narrows what the taps can reach to the biases that keep the target
 * inside it. The bias carried in from earlier segments is pulled into that
 * window first, so the very first tap moves the target; otherwise a rider who
 * arrived at +20% on a range topping out at +10% would tap down twice before
 * anything changed. The bias still carries on to the segments after, as it
 * always has.
 *
 * A null [segment] (nothing loaded, or the workout is over) is held to
 * [MAX_BIAS_PERCENT] alone.
 */
fun nextBiasPercent(segment: PowerZone?, currentBias: Int, deltaPercent: Int): Int {
    val window = segment?.biasWindow() ?: (-MAX_BIAS_PERCENT..MAX_BIAS_PERCENT)
    return (currentBias.coerceIn(window) + deltaPercent).coerceIn(window)
}

/**
 * The biases that keep this segment's target inside its range, within
 * [MAX_BIAS_PERCENT]. Always contains 0, since the start is inside the range.
 */
private fun PowerZone.biasWindow(): IntRange {
    val all = -MAX_BIAS_PERCENT..MAX_BIAS_PERCENT
    val range = powerRange ?: return all
    val start = startPercent
    // A start of 0 has no bias that moves it, so there is nothing to narrow.
    if (start <= 0) return all
    val low = ceil((range.minPercent.toDouble() / start - 1.0) * 100.0 - EPSILON).toInt()
    val high = floor((range.maxPercent.toDouble() / start - 1.0) * 100.0 + EPSILON).toInt()
    return maxOf(low, all.first)..minOf(high, all.last)
}

/**
 * Slack for the window's arithmetic: 105 / 100 - 1 is not exactly 0.05 in
 * floating point, and +5% on a 100% start must still reach 105.
 */
private const val EPSILON = 1e-9
