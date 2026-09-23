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
package app.indoorbike.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Whether a screen leaving composition is the rider leaving it, as opposed to
 * Android tearing the activity down behind them.
 *
 * The two look the same from inside a composable, and they want opposite
 * things. A companion session is tied to its screen, so leaving the screen
 * ends it; but Android reclaiming a backgrounded activity while the ride runs
 * on in its foreground service is not the rider leaving anything, and ending
 * the session there told the game the ride was over while the rider was still
 * on the bike. A rider can only leave a screen of an activity that is up, or
 * one they are finishing; an activity going away while stopped is Android's
 * doing.
 */
fun Context.leavingForGood(): Boolean {
    val activity = findActivity() ?: return true
    if (activity.isFinishing) return true
    val lifecycle = (activity as? LifecycleOwner)?.lifecycle ?: return true
    return lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
