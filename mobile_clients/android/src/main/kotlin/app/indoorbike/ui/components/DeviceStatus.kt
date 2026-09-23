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

internal fun trainerTitle(
    state: TrainerConnectionState,
    name: String?,
    isMock: Boolean,
): String = when {
    state == TrainerConnectionState.Connected && name != null -> name
    isMock -> "Mock trainer"
    else -> "Trainer"
}

internal fun trainerStatusLine(state: TrainerConnectionState, isMock: Boolean): String = when (state) {
    TrainerConnectionState.Scanning -> "Scanning…"
    TrainerConnectionState.Connecting -> "Connecting…"
    TrainerConnectionState.Connected -> if (isMock) "Mock connected" else "Connected"
    TrainerConnectionState.Disconnecting -> "Disconnecting…"
    TrainerConnectionState.Disconnected -> "Not paired"
    TrainerConnectionState.Error -> "Connection error · tap to retry"
}

/**
 * The trainer sheet's explanation: what kinds of machine pair, until one has,
 * and then how the app is driving the one that did.
 *
 * Worth saying because the machines differ in ways a rider feels. A bike that
 * only takes a resistance level is steered onto a wattage by measuring it,
 * which takes a few seconds after each change, and a rider who does not know
 * that reads it as the app being slow.
 */
internal fun trainerHelper(state: TrainerConnectionState, isMock: Boolean, control: TrainerControl): String {
    if (state != TrainerConnectionState.Connected || isMock) {
        return "Smart trainers and smart bikes using FTMS or Cycling Power Service. " +
            "Wahoo Kickr, Tacx Neo, Saris, Elite, etc."
    }
    return when {
        control.power == ControlPath.NATIVE && control.road == ControlPath.NATIVE ->
            "Workouts hold you at their target wattage, and routes set the resistance of the road."
        control.power == ControlPath.NATIVE && control.road == ControlPath.VIA_POWER ->
            "This trainer can't simulate a road, so routes hold you at the wattage the road " +
                "asks for at your cadence. Workouts use its own ERG."
        control.power == ControlPath.VIA_RESISTANCE ->
            "This machine only takes a resistance level, so the app sets it by measuring your " +
                "power. Allow a few seconds for it to settle after each change."
        control.power == ControlPath.MANUAL && control.road == ControlPath.NATIVE ->
            "Routes set the resistance of the road. This trainer can't hold a wattage, so in " +
                "workouts set the effort yourself."
        else ->
            "This machine's resistance can't be set from the app, so set it yourself. Your " +
                "power, speed and progress are still recorded as you ride."
    }
}

internal fun trainerStatusColor(state: TrainerConnectionState): Color = when (state) {
    TrainerConnectionState.Connected -> CadenceColors.Success
    TrainerConnectionState.Scanning,
    TrainerConnectionState.Connecting,
    TrainerConnectionState.Disconnecting -> CadenceColors.Warning
    TrainerConnectionState.Error -> CadenceColors.Danger
    TrainerConnectionState.Disconnected -> CadenceColors.Fg4
}

internal fun hrmTitle(state: HrmConnectionState, name: String?): String =
    if (state == HrmConnectionState.Connected && name != null) name else "Heart rate monitor"

internal fun hrmStatusLine(
    state: HrmConnectionState,
    heartRate: Int,
    sensorSilent: Boolean,
): String = when (state) {
    HrmConnectionState.Scanning -> "Scanning…"
    HrmConnectionState.Connecting -> "Connecting…"
    HrmConnectionState.Connected -> when {
        heartRate > 0 -> "$heartRate bpm"
        // "Waiting" implies patience will fix it. Once the monitor has given up
        // retrying, it won't, and the rider needs to act. See
        // HeartRateMonitor.sensorSilent.
        sensorSilent -> "Connected but sending nothing · restart Bluetooth"
        else -> "Connected · waiting for first sample"
    }
    HrmConnectionState.Disconnecting -> "Disconnecting…"
    HrmConnectionState.Disconnected -> "Not paired"
    HrmConnectionState.Error -> "Connection error · tap to retry"
}

internal fun hrmStatusColor(state: HrmConnectionState, sensorSilent: Boolean): Color = when (state) {
    HrmConnectionState.Connected -> if (sensorSilent) CadenceColors.Danger else CadenceColors.Success
    HrmConnectionState.Scanning,
    HrmConnectionState.Connecting,
    HrmConnectionState.Disconnecting -> CadenceColors.Warning
    HrmConnectionState.Error -> CadenceColors.Danger
    HrmConnectionState.Disconnected -> CadenceColors.Fg4
}

internal fun controllerTitle(state: ControllerConnectionState, name: String?): String =
    if (state == ControllerConnectionState.Connected && name != null) name else "Controller"

internal fun controllerStatusLine(
    state: ControllerConnectionState,
    keypadSilent: Boolean,
): String = when (state) {
    ControllerConnectionState.Scanning -> "Scanning…"
    ControllerConnectionState.Connecting -> "Connecting…"
    // Connected is not the same as usable: a controller whose protocol the app
    // cannot speak pairs and reports itself connected like any other.
    ControllerConnectionState.Connected ->
        if (keypadSilent) "Connected but not answering · use in-app ± buttons"
        else "Connected · in-ride ± control"
    ControllerConnectionState.Disconnecting -> "Disconnecting…"
    ControllerConnectionState.Disconnected -> "Not paired · use in-app ± buttons"
    ControllerConnectionState.Error -> "Connection error · tap to retry"
}

internal fun controllerStatusColor(
    state: ControllerConnectionState,
    keypadSilent: Boolean,
): Color = when (state) {
    ControllerConnectionState.Connected ->
        if (keypadSilent) CadenceColors.Danger else CadenceColors.Success
    ControllerConnectionState.Scanning,
    ControllerConnectionState.Connecting,
    ControllerConnectionState.Disconnecting -> CadenceColors.Warning
    ControllerConnectionState.Error -> CadenceColors.Danger
    ControllerConnectionState.Disconnected -> CadenceColors.Fg4
}
