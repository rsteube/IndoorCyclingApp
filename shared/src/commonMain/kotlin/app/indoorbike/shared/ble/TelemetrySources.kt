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
 * Which of a trainer's reports to believe, reading by reading.
 *
 * A trainer can say the same thing twice. A Kickr sends power in both Cycling
 * Power Measurement and FTMS Indoor Bike Data; a smart bike may send only the
 * second; a trainer with a heart-rate relay sends heart rate both in its own
 * service and inside Indoor Bike Data. The dedicated service is preferred
 * wherever it is actually speaking, and Indoor Bike Data fills in for it
 * wherever it is not.
 *
 * "Actually speaking" is judged by when it last spoke, not by whether it
 * exists or by what it said. The rule this replaced took Indoor Bike Data's
 * power only while the current value was zero, which on a machine with no
 * Cycling Power service meant the first real reading was also the last: every
 * later one was refused because the value was no longer zero, and the ride
 * recorded 150 W for an hour. And on a Kickr it let a lagging Indoor Bike Data
 * value in at every moment Cycling Power honestly reported zero.
 *
 * Not synchronised: each field is one timestamp written by one characteristic's
 * callback, and a reading judged against one a few milliseconds stale is
 * judged correctly anyway.
 */
class TelemetrySources(private val staleAfterMs: Long = STALE_AFTER_MS) {

    private var powerServiceAtMs = NEVER
    private var crankAtMs = NEVER
    private var heartRateServiceAtMs = NEVER

    /** A new connection: nothing has spoken yet. */
    fun reset() {
        powerServiceAtMs = NEVER
        crankAtMs = NEVER
        heartRateServiceAtMs = NEVER
    }

    /** Cycling Power Measurement arrived, carrying crank data or not. */
    fun heardPowerService(nowMs: Long, carriesCrank: Boolean) {
        powerServiceAtMs = nowMs
        if (carriesCrank) crankAtMs = nowMs
    }

    /** Heart Rate Measurement arrived from the trainer's own heart-rate service. */
    fun heardHeartRateService(nowMs: Long) {
        heartRateServiceAtMs = nowMs
    }

    /** Whether Indoor Bike Data's power is the one to believe at [nowMs]. */
    fun bikeDataPowerCounts(nowMs: Long) = nowMs - powerServiceAtMs > staleAfterMs

    /**
     * Whether Indoor Bike Data's cadence is the one to believe at [nowMs].
     *
     * Judged by crank data rather than by Cycling Power as a whole: a power
     * meter with no crank sensor speaks constantly and still knows nothing
     * about cadence.
     */
    fun bikeDataCadenceCounts(nowMs: Long) = nowMs - crankAtMs > staleAfterMs

    /** Whether Indoor Bike Data's heart rate is the one to believe at [nowMs]. */
    fun bikeDataHeartRateCounts(nowMs: Long) = nowMs - heartRateServiceAtMs > staleAfterMs

    companion object {
        /**
         * Silence after which a dedicated service counts as gone. Longer than
         * the gaps a trainer leaves when it is busy with a control-point
         * write, which are a notification or two, and short enough that a
         * machine whose power service stops speaking is back on screen before
         * the ride would auto-pause.
         */
        const val STALE_AFTER_MS = 2_500L

        private const val NEVER = Long.MIN_VALUE / 2
    }
}
