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

    fun replace(route: NavKey) {
        val currentStack = state.backStacks[state.topLevelRoute] ?: return
        if (currentStack.isNotEmpty()) {
            // Not removeLast(): on a NavBackStack that binds to
            // java.util.List.removeLast(), which Java 21 added and Android only
            // has from API 35. Below that it's a NoSuchMethodError, which took
            // the process down on the one screen that calls this.
            currentStack.removeAt(currentStack.lastIndex)
        }
        currentStack.add(route)
    }

    fun goBack() {
        val currentStack = state.backStacks[state.topLevelRoute] ?: error("Stack for ${state.topLevelRoute} not found")
        val currentRoute = currentStack.last()

        // If we're at the base of the current route, go back to the start route stack.
        if (currentRoute == state.topLevelRoute) {
            state.topLevelRoute = state.startRoute
        } else {
            currentStack.removeLastOrNull()
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
