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
package app.indoorbike.shared.companion

/**
 * The six presses a rider can send back to a companion.
 *
 * Deliberately a d-pad and two verbs rather than anything richer. A companion
 * showing a menu on a television across the room needs the rider to move a
 * selection and commit to it; a rider on a trainer has one hand, a phone on the
 * bars, and no attention to spare. Anything more expressive than this would be
 * a remote control for a game the app cannot see, and the app has no business
 * pretending it knows what is on that screen.
 *
 * The names are the protocol. A companion enabling a control this app does not
 * recognise gets a warning rather than a silently missing button, because the
 * failure it prevents is an implementer wondering why their Enter never
 * appeared.
 */
object CompanionControl {
    const val UP = "up"
    const val DOWN = "down"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val ENTER = "enter"
    const val BACK = "back"

    /** Every control the protocol defines, in the order a pad lays them out. */
    val ALL: List<String> = listOf(UP, DOWN, LEFT, RIGHT, ENTER, BACK)

    fun isKnown(name: String): Boolean = name in ALL
}

/**
 * A controllable screen the companion is showing, and what will answer on it.
 *
 * ## Why the companion has to say which controls are live
 *
 * The app is driving a screen it cannot see. It has no idea whether the thing
 * currently up is a list with four items, a yes/no dialog, or a loading state
 * that ignores everything. Drawing all six buttons and hoping would produce the
 * one failure this whole exchange exists to avoid: a rider pressing a button
 * that looks pressable, nothing happening, and no way to tell whether the press
 * was lost or the game simply did not care.
 *
 * So [enabled] is the contract. The app draws the pad, and every control not in
 * that set is visibly dead rather than quietly inert. A companion that changes
 * what is selectable sends a new frame; they are cheap and the last one wins.
 *
 * ## Why this is not on the ride
 *
 * Nothing here reaches the trainer, the physics or the ride record. It is a
 * remote control that happens to share a socket with a bicycle, and keeping it
 * on its own state rather than folding it into the engine is what stops a menu
 * being able to affect a road.
 */
data class RemoteControls(
    /** What the screen is, e.g. "Choose a route". Shown above the pad. */
    val title: String? = null,
    /** One line of help, e.g. "Pick a lap count". Shown under the title. */
    val hint: String? = null,
    /** Which of [CompanionControl.ALL] will actually answer. */
    val enabled: Set<String> = emptySet(),
) {
    fun isEnabled(control: String): Boolean = control in enabled

    /** True when there is at least one thing to press. */
    val hasAnyEnabled: Boolean get() = enabled.isNotEmpty()
}
