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
package app.indoorbike.navigation

import androidx.navigation3.runtime.NavKey

class Navigator(val state: NavigationState) {
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            // This is a top level route, just switch to it
            state.topLevelRoute = route
        } else {
            state.backStacks[state.topLevelRoute]?.add(route)
        }
    }

    /**
     * Swap the screen on top for another one.
     *
     * The tab's own root is never the one swapped out. A stack whose first
     * entry is not its tab is a stack [goBack] can empty, and an empty one is
     * not a state the display has: it is a crash, from whichever screen the
     * rider happened to press back on.
     */
    fun replace(route: NavKey) {
        val currentStack = state.backStacks[state.topLevelRoute] ?: return
        if (currentStack.size > 1) {
            // Not removeLast(): on a NavBackStack that binds to
            // java.util.List.removeLast(), which Java 21 added and Android only
            // has from API 35. Below that it's a NoSuchMethodError, which took
            // the process down on the one screen that calls this.
            currentStack.removeAt(currentStack.lastIndex)
        }
        currentStack.add(route)
    }

    /**
     * Back, one step, and never off the end of the stack.
     *
     * Tested on the depth rather than on what the top entry is. Those are the
     * same question only while every stack still begins with its own tab, and
     * a stack that had lost its root would answer the second one by popping
     * itself empty.
     */
    fun goBack() {
        val currentStack = state.backStacks[state.topLevelRoute] ?: return
        if (currentStack.size > 1) {
            currentStack.removeAt(currentStack.lastIndex)
        } else {
            // At the base of this tab: the way back is the tab the app opens on.
            state.topLevelRoute = state.startRoute
        }
    }

    fun popToRoot() {
        val currentStack = state.backStacks[state.topLevelRoute] ?: return
        if (currentStack.size > 1) {
            val root = currentStack.first()
            currentStack.clear()
            currentStack.add(root)
        }
    }

    /**
     * Switch to the rides tab, clear any leftover sub-navigation in any tab's
     * stack, then push the ride screen on top. Used by the unfinished-ride
     * banner so "Continue" always lands the user back on the active ride.
     */
    fun continueRide(workoutId: String) {
        // Reset every back-stack to its root so we don't accumulate dead history
        // when the user navigates from e.g. Settings → Continue.
        state.backStacks.values.forEach { stack ->
            if (stack.size > 1) {
                val root = stack.first()
                stack.clear()
                stack.add(root)
            }
        }
        state.topLevelRoute = state.startRoute
        state.backStacks[state.startRoute]?.add(
            app.indoorbike.navigation.RouteRideScreen(workoutId)
        )
    }
}
