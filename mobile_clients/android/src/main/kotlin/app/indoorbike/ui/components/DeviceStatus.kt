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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.ble.trainerTitle
import app.indoorbike.shared.ble.trainerStatusLine
import app.indoorbike.shared.ble.hrmTitle
import app.indoorbike.shared.ble.hrmStatusLine
import app.indoorbike.shared.ble.controllerTitle
import app.indoorbike.shared.ble.controllerStatusLine
import app.indoorbike.shared.ble.StatusTone
import app.indoorbike.shared.ble.trainerStatusTone
import app.indoorbike.shared.ble.hrmStatusTone
import app.indoorbike.shared.ble.controllerStatusTone
import app.indoorbike.shared.ble.ControlPath
import app.indoorbike.shared.ble.ControllerConnectionState
import app.indoorbike.shared.ble.HrmConnectionState
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.ble.TrainerControl
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.SlateMotion
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.theme.rememberReducedMotion
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The trainer, heart rate monitor and controller, with what each one is
 * actually doing.
 *
 * Lifted out of the Settings tab unchanged when the home screen came to need
 * the same thing. Two copies would have drifted, and these status lines are
 * where the awkward truths live: a heart rate strap can be connected and
 * sending nothing, and a controller can pair, report itself connected, and
 * answer no button press. Both of those read as "Connected" to anything that
 * only looks at the connection state, which is exactly the case a rider needs
 * warning about before they start pedalling.
 */

// ─── Devices card + rows ──────────────────────────────────────────────────────

@Composable
fun DeviceStatusCard(
    onTrainerClick: () -> Unit,
    onHrmClick: () -> Unit,
    onControllerClick: () -> Unit,
    showChevrons: Boolean = false,
) {
    val container = LocalAppContainer.current
    val trainerState by container.trainerManager.connectionState.collectAsStateWithLifecycle()
    val trainerName by container.trainerManager.connectedTrainerName.collectAsStateWithLifecycle()
    val isMockTrainer by container.trainerManager.isMockTrainer.collectAsStateWithLifecycle()
    val hrmState by container.heartRateMonitor.connectionState.collectAsStateWithLifecycle()
    val hrmName by container.heartRateMonitor.connectedDeviceName.collectAsStateWithLifecycle()
    val hrmHeartRate by container.heartRateMonitor.heartRate.collectAsStateWithLifecycle()
    val hrmSilent by container.heartRateMonitor.sensorSilent.collectAsStateWithLifecycle()
    val controllerState by container.controllerManager.connectionState.collectAsStateWithLifecycle()
    val controllerName by container.controllerManager.connectedDeviceName.collectAsStateWithLifecycle()
    val controllerSilent by container.controllerManager.keypadSilent.collectAsStateWithLifecycle()

    ListCard {
        DeviceRow(
            icon = Icons.Outlined.Bluetooth,
            title = trainerTitle(trainerState, trainerName, isMockTrainer),
            statusLine = trainerStatusLine(trainerState, isMockTrainer),
            statusColor = trainerStatusColor(trainerState),
            showChevron = showChevrons,
            onClick = onTrainerClick
        )
        CardDivider()
        DeviceRow(
            icon = Icons.Outlined.MonitorHeart,
            title = hrmTitle(hrmState, hrmName),
            statusLine = hrmStatusLine(hrmState, hrmHeartRate, hrmSilent),
            statusColor = hrmStatusColor(hrmState, hrmSilent),
            showChevron = showChevrons,
            onClick = onHrmClick
        )
        CardDivider()
        DeviceRow(
            icon = Icons.Outlined.SettingsRemote,
            title = controllerTitle(controllerState, controllerName),
            statusLine = controllerStatusLine(controllerState, controllerSilent),
            statusColor = controllerStatusColor(controllerState, controllerSilent),
            showChevron = showChevrons,
            onClick = onControllerClick
        )
    }
}

/**
 * One device. The leading glyph is a plain Fg2 icon with no well behind it: in
 * a monochrome system a tinted tile around every icon is three more surfaces
 * per card doing no work. What carries the meaning is the dot on the right.
 */
@Composable
private fun DeviceRow(
    icon: ImageVector,
    title: String,
    statusLine: String,
    statusColor: Color,
    showChevron: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s5, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CadenceColors.Fg2,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = statusLine, style = CadenceType.small, color = CadenceColors.Fg3)
        }
        StatusDot(color = statusColor)
        if (showChevron) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = CadenceColors.Fg3,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * The 8 dp dot that carries a device state. It crossfades between colours
 * rather than snapping, and while it is amber - scanning, connecting - it
 * breathes, because those are the states in which something is happening
 * that the rider is waiting on. Green and grey hold still: nothing to wait for.
 */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    val shown by animateColorAsState(
        targetValue = color,
        animationSpec = tween(SlateMotion.Slow, easing = SlateMotion.Easing),
        label = "statusDotColor",
    )
    val busy = color == CadenceColors.Warning && !rememberReducedMotion()
    val breath = rememberInfiniteTransition(label = "statusDotBreath")
    val pulse by breath.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = SlateMotion.Easing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "statusDotPulse",
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .graphicsLayer {
                if (busy) {
                    alpha = pulse
                    val s = 0.85f + 0.3f * pulse
                    scaleX = s
                    scaleY = s
                }
            }
            .clip(CircleShape)
            .background(shown)
    )
}

// ─── State → label helpers ────────────────────────────────────────────────────
//
// The words are shared with the iOS app (shared/ble/DeviceStatusText.kt), so a
// device reads the same on either phone. The colours are this app's.

private fun StatusTone.color(): Color = when (this) {
    StatusTone.Good -> CadenceColors.Success
    StatusTone.Busy -> CadenceColors.Warning
    StatusTone.Bad -> CadenceColors.Danger
    StatusTone.Idle -> CadenceColors.Fg4
}

internal fun trainerStatusColor(state: TrainerConnectionState): Color = trainerStatusTone(state).color()

internal fun hrmStatusColor(state: HrmConnectionState, sensorSilent: Boolean): Color =
    hrmStatusTone(state, sensorSilent).color()

internal fun controllerStatusColor(state: ControllerConnectionState, keypadSilent: Boolean): Color =
    controllerStatusTone(state, keypadSilent).color()
