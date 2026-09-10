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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.indoorbike.LocalAppContainer
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.SlateMotion
import app.indoorbike.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Transient in-app banner shown when a paired device (re)connects. Driven by the
 * three managers' `justConnected` flows so the rider gets confirmation the app
 * found their trainer / HRM / controller — including silent auto-reconnects
 * after the device wakes from sleep.
 *
 * Place as an overlay in the app's root Box; it aligns itself to the top.
 */
@Composable
fun ConnectionToastHost(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        merge(
            container.trainerManager.justConnected.map { "Trainer connected · $it" },
            container.heartRateMonitor.justConnected.map { "Heart rate monitor connected" },
            container.controllerManager.justConnected.map { "Controller connected" },
        ).collect { msg ->
            text = msg
            visible = true
            delay(3_000)
            visible = false
        }
    }

    // Drops in on a soft spring - a small overshoot past its resting place and
    // back - so it lands like something set down rather than something that
    // slid in on rails. Leaves the way it came, quicker and without the bounce.
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            initialOffsetY = { -it },
        ) + fadeIn(tween(SlateMotion.Base, easing = SlateMotion.Easing)),
        exit = slideOutVertically(
            animationSpec = tween(SlateMotion.Base, easing = SlateMotion.Easing),
            targetOffsetY = { -it },
        ) + fadeOut(tween(SlateMotion.Fast, easing = SlateMotion.Easing)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = Spacing.s4, vertical = Spacing.s3)
                .clip(CircleShape)
                .background(CadenceColors.Bg1)
                .padding(horizontal = Spacing.s5, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            Icon(
                imageVector = Icons.Outlined.BluetoothConnected,
                contentDescription = null,
                tint = CadenceColors.Success,
                modifier = Modifier.size(20.dp),
            )
            Text(text = text, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
        }
    }
}
