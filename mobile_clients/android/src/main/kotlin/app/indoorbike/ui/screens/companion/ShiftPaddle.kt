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
package app.indoorbike.ui.screens.companion

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.DisplayFontFamily
import app.indoorbike.ui.theme.PressScale
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.SlateMotion
import app.indoorbike.ui.theme.rememberHaptics
import app.indoorbike.ui.theme.rememberReducedMotion

/**
 * One side of the virtual shifter, sized for a thumb that is also holding a
 * handlebar.
 *
 * In Companion mode this is the most important control on the screen. The game
 * owns the road, the rider owns the gears, and a rider without a hardware
 * controller has no other way to change one. Everything else on the HUD is read;
 * this is the thing they reach for, mid-effort, with the screen bouncing on a
 * mount, often without looking.
 *
 * ## Why it fires on touch-down
 *
 * An ordinary click commits when the finger lifts, and cancels if the finger
 * slides first. On a moving bike a thumb lands, skids a few millimetres and
 * lifts, and the shift silently never happens: the one outcome a shifter cannot
 * have. So a shift happens the instant the paddle is touched, exactly as a
 * mechanical lever engages on the push rather than the release. Accessibility
 * services still get an ordinary click action through semantics.
 *
 * ## Why it never disappears
 *
 * At the end of the range the paddle dims but stays where it is. A control that
 * vanished at the top of the cassette would leave a rider's thumb hitting
 * whatever moved into its place.
 */
@Composable
fun ShiftPaddle(
    /** The glyph, "-" or "+". Big enough to read from the saddle. */
    symbol: String,
    /** One word under the glyph, so the direction is never a guess. */
    label: String,
    /** Spoken by accessibility services. */
    description: String,
    enabled: Boolean,
    onShift: () -> Unit,
    modifier: Modifier = Modifier,
    symbolSize: TextUnit = 56.sp,
) {
    val haptics = rememberHaptics()
    val reduceMotion = rememberReducedMotion()
    val currentOnShift by rememberUpdatedState(onShift)
    val currentEnabled by rememberUpdatedState(enabled)
    var pressed by remember { mutableStateOf(false) }

    // A press flashes the paddle to the app's one inverted fill, the same
    // treatment a primary button gets, because a rider who is not looking
    // straight at the screen still catches a large area changing brightness in
    // the corner of their eye.
    val fill by animateColorAsState(
        targetValue = when {
            !enabled -> CadenceColors.Bg1.copy(alpha = 0.45f)
            pressed -> CadenceColors.Fg1
            else -> CadenceColors.Bg1
        },
        animationSpec = tween(if (pressed) 40 else SlateMotion.Fast, easing = SlateMotion.Easing),
        label = "paddleFill",
    )
    val ink = when {
        !enabled -> CadenceColors.Fg4
        pressed -> CadenceColors.OnBrand
        else -> CadenceColors.Fg1
    }
    val shape = RoundedCornerShape(Radii.lg)

    Column(
        modifier = modifier
            .graphicsLayer {
                val scale = if (pressed && !reduceMotion) PressScale.Button else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(fill)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (currentEnabled) {
                        pressed = true
                        haptics.tick()
                        currentOnShift()
                    }
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                if (!enabled) disabled()
                onClick {
                    if (currentEnabled) currentOnShift()
                    currentEnabled
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = symbol,
            fontSize = symbolSize,
            lineHeight = symbolSize,
            fontFamily = DisplayFontFamily,
            color = ink,
            maxLines = 1,
        )
        Text(
            text = label,
            style = CadenceType.eyebrow,
            color = if (enabled && !pressed) CadenceColors.Fg3 else ink,
            maxLines = 1,
        )
    }
}
