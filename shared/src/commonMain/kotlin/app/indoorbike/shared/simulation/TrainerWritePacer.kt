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

/**
 * How often the trainer's control point may be written, whatever is writing it.
 *
 * The GATT queue is the scarce resource in this whole design: a control-point
 * write shares it with the telemetry notifications, and flooding it is what
 * makes cadence drop out. Every writer to one trainer shares one pacer, so a
 * road and a power target cannot between them write twice inside the floor.
 *
 * Three rules, and they are the same for every mode:
 *
 *  - Routine writes go no more often than [MIN_SEND_INTERVAL_MS].
 *  - A write the rider caused (a shift, a new target) has its own, much shorter
 *    floor, [MIN_SHIFT_INTERVAL_MS]. A shifter that lags a fifth of a second
 *    feels broken.
 *  - A forced write refused inside its floor is remembered, not dropped. Only
 *    the latest state matters, so holding the shift button coalesces into one
 *    write rather than flooding the control point or losing shifts.
 *
 * Not thread-safe: its owner confines every call to one thread.
 */
class TrainerWritePacer {
    private var lastWriteMs: Long = 0
    private var owed = false

    /** A forced write was refused inside its floor and has not been made up yet. */
    val isOwed: Boolean get() = owed

    /**
     * Whether a write may go at [nowMs]. [forced] is a write the rider caused,
     * which gets the shorter floor; a forced write that is refused is recorded
     * as owed, and an owed write is forced from then on.
     */
    fun mayWrite(nowMs: Long, forced: Boolean): Boolean {
        val mustSend = forced || owed
        val floorMs = if (mustSend) MIN_SHIFT_INTERVAL_MS else MIN_SEND_INTERVAL_MS
        if (nowMs - lastWriteMs < floorMs) {
            if (mustSend) owed = true
            return false
        }
        return true
    }

    /** True when a write at [nowMs] is forced, owed, or due for its restatement. */
    fun mustRestate(nowMs: Long, forced: Boolean): Boolean =
        forced || owed || nowMs - lastWriteMs >= REFRESH_INTERVAL_MS

    /** How long an owed write has to wait for its floor, from [nowMs]. */
    fun msUntilOwedMayGo(nowMs: Long): Long =
        (MIN_SHIFT_INTERVAL_MS - (nowMs - lastWriteMs)).coerceAtLeast(0)

    /** A write went out at [nowMs]. */
    fun wrote(nowMs: Long) {
        lastWriteMs = nowMs
        owed = false
    }

    /**
     * Forget what is owed, and let the next write go at once. Called when a
     * ride starts or stops: nothing is owed to a ride that is not running.
     */
    fun reset() {
        owed = false
        lastWriteMs = 0
    }

    companion object {
        /**
         * Floor on the routine traffic the 4 Hz loop generates, so gradient
         * changes cannot saturate the GATT queue.
         */
        const val MIN_SEND_INTERVAL_MS = 200L

        /**
         * Floor on a write the rider caused, which jumps that queue.
         *
         * On a descent the force is changing every tick, so routine sends run
         * at the floor above continuously and a shift arriving between them
         * used to wait for the next slot: a fifth of a second before the write
         * even started, on top of the BLE round trip. A shift is a deliberate
         * act a human performs a few times a second at most, so it gets its own,
         * far shorter floor: long enough to coalesce a double-tap, short enough
         * to be indistinguishable from immediate.
         */
        const val MIN_SHIFT_INTERVAL_MS = 60L

        /**
         * Restate an unchanged road at least this often, because a trainer
         * that dropped and reconnected has forgotten it.
         */
        const val REFRESH_INTERVAL_MS = 5_000L
    }
}
