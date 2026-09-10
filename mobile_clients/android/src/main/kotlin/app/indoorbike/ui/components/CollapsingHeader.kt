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
package app.indoorbike.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * A title block that gets out of the way once the rider starts scrolling.
 *
 * A 28 sp title over a section label is the right thing to land on and the
 * wrong thing to keep: on the workout and route lists it costs a row and a half
 * of the list for the whole time you are reading the list. So it behaves the
 * way Android headers are expected to: scrolling up collapses it into the row
 * the back button already occupies, scrolling back to the top brings it back,
 * and a fling that stops halfway settles to whichever end is nearer.
 *
 * This is the same "exit until collapsed" contract as Material's
 * [androidx.compose.material3.TopAppBarScrollBehavior], written out here
 * because the header it drives is the Slate title block, not a Material app
 * bar - the geometry, the eyebrow and the type ramp are all ours, and
 * LargeTopAppBar's fixed 152 dp expanded height is not.
 */
@Stable
class CollapsingHeaderState internal constructor(initialOffset: Float) {

    /** How far the block has scrolled away, in pixels: `-height` … `0`. */
    var offset by mutableFloatStateOf(initialOffset)
        internal set

    /**
     * The collapsible height in pixels - the title block's own, measured once
     * it has been laid out. Zero until then, which reads as "nothing to
     * collapse" and leaves the header static.
     */
    var collapsibleHeight by mutableFloatStateOf(0f)
        internal set

    /** 0 fully open, 1 fully collapsed. */
    val fraction: Float
        get() = if (collapsibleHeight <= 0f) 0f
        else (-offset / collapsibleHeight).coerceIn(0f, 1f)

    internal fun onMeasured(height: Float) {
        if (height != collapsibleHeight) {
            collapsibleHeight = height
            offset = offset.coerceIn(-height, 0f)
        }
    }

    /** Settle to the nearer end after a fling, so it never rests half-open. */
    internal suspend fun settle(spec: AnimationSpec<Float>) {
        val f = fraction
        if (f <= 0f || f >= 1f) return
        val target = if (f < 0.5f) 0f else -collapsibleHeight
        animate(initialValue = offset, targetValue = target, animationSpec = spec) { value, _ ->
            offset = value
        }
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        // Collapsing happens before the list scrolls, so the header goes first
        // and the list only starts moving once the header is out of the way.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val delta = available.y
            if (delta >= 0f || collapsibleHeight <= 0f) return Offset.Zero
            val before = offset
            offset = (offset + delta).coerceAtLeast(-collapsibleHeight)
            return Offset(0f, offset - before)
        }

        // Expanding happens after, so the list scrolls back to its top first and
        // only then does the title come back down. The other order pulls the
        // title out over a list that is still halfway down.
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val delta = available.y
            if (delta <= 0f || collapsibleHeight <= 0f) return Offset.Zero
            val before = offset
            offset = (offset + delta).coerceAtMost(0f)
            return Offset(0f, offset - before)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settle(SettleSpec)
            return Velocity.Zero
        }
    }

    companion object {
        internal val SettleSpec: AnimationSpec<Float> = tween(durationMillis = 200)

        val Saver: Saver<CollapsingHeaderState, Float> = Saver(
            save = { it.offset },
            restore = { CollapsingHeaderState(it) }
        )
    }
}

/**
 * Survives rotation and process death, so a rider who scrolled a list and
 * turned the phone does not get the title block back over their place in it.
 */
@Composable
fun rememberCollapsingHeaderState(): CollapsingHeaderState =
    rememberSaveable(saver = CollapsingHeaderState.Saver) { CollapsingHeaderState(0f) }
