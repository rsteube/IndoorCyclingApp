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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.companion.CompanionControl
import app.indoorbike.shared.companion.RemoteControls
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.SlateMotion
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.theme.pressScale

/**
 * A remote control for a screen this app cannot see.
 *
 * Appears only while the companion says a controllable screen is up, and
 * carries only the six presses the protocol defines. The arrangement is the one
 * every remote in the world uses, because a rider glancing down mid-effort is
 * not learning a new layout: a cross with the commit in the middle, and the way
 * out underneath it where it cannot be hit by accident.
 *
 * ## Disabled buttons are drawn, not hidden
 *
 * A control the companion has not enabled stays in place and goes visibly dead
 * rather than disappearing. Hiding it would reshuffle the pad every time a menu
 * changed what was selectable, and a rider would be aiming at a moving target
 * while pedalling. Dead-but-present also answers the question a missing button
 * raises: whether the app forgot it or the game does not want it.
 */
@Composable
fun CompanionControlPad(
    controls: RemoteControls,
    onPress: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The companion's accent, for a button while it is pressed. The pad is the
     * game's menu borrowed onto the phone, so its presses light up in the game's
     * colour; which buttons are live and which are dead stays the app's call.
     */
    accent: Color? = null,
    onAccent: Color? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1)
            .padding(Spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        val title = controls.title
        val hint = controls.hint
        if (title != null) {
            Text(text = title, style = CadenceType.h3, color = CadenceColors.Fg1)
        }
        if (hint != null) {
            Text(text = hint, style = CadenceType.small, color = CadenceColors.Fg3)
        }
        if (title != null || hint != null) {
            Spacer(Modifier.height(Spacing.s1))
        }

        PadButton(CompanionControl.UP, Icons.Outlined.KeyboardArrowUp, "Up", controls, onPress, accent = accent, onAccent = onAccent)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            PadButton(
                CompanionControl.LEFT, Icons.Outlined.KeyboardArrowLeft, "Left", controls, onPress,
                accent = accent, onAccent = onAccent,
            )
            PadButton(
                CompanionControl.ENTER, Icons.Outlined.Check, "Select", controls, onPress,
                accent = accent, onAccent = onAccent,
            )
            PadButton(
                CompanionControl.RIGHT, Icons.Outlined.KeyboardArrowRight, "Right",
                controls, onPress, accent = accent, onAccent = onAccent,
            )
        }
        PadButton(
            CompanionControl.DOWN, Icons.Outlined.KeyboardArrowDown, "Down", controls, onPress,
            accent = accent, onAccent = onAccent,
        )

        Spacer(Modifier.height(Spacing.s1))
        PadButton(
            control = CompanionControl.BACK,
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            label = "Back",
            controls = controls,
            onPress = onPress,
            // As wide as the cross above it, so the pad reads as one block.
            width = BUTTON * 3 + Spacing.s2 * 2,
            accent = accent,
            onAccent = onAccent,
        )
    }
}

@Composable
private fun PadButton(
    control: String,
    icon: ImageVector,
    label: String,
    controls: RemoteControls,
    onPress: (String) -> Unit,
    width: Dp = BUTTON,
    accent: Color? = null,
    onAccent: Color? = null,
) {
    val enabled = controls.isEnabled(control)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = when {
            !enabled -> CadenceColors.Bg0
            pressed -> accent ?: CadenceColors.Bg3
            else -> CadenceColors.Bg2
        },
        animationSpec = tween(SlateMotion.Fast, easing = SlateMotion.Easing),
        label = "padButtonBg",
    )
    val tint: Color = when {
        !enabled -> CadenceColors.Fg4
        pressed && accent != null -> onAccent ?: CadenceColors.Fg1
        else -> CadenceColors.Fg1
    }

    Box(
        modifier = Modifier
            .size(width = width, height = BUTTON)
            .pressScale(interaction)
            .clip(RoundedCornerShape(Radii.md))
            .background(background)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = { onPress(control) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            // Null when dead: a screen reader announcing six buttons the rider
            // cannot press is worse than announcing the two they can.
            contentDescription = if (enabled) label else null,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** Big enough for a thumb aimed at while pedalling, which is bigger than usual. */
private val BUTTON = 60.dp
