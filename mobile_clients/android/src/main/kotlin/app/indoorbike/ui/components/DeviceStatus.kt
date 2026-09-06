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
package app.indoorbike.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.ble.ControllerConnectionState
import app.indoorbike.shared.ble.HrmConnectionState
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

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
) {
    val container = LocalAppContainer.current
    val trainerState by container.trainerManager.connectionState.collectAsState()
    val trainerName by container.trainerManager.connectedTrainerName.collectAsState()
    val isMockTrainer by container.trainerManager.isMockTrainer.collectAsState()
    val hrmState by container.heartRateMonitor.connectionState.collectAsState()
    val hrmName by container.heartRateMonitor.connectedDeviceName.collectAsState()
    val hrmHeartRate by container.heartRateMonitor.heartRate.collectAsState()
    val hrmSilent by container.heartRateMonitor.sensorSilent.collectAsState()
    val controllerState by container.controllerManager.connectionState.collectAsState()
    val controllerName by container.controllerManager.connectedDeviceName.collectAsState()
    val controllerSilent by container.controllerManager.keypadSilent.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
    ) {
        DeviceRow(
            icon = Icons.Outlined.Bluetooth,
            iconTint = CadenceColors.Brand500,
            title = trainerTitle(trainerState, trainerName, isMockTrainer),
            statusLine = trainerStatusLine(trainerState, isMockTrainer),
            statusColor = trainerStatusColor(trainerState),
            onClick = onTrainerClick
        )
        HorizontalDivider(color = CadenceColors.Line1, thickness = 1.dp)
        DeviceRow(
            icon = Icons.Outlined.MonitorHeart,
            iconTint = CadenceColors.Danger,
            title = hrmTitle(hrmState, hrmName),
            statusLine = hrmStatusLine(hrmState, hrmHeartRate, hrmSilent),
            statusColor = hrmStatusColor(hrmState, hrmSilent),
            onClick = onHrmClick
        )
        HorizontalDivider(color = CadenceColors.Line1, thickness = 1.dp)
        DeviceRow(
            icon = Icons.Outlined.SettingsRemote,
            iconTint = CadenceColors.Brand500,
            title = controllerTitle(controllerState, controllerName),
            statusLine = controllerStatusLine(controllerState, controllerSilent),
            statusColor = controllerStatusColor(controllerState, controllerSilent),
            onClick = onControllerClick
        )
    }
}

@Composable
private fun DeviceRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    statusLine: String,
    statusColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
            Text(text = statusLine, style = CadenceType.small, color = CadenceColors.Fg3)
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(Spacing.s2))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = CadenceColors.Fg4,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ─── State → label helpers ────────────────────────────────────────────────────

internal fun trainerTitle(
    state: TrainerConnectionState,
    name: String?,
    isMock: Boolean,
): String = when {
    state == TrainerConnectionState.Connected && name != null -> name
    isMock -> "Mock Trainer"
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

internal fun trainerStatusColor(state: TrainerConnectionState): Color = when (state) {
    TrainerConnectionState.Connected -> CadenceColors.Success
    TrainerConnectionState.Scanning,
    TrainerConnectionState.Connecting,
    TrainerConnectionState.Disconnecting -> CadenceColors.Warning
    TrainerConnectionState.Error -> CadenceColors.Danger
    TrainerConnectionState.Disconnected -> CadenceColors.Fg4
}

internal fun hrmTitle(state: HrmConnectionState, name: String?): String =
    if (state == HrmConnectionState.Connected && name != null) name else "Heart Rate Monitor"

internal fun hrmStatusLine(
    state: HrmConnectionState,
    heartRate: Int,
    sensorSilent: Boolean,
): String = when (state) {
    HrmConnectionState.Scanning -> "Scanning…"
    HrmConnectionState.Connecting -> "Connecting…"
    HrmConnectionState.Connected -> when {
        heartRate > 0 -> "$heartRate BPM"
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
