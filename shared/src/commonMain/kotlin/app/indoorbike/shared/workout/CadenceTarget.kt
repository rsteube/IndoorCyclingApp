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
package app.indoorbike.shared.workout

import app.indoorbike.shared.PowerZone

/** Which way the rider needs to move to reach the segment's cadence target. */
enum class CadenceCue {
    /** Spinning too slowly - push the legs faster. */
    Below,
    OnTarget,
    /** Spinning too fast - settle down. */
    Above,
}

/**
 * A segment's cadence ask, normalised from the raw [PowerZone] fields into
 * something the UI can render and judge against without re-deriving the rules.
 *
 * [minRpm] and [maxRpm] are the *stated* target. Judging uses [lowBoundRpm] /
 * [highBoundRpm], which widen a single value into a band - see below.
 */
data class CadenceTarget(
    val minRpm: Int,
    val maxRpm: Int,
) {
    init {
        require(minRpm <= maxRpm) { "cadence target min ($minRpm) must not exceed max ($maxRpm)" }
    }

    /** True when the workout asked for one number rather than a range. */
    val isSingleValue: Boolean get() = minRpm == maxRpm

    /**
     * Nobody holds an exact rpm, so a single-value target is judged against a
     * small band around it. Without this the cue would flicker between "spin
     * up" and "ease off" several times a second and the rider would learn to
     * ignore it. A stated range is taken at face value - the author already
     * said how much slack they wanted.
     */
    val lowBoundRpm: Int get() = if (isSingleValue) minRpm - SINGLE_VALUE_TOLERANCE_RPM else minRpm
    val highBoundRpm: Int get() = if (isSingleValue) maxRpm + SINGLE_VALUE_TOLERANCE_RPM else maxRpm

    /** "85-95" for a range, "90" for a single value. */
    val label: String get() = if (isSingleValue) "$minRpm" else "$minRpm-$maxRpm"

    /**
     * Judge [rpm] against this target, or null when there's nothing to judge.
     *
     * A non-positive reading means the rider is coasting or the trainer isn't
     * reporting cadence at all. Neither is a miss worth nagging about, so the
     * caller gets null and shows the target without a verdict.
     */
    fun cueFor(rpm: Int): CadenceCue? = when {
        rpm <= 0 -> null
        rpm < lowBoundRpm -> CadenceCue.Below
        rpm > highBoundRpm -> CadenceCue.Above
        else -> CadenceCue.OnTarget
    }

    /**
     * Where [rpm] sits on a bar drawn across [displayRangeRpm], as 0f..1f.
     * Clamped, so a wildly off reading pins to an end instead of drawing
     * outside the track.
     */
    fun barPositionOf(rpm: Int): Float {
        val span = (displayRangeRpm.last - displayRangeRpm.first).toFloat()
        if (span <= 0f) return 0f
        return ((rpm - displayRangeRpm.first) / span).coerceIn(0f, 1f)
    }

    /**
     * The rpm window the bar spans. Padded well outside the target so the
     * marker still moves visibly when the rider is close, and so the target
     * band never fills the whole track and stops meaning anything.
     */
    val displayRangeRpm: IntRange
        get() {
            val pad = ((highBoundRpm - lowBoundRpm) / 2).coerceAtLeast(BAR_MIN_PADDING_RPM)
            return (lowBoundRpm - pad).coerceAtLeast(0)..(highBoundRpm + pad)
        }

    companion object {
        /** Half-width of the band a single-value target is judged against. */
        const val SINGLE_VALUE_TOLERANCE_RPM = 3

        /** Lower limit on how far the bar extends past the target on each side. */
        const val BAR_MIN_PADDING_RPM = 10

        /** Cadence values outside this are almost certainly a typo or bad data. */
        val VALID_RPM = 20..200

        /**
         * Build a target from the raw pair, or null when the segment doesn't
         * ask for one.
         *
         * Deliberately forgiving about how the pair arrives, because these
         * values come from hand-edited JSON and third-party links as much as
         * from our own editor: either bound alone means a single target, and a
         * reversed pair is read as the range the author obviously meant.
         * Anything outside [VALID_RPM] is treated as absent rather than
         * rendered as a target no human could hit.
         */
        fun of(minRpm: Int?, maxRpm: Int?): CadenceTarget? {
            val low = minRpm?.takeIf { it in VALID_RPM }
            val high = maxRpm?.takeIf { it in VALID_RPM }
            return when {
                low != null && high != null -> CadenceTarget(minOf(low, high), maxOf(low, high))
                low != null -> CadenceTarget(low, low)
                high != null -> CadenceTarget(high, high)
                else -> null
            }
        }
    }
}

/** This segment's cadence ask, or null when it doesn't have one. */
val PowerZone.cadenceTarget: CadenceTarget?
    get() = CadenceTarget.of(cadenceMinRpm, cadenceMaxRpm)
