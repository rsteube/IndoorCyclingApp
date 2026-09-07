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
package app.indoorbike.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * How the app moves.
 *
 * Three durations and one curve for anything that travels: a screen arriving,
 * a header settling, a button letting go. Three springs for anything that is
 * touched or that pops into being, because a finger expects a physical answer
 * and a tween has none. And a slower reveal for the moments the app has
 * something to show off - a finished ride's numbers, a workout's shape - where
 * the point is to let the rider watch it arrive.
 *
 * The curve leaves fast and settles slowly, which is what makes a push feel
 * like it was thrown rather than dragged.
 */
object SlateMotion {
    val Easing: Easing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

    /** Numbers landing: leaves fast, brakes hard, so the last digits settle. */
    val CountEasing: Easing = CubicBezierEasing(0.1f, 0.85f, 0.2f, 1f)

    /** A press, a ripple, something leaving. */
    const val Fast = 120

    /** The default: a screen arriving, a header settling. */
    const val Base = 200

    /** Something large moving a long way. */
    const val Slow = 320

    /** A card or a chart revealing itself on entry. */
    const val Reveal = 380

    /** A headline number counting up to what it is. */
    const val Count = 900

    /** The gap between one list item revealing and the next. */
    const val StaggerStep = 45

    /** Past this many items the stagger stops growing, so a long list does not wait. */
    const val StaggerCap = 6

    /**
     * The answer to a finger: firm, no bounce. Used for the press scale, so a
     * button feels solid rather than rubbery.
     */
    val Snappy: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 1100f,
    )

    /** Something sliding into its place: the tab pill, the switch thumb. */
    val Settle: SpringSpec<Float> = spring(
        dampingRatio = 0.86f,
        stiffness = 420f,
    )

    /** Something arriving that has earned a little overshoot: a badge, a star, a count. */
    val Pop: SpringSpec<Float> = spring(
        dampingRatio = 0.58f,
        stiffness = 480f,
    )
}

/**
 * Whether the device has animations turned off system-wide. Compose has no
 * reduced-motion flag of its own, so this reads the same global the platform
 * animators read. Everything decorative in the app checks it; everything that
 * carries meaning (a value changing) still moves, just instantly.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
}

// ─── Touch ────────────────────────────────────────────────────────────────────

/**
 * Shrinks the element a touch while it is pressed and springs it back on
 * release. The whole app uses one of two depths: [PressScale.Button] for
 * things that are obviously buttons, [PressScale.Card] for cards and rows
 * where a deeper squash would feel like the content itself was giving way.
 */
object PressScale {
    const val Button = 0.965f
    const val Card = 0.985f
}

fun Modifier.pressScale(
    interaction: InteractionSource,
    pressedScale: Float = PressScale.Button,
): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val reduce = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) pressedScale else 1f,
        animationSpec = SlateMotion.Snappy,
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

// ─── Entry ────────────────────────────────────────────────────────────────────

/**
 * Fades and lifts the element into place the first time it is composed, with
 * a stagger by [index] so a column of cards arrives one after the other rather
 * than as a wall. Runs once per composition: scrolling a row back into a lazy
 * list replays it, which reads as the row settling, not as a glitch.
 *
 * Kept short and shallow on purpose. The screen transition already moved the
 * whole screen; this is the contents catching up, not a second entrance.
 */
fun Modifier.enterReveal(
    index: Int = 0,
    distance: Dp = 14.dp,
): Modifier = composed {
    val reduce = rememberReducedMotion()
    val progress = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduce || progress.value >= 1f) return@LaunchedEffect
        val slot = index.coerceIn(0, SlateMotion.StaggerCap)
        if (slot > 0) delay(slot * SlateMotion.StaggerStep.toLong())
        progress.animateTo(1f, tween(SlateMotion.Reveal, easing = SlateMotion.Easing))
    }
    val distancePx = with(LocalDensity.current) { distance.toPx() }
    graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * distancePx
    }
}

/**
 * Scales the element up from small with a little overshoot the first time it
 * appears - for a count pill, a badge, anything that is news.
 */
fun Modifier.enterPop(delayMillis: Int = 0): Modifier = composed {
    val reduce = rememberReducedMotion()
    val scale = remember { Animatable(if (reduce) 1f else 0.6f) }
    LaunchedEffect(Unit) {
        if (reduce || scale.value >= 1f) return@LaunchedEffect
        if (delayMillis > 0) delay(delayMillis.toLong())
        scale.animateTo(1f, SlateMotion.Pop)
    }
    graphicsLayer {
        val s = scale.value
        scaleX = s
        scaleY = s
        alpha = ((s - 0.6f) / 0.4f).coerceIn(0f, 1f)
    }
}

/**
 * A 0 → 1 progress that runs once when [key] changes, for anything that
 * reveals itself over time: a number counting up, a bar growing, a line being
 * drawn. Restarts on a new key, so a card showing a different ride replays.
 * Snaps straight to 1 under reduced motion so the value is simply there.
 */
@Composable
fun rememberEntryProgress(
    key: Any?,
    durationMillis: Int = SlateMotion.Count,
    delayMillis: Int = 0,
    easing: Easing = SlateMotion.CountEasing,
): State<Float> {
    val reduce = rememberReducedMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key, reduce) {
        if (reduce) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        if (delayMillis > 0) delay(delayMillis.toLong())
        progress.animateTo(1f, tween(durationMillis, easing = easing))
    }
    return progress.asState()
}

/**
 * A scale that pops to 1 with overshoot every time [trigger] changes - the
 * star being starred, the count pill's number going up. Idles at 1.
 */
@Composable
fun rememberPopOnChange(trigger: Any?): State<Float> {
    val reduce = rememberReducedMotion()
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        // The first composition is the element appearing, not changing.
        if (first) { first = false; return@LaunchedEffect }
        if (reduce) return@LaunchedEffect
        scale.snapTo(0.72f)
        scale.animateTo(1f, SlateMotion.Pop)
    }
    return scale.asState()
}

// ─── Haptics ──────────────────────────────────────────────────────────────────

/**
 * The two touches the app makes back. A tick for a selection changing - a tab,
 * a chip, a star - and a firmer confirm for committing to something: starting
 * a ride, saving. Both go through the view so the system's own "touch
 * feedback" setting is respected; a rider who turned haptics off feels
 * nothing.
 */
class Haptics internal constructor(private val view: View) {
    fun tick() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) HapticFeedbackConstants.CONTEXT_CLICK
            else HapticFeedbackConstants.KEYBOARD_TAP
        )
    }

    fun confirm() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.KEYBOARD_TAP
        )
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
