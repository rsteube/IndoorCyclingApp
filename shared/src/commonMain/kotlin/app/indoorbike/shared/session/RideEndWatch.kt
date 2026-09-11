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
package app.indoorbike.shared.session

/**
 * The moment a ride ends, picked out of the stream of states it passes through.
 *
 * ## Why this is not just `state == Completed`
 *
 * [WorkoutState.Completed] is not an event. It is where the session *sits*,
 * from the moment a ride is saved until the next one is prepared: all the time
 * the rider spends reading their summary, and all the time they spend on any
 * screen they visit afterwards. Something that reacts to the value rather than
 * to the arrival at it will fire again on every one of those screens.
 *
 * That is not hypothetical. The companion socket closed itself on this: opening
 * the pairing screen after a ride started a watch that immediately saw the last
 * ride's `Completed` and shut the socket it had just opened. Nothing logged a
 * failure and nothing crashed. The second ride of an evening simply could not
 * be started, and the only symptom was a pairing card with no address on it.
 *
 * So: an ending is a *transition* into `Completed`, from a session this watch
 * saw running. A `Completed` it was handed before it ever saw a ride is somebody
 * else's ending, already dealt with, and is ignored.
 *
 * Not thread-safe, and does not need to be: a watch belongs to one collector.
 */
class RideEndWatch {
    private var rideSeen = false

    /**
     * Feed it each state the session takes. True exactly once per ride, on the
     * transition into [WorkoutState.Completed].
     */
    fun ended(state: WorkoutState): Boolean {
        if (state != WorkoutState.Completed) {
            rideSeen = true
            return false
        }
        if (!rideSeen) return false
        // Disarm, so a repeat of the same state cannot end the same ride twice.
        // A conflated flow will not deliver one, but the caller's reaction is
        // usually to tear something down, and doing that twice is how a
        // teardown finds the half-built thing that replaced it.
        rideSeen = false
        return true
    }
}
