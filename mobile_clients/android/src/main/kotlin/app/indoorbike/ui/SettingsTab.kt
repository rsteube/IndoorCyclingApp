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
package app.indoorbike.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import app.indoorbike.BuildConfig
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.ble.ControllerConnectionState
import app.indoorbike.shared.ble.HrmConnectionState
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.sync.SyncStatus
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.components.UnfinishedRideBanner
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlinx.coroutines.launch

private enum class DeviceSheetTarget { Trainer, Hrm, Controller }

@Composable
fun SettingsTab(
    onNavigateToPairing: () -> Unit,
    onNavigateToHrmPairing: () -> Unit,
    onNavigateToControllerPairing: () -> Unit
) {
    var sheetTarget by remember { mutableStateOf<DeviceSheetTarget?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
    ) {
        AppBar(title = "Settings", eyebrow = "Preferences")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s5)
        ) {
            Spacer(modifier = Modifier.height(Spacing.s3))

            UnfinishedRideBanner()

            Spacer(modifier = Modifier.height(Spacing.s5))

            SectionHeader("PROFILE")
            FtpRow()
            Spacer(modifier = Modifier.height(Spacing.s3))
            WeightRow()
            Spacer(modifier = Modifier.height(Spacing.s3))
            MaxHrRow()

            Spacer(modifier = Modifier.height(Spacing.s6))

            SectionHeader("DEVICES")
            DevicesCard(
                onTrainerClick = { sheetTarget = DeviceSheetTarget.Trainer },
                onHrmClick = { sheetTarget = DeviceSheetTarget.Hrm },
                onControllerClick = { sheetTarget = DeviceSheetTarget.Controller }
            )

            Spacer(modifier = Modifier.height(Spacing.s6))

            SectionHeader("CLOUD SYNC")
            IntervalsIcuCard()

            Spacer(modifier = Modifier.height(Spacing.s6))

            SectionHeader("BACKUP & SYNC")
            BackupSyncCard()

            Spacer(modifier = Modifier.height(Spacing.s6))

            SectionHeader("ABOUT")
            AboutCard()

            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(modifier = Modifier.height(Spacing.s5))
        }
    }

    DeviceSheet(
        target = sheetTarget,
        onDismiss = { sheetTarget = null },
        onPairTrainer = {
            sheetTarget = null
            onNavigateToPairing()
        },
        onPairHrm = {
            sheetTarget = null
            onNavigateToHrmPairing()
        },
        onPairController = {
            sheetTarget = null
            onNavigateToControllerPairing()
        }
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = CadenceType.eyebrow,
        color = CadenceColors.Fg3,
        modifier = Modifier.padding(bottom = Spacing.s3)
    )
}

// ─── Devices card + rows ──────────────────────────────────────────────────────

@Composable
private fun DevicesCard(
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
    val controllerState by container.controllerManager.connectionState.collectAsState()
    val controllerName by container.controllerManager.connectedDeviceName.collectAsState()

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
            statusLine = hrmStatusLine(hrmState, hrmHeartRate),
            statusColor = hrmStatusColor(hrmState),
            onClick = onHrmClick
        )
        HorizontalDivider(color = CadenceColors.Line1, thickness = 1.dp)
        DeviceRow(
            icon = Icons.Outlined.SettingsRemote,
            iconTint = CadenceColors.Brand500,
            title = controllerTitle(controllerState, controllerName),
            statusLine = controllerStatusLine(controllerState),
            statusColor = controllerStatusColor(controllerState),
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

// ─── Bottom sheet for the tapped device ───────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(
    target: DeviceSheetTarget?,
    onDismiss: () -> Unit,
    onPairTrainer: () -> Unit,
    onPairHrm: () -> Unit,
    onPairController: () -> Unit,
) {
    if (target == null) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CadenceColors.Bg1,
        contentColor = CadenceColors.Fg1,
    ) {
        when (target) {
            DeviceSheetTarget.Trainer -> TrainerSheetContent(
                onPair = onPairTrainer,
                onClose = onDismiss
            )
            DeviceSheetTarget.Hrm -> HrmSheetContent(
                onPair = onPairHrm,
                onClose = onDismiss
            )
            DeviceSheetTarget.Controller -> ControllerSheetContent(
                onPair = onPairController,
                onClose = onDismiss
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrainerSheetContent(
    onPair: () -> Unit,
    onClose: () -> Unit,
) {
    val container = LocalAppContainer.current
    val trainerManager = container.trainerManager
    val state by trainerManager.connectionState.collectAsState()
    val name by trainerManager.connectedTrainerName.collectAsState()
    val pairedId by trainerManager.pairedTrainerId.collectAsState()
    val isMock by trainerManager.isMockTrainer.collectAsState()
    val scope = rememberCoroutineScope()

    SheetBody(
        icon = Icons.Outlined.Bluetooth,
        iconTint = CadenceColors.Brand500,
        title = trainerTitle(state, name, isMock),
        statusLine = trainerStatusLine(state, isMock),
        helper = "Smart trainers using FTMS or Cycling Power Service. " +
                "Wahoo Kickr, Tacx Neo, Saris, Elite, etc.",
    ) {
        when (state) {
            TrainerConnectionState.Connected,
            TrainerConnectionState.Connecting,
            TrainerConnectionState.Disconnecting -> {
                SecondaryBtn(
                    text = if (state == TrainerConnectionState.Connecting) "Cancel Connection" else "Disconnect",
                    onClick = {
                        scope.launch { trainerManager.disconnect() }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                PrimaryBtn(
                    text = "Pair Trainer",
                    leadingIcon = Icons.AutoMirrored.Outlined.BluetoothSearching,
                    onClick = onPair,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (pairedId != null || isMock) {
            Spacer(modifier = Modifier.height(Spacing.s3))
            SecondaryBtn(
                text = "Forget Trainer",
                onClick = {
                    trainerManager.forgetTrainer()
                    container.userSettings.setPairedTrainerId(null)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HrmSheetContent(
    onPair: () -> Unit,
    onClose: () -> Unit,
) {
    val container = LocalAppContainer.current
    val hrm = container.heartRateMonitor
    val state by hrm.connectionState.collectAsState()
    val name by hrm.connectedDeviceName.collectAsState()
    val pairedId by hrm.pairedDeviceId.collectAsState()
    val heartRate by hrm.heartRate.collectAsState()

    SheetBody(
        icon = Icons.Outlined.MonitorHeart,
        iconTint = CadenceColors.Danger,
        title = hrmTitle(state, name),
        statusLine = hrmStatusLine(state, heartRate),
        helper = "Bluetooth chest strap (Polar H10, Wahoo TICKR, Garmin HRM-Pro/Dual, etc.). " +
                "Wake the strap by snapping it on or moistening the contacts before scanning.",
    ) {
        when (state) {
            HrmConnectionState.Connected,
            HrmConnectionState.Connecting,
            HrmConnectionState.Disconnecting -> {
                SecondaryBtn(
                    text = if (state == HrmConnectionState.Connecting) "Cancel Connection" else "Disconnect",
                    onClick = { hrm.disconnect() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                PrimaryBtn(
                    text = "Pair Heart Rate Monitor",
                    leadingIcon = Icons.Outlined.MonitorHeart,
                    onClick = onPair,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (pairedId != null) {
            Spacer(modifier = Modifier.height(Spacing.s3))
            SecondaryBtn(
                text = "Forget HRM",
                onClick = {
                    hrm.forgetDevice()
                    container.userSettings.setPairedHrmId(null)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControllerSheetContent(
    onPair: () -> Unit,
    onClose: () -> Unit,
) {
    val container = LocalAppContainer.current
    val controller = container.controllerManager
    val state by controller.connectionState.collectAsState()
    val name by controller.connectedDeviceName.collectAsState()
    val pairedId by controller.pairedDeviceId.collectAsState()

    SheetBody(
        icon = Icons.Outlined.SettingsRemote,
        iconTint = CadenceColors.Brand500,
        title = controllerTitle(state, name),
        statusLine = controllerStatusLine(state),
        // Plus increases the workout's target power, minus decreases it. Same
        // ±5 % step that the in-app stepper uses, so users feel the same beat
        // whether or not the Click is paired.
        helper = "Bluetooth ride controller for in-workout ± difficulty. " +
                "Supported: Zwift Click v1 (BC1). Encrypted Zwift Play / Ride controllers " +
                "are not yet supported. With no controller paired, the in-app ± buttons still work.",
    ) {
        when (state) {
            ControllerConnectionState.Connected,
            ControllerConnectionState.Connecting,
            ControllerConnectionState.Disconnecting -> {
                SecondaryBtn(
                    text = if (state == ControllerConnectionState.Connecting) "Cancel Connection" else "Disconnect",
                    onClick = { controller.disconnect() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                PrimaryBtn(
                    text = "Pair Controller",
                    leadingIcon = Icons.Outlined.SettingsRemote,
                    onClick = onPair,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (pairedId != null) {
            Spacer(modifier = Modifier.height(Spacing.s3))
            SecondaryBtn(
                text = "Forget Controller",
                onClick = {
                    controller.forgetDevice()
                    container.userSettings.setPairedControllerId(null)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SheetBody(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    statusLine: String,
    helper: String,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5)
            .padding(bottom = Spacing.s5)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
            modifier = Modifier.padding(bottom = Spacing.s4)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = CadenceType.h3, color = CadenceColors.Fg1)
                Text(text = statusLine, style = CadenceType.small, color = CadenceColors.Fg3)
            }
        }

        actions()

        Spacer(modifier = Modifier.height(Spacing.s4))

        Text(
            text = helper,
            style = CadenceType.small,
            color = CadenceColors.Fg3,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ─── State → label helpers ────────────────────────────────────────────────────

private fun trainerTitle(
    state: TrainerConnectionState,
    name: String?,
    isMock: Boolean,
): String = when {
    state == TrainerConnectionState.Connected && name != null -> name
    isMock -> "Mock Trainer"
    else -> "Trainer"
}

private fun trainerStatusLine(state: TrainerConnectionState, isMock: Boolean): String = when (state) {
    TrainerConnectionState.Scanning -> "Scanning…"
    TrainerConnectionState.Connecting -> "Connecting…"
    TrainerConnectionState.Connected -> if (isMock) "Mock connected" else "Connected"
    TrainerConnectionState.Disconnecting -> "Disconnecting…"
    TrainerConnectionState.Disconnected -> "Not paired"
    TrainerConnectionState.Error -> "Connection error · tap to retry"
}

private fun trainerStatusColor(state: TrainerConnectionState): Color = when (state) {
    TrainerConnectionState.Connected -> CadenceColors.Success
    TrainerConnectionState.Scanning,
    TrainerConnectionState.Connecting,
    TrainerConnectionState.Disconnecting -> CadenceColors.Warning
    TrainerConnectionState.Error -> CadenceColors.Danger
    TrainerConnectionState.Disconnected -> CadenceColors.Fg4
}

private fun hrmTitle(state: HrmConnectionState, name: String?): String =
    if (state == HrmConnectionState.Connected && name != null) name else "Heart Rate Monitor"

private fun hrmStatusLine(state: HrmConnectionState, heartRate: Int): String = when (state) {
    HrmConnectionState.Scanning -> "Scanning…"
    HrmConnectionState.Connecting -> "Connecting…"
    HrmConnectionState.Connected -> if (heartRate > 0) "$heartRate BPM" else "Connected · waiting for first sample"
    HrmConnectionState.Disconnecting -> "Disconnecting…"
    HrmConnectionState.Disconnected -> "Not paired"
    HrmConnectionState.Error -> "Connection error · tap to retry"
}

private fun hrmStatusColor(state: HrmConnectionState): Color = when (state) {
    HrmConnectionState.Connected -> CadenceColors.Success
    HrmConnectionState.Scanning,
    HrmConnectionState.Connecting,
    HrmConnectionState.Disconnecting -> CadenceColors.Warning
    HrmConnectionState.Error -> CadenceColors.Danger
    HrmConnectionState.Disconnected -> CadenceColors.Fg4
}

private fun controllerTitle(state: ControllerConnectionState, name: String?): String =
    if (state == ControllerConnectionState.Connected && name != null) name else "Controller"

private fun controllerStatusLine(state: ControllerConnectionState): String = when (state) {
    ControllerConnectionState.Scanning -> "Scanning…"
    ControllerConnectionState.Connecting -> "Connecting…"
    ControllerConnectionState.Connected -> "Connected · in-ride ± control"
    ControllerConnectionState.Disconnecting -> "Disconnecting…"
    ControllerConnectionState.Disconnected -> "Not paired · use in-app ± buttons"
    ControllerConnectionState.Error -> "Connection error · tap to retry"
}

private fun controllerStatusColor(state: ControllerConnectionState): Color = when (state) {
    ControllerConnectionState.Connected -> CadenceColors.Success
    ControllerConnectionState.Scanning,
    ControllerConnectionState.Connecting,
    ControllerConnectionState.Disconnecting -> CadenceColors.Warning
    ControllerConnectionState.Error -> CadenceColors.Danger
    ControllerConnectionState.Disconnected -> CadenceColors.Fg4
}

// ─── Profile rows (unchanged) ─────────────────────────────────────────────────

@Composable
private fun WeightRow() {
    val container = LocalAppContainer.current
    val userSettings = container.userSettings
    val weight by userSettings.weightKg.collectAsState()
    var weightInput by remember(weight) { mutableStateOf(formatWeightForInput(weight)) }

    fun commit() {
        val parsed = weightInput.replace(',', '.').toFloatOrNull()
        if (parsed == null ||
            parsed !in app.indoorbike.shared.settings.MIN_WEIGHT_KG..app.indoorbike.shared.settings.MAX_WEIGHT_KG
        ) {
            weightInput = formatWeightForInput(weight)
        } else if (parsed != weight) {
            userSettings.setWeightKg(parsed)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Body Weight", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Used for W/kg and XP scoring", style = CadenceType.body, color = CadenceColors.Fg3)
            }

            Spacer(modifier = Modifier.width(16.dp))

            androidx.compose.foundation.text.BasicTextField(
                value = weightInput,
                onValueChange = { newValue ->
                    val filtered = buildString {
                        var seenDot = false
                        for (c in newValue.take(6)) {
                            if (c.isDigit()) append(c)
                            else if ((c == '.' || c == ',') && !seenDot) {
                                append('.')
                                seenDot = true
                            }
                        }
                    }
                    weightInput = filtered
                },
                textStyle = CadenceType.h2.copy(color = CadenceColors.Brand500, textAlign = TextAlign.End),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { commit() }
                ),
                singleLine = true,
                modifier = Modifier
                    .width(110.dp)
                    .onFocusChanged { state -> if (!state.isFocused) commit() },
                decorationBox = { innerTextField ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.sm))
                            .background(CadenceColors.Bg2)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            innerTextField()
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("kg", style = CadenceType.bodyStrong, color = CadenceColors.Fg3)
                    }
                }
            )
        }
    }
}

private fun formatWeightForInput(kg: Float): String {
    val rounded = kotlin.math.round(kg * 10f) / 10f
    return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
}

@Composable
private fun FtpRow() {
    val container = LocalAppContainer.current
    val userSettings = container.userSettings
    val ftp by userSettings.ftp.collectAsState()
    var ftpInput by remember(ftp) { mutableStateOf(ftp.toString()) }

    fun commit() {
        val parsed = ftpInput.toIntOrNull()
        if (parsed == null || parsed !in app.indoorbike.shared.settings.MIN_FTP..app.indoorbike.shared.settings.MAX_FTP) {
            ftpInput = ftp.toString()
        } else if (parsed != ftp) {
            userSettings.setFtp(parsed)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Functional Threshold Power (FTP)", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Used to calculate your power zones", style = CadenceType.body, color = CadenceColors.Fg3)
            }

            Spacer(modifier = Modifier.width(16.dp))

            androidx.compose.foundation.text.BasicTextField(
                value = ftpInput,
                onValueChange = { newValue ->
                    ftpInput = newValue.filter { it.isDigit() }.take(4)
                },
                textStyle = CadenceType.h2.copy(color = CadenceColors.Brand500, textAlign = TextAlign.End),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { commit() }
                ),
                singleLine = true,
                modifier = Modifier
                    .width(110.dp)
                    .onFocusChanged { state -> if (!state.isFocused) commit() },
                decorationBox = { innerTextField ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.sm))
                            .background(CadenceColors.Bg2)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            innerTextField()
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("W", style = CadenceType.bodyStrong, color = CadenceColors.Fg3)
                    }
                }
            )
        }
    }
}

// ─── Max HR row ───────────────────────────────────────────────────────────────

@Composable
private fun MaxHrRow() {
    val container = LocalAppContainer.current
    val userSettings = container.userSettings
    val maxHr by userSettings.maxHr.collectAsState()
    var input by remember(maxHr) { mutableStateOf(maxHr.toString()) }

    fun commit() {
        val parsed = input.toIntOrNull()
        if (parsed == null || parsed !in app.indoorbike.shared.settings.MIN_MAX_HR..app.indoorbike.shared.settings.MAX_MAX_HR) {
            input = maxHr.toString()
        } else if (parsed != maxHr) {
            userSettings.setMaxHr(parsed)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Maximum heart rate", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Used to colour the live HR readout by zone",
                    style = CadenceType.body,
                    color = CadenceColors.Fg3
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            androidx.compose.foundation.text.BasicTextField(
                value = input,
                onValueChange = { newValue -> input = newValue.filter { it.isDigit() }.take(3) },
                textStyle = CadenceType.h2.copy(color = CadenceColors.Danger, textAlign = TextAlign.End),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(CadenceColors.Danger),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
                singleLine = true,
                modifier = Modifier
                    .width(110.dp)
                    .onFocusChanged { state -> if (!state.isFocused) commit() },
                decorationBox = { innerTextField ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.sm))
                            .background(CadenceColors.Bg2)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            innerTextField()
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("BPM", style = CadenceType.bodyStrong, color = CadenceColors.Fg3)
                    }
                }
            )
        }
    }
}

// ─── About ────────────────────────────────────────────────────────────────────

@Composable
private fun AboutCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2)
    ) {
        AboutRow(
            label = "App version",
            value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        )
        AboutRow(
            label = "Build",
            value = if (BuildConfig.DEBUG) "Debug" else "Release"
        )
        AboutRow(
            label = "License",
            value = "GPLv3"
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = CadenceType.body, color = CadenceColors.Fg2)
        Text(text = value, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
    }
}

// ─── Cloud Sync · Intervals.icu ──────────────────────────────────────────────

/**
 * Off-by-default cloud sync card for Intervals.icu. The whole section sits
 * idle (no network, no upload) until the user fills in athlete id + API key.
 * Auto-upload is its own opt-in toggle; manual upload from
 * WorkoutOverviewScreen also picks up these credentials.
 *
 * F-Droid posture: nothing here triggers a network call by itself — only the
 * upload path (manual or auto) does, and both require explicit user action.
 */
@Composable
private fun IntervalsIcuCard() {
    val container = LocalAppContainer.current
    val settings = container.userSettings

    val athleteId by settings.intervalsAthleteId.collectAsState()
    val apiKey by settings.intervalsApiKey.collectAsState()
    val autoUpload by settings.intervalsAutoUpload.collectAsState()
    val configured = !athleteId.isNullOrBlank() && !apiKey.isNullOrBlank()

    var showKey by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(Spacing.s5),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CadenceColors.Brand500.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudUpload,
                    contentDescription = null,
                    tint = CadenceColors.Brand500,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(Spacing.s3))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Intervals.icu", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Text(
                    text = if (configured) "Configured" else "Not configured",
                    style = CadenceType.tick,
                    color = if (configured) CadenceColors.Success else CadenceColors.Fg3
                )
            }
        }

        Text(
            text = "Sync your finished rides to intervals.icu. Off by default — your athlete id " +
                "and API key live only on this phone, and the app only contacts intervals.icu " +
                "when an upload is triggered (manually or via auto-upload below).",
            style = CadenceType.small,
            color = CadenceColors.Fg3
        )

        IntervalsTextField(
            label = "ATHLETE ID",
            placeholder = "i12345",
            value = athleteId.orEmpty(),
            onValueChange = { settings.setIntervalsAthleteId(it) },
            keyboardType = KeyboardType.Ascii,
            visualTransformation = VisualTransformation.None,
        )

        IntervalsTextField(
            label = "API KEY",
            placeholder = "Paste from intervals.icu/settings",
            value = apiKey.orEmpty(),
            onValueChange = { settings.setIntervalsApiKey(it) },
            keyboardType = KeyboardType.Password,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                Icon(
                    imageVector = if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (showKey) "Hide key" else "Show key",
                    tint = CadenceColors.Fg3,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { showKey = !showKey }
                )
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Auto-upload finished rides", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Text(
                    text = if (configured)
                        "Each completed ride is sent immediately."
                    else
                        "Add credentials above to enable.",
                    style = CadenceType.small,
                    color = CadenceColors.Fg3
                )
            }
            Switch(
                checked = autoUpload && configured,
                enabled = configured,
                onCheckedChange = { settings.setIntervalsAutoUpload(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CadenceColors.OnBrand,
                    checkedTrackColor = CadenceColors.Brand500,
                    uncheckedThumbColor = CadenceColors.Fg3,
                    uncheckedTrackColor = CadenceColors.Bg2,
                    uncheckedBorderColor = CadenceColors.Line2,
                )
            )
        }
    }
}

@Composable
private fun IntervalsTextField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    visualTransformation: VisualTransformation,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.sm))
                .background(CadenceColors.Bg2)
                .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.sm))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = CadenceType.body.copy(color = CadenceColors.Fg1),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(CadenceColors.Brand500),
                visualTransformation = visualTransformation,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(text = placeholder, style = CadenceType.body, color = CadenceColors.Fg4)
                    }
                    inner()
                }
            )
            if (trailing != null) {
                Spacer(Modifier.width(Spacing.s3))
                trailing()
            }
        }
    }
}

// ─── Backup & Sync ───────────────────────────────────────────────────────────

/**
 * Folder-backed backup that doubles as cross-device sync. The rider picks
 * any SAF-accessible folder (Drive, Dropbox, Nextcloud, Syncthing, …) and
 * each device writes one small JSON slice into it; every device pointed at
 * the same folder shares rides, library, and settings.
 *
 * F-Droid posture: nothing here triggers a network call from the app
 * itself — the rider's chosen storage provider does any uploading on the
 * SAF side. No accounts, no servers, no telemetry.
 */
@Composable
private fun BackupSyncCard() {
    val container = LocalAppContainer.current
    val settings = container.userSettings
    val engine = container.syncEngine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val syncFolderUri by settings.syncFolderUri.collectAsState()
    val deviceLabel by settings.deviceLabel.collectAsState()
    val status by engine.status.collectAsState()
    val paused by settings.syncPaused.collectAsState()
    val configured = !syncFolderUri.isNullOrBlank()

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Keep the grant alive across reboots — without this the URI works
        // for the current process only and breaks on next launch.
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // A few exotic SAF providers don't support persistable permissions.
            // Save the URI anyway so syncs work for this session; the rider
            // will be re-prompted on next launch.
        }
        settings.setSyncFolderUri(uri.toString())
        // Auto-fire a first sync so the rider sees something happen rather
        // than tapping Sync now after picking the folder.
        scope.launch { engine.syncNow() }
    }

    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(Spacing.s5),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CadenceColors.Brand500.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = CadenceColors.Brand500,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(Spacing.s3))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Backup & sync", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Text(
                    text = if (configured) "Configured" else "Not configured",
                    style = CadenceType.tick,
                    color = if (configured) CadenceColors.Success else CadenceColors.Fg3
                )
            }
        }

        Text(
            text = "Pick any folder on your device or in cloud storage — Drive, Dropbox, " +
                "Nextcloud, Syncthing, OneDrive, anything that exposes itself as a folder. " +
                "Each device writes one small JSON file there; other devices pointed at the " +
                "same folder share your rides, library, and settings. No accounts, no servers " +
                "— just a file in a folder you already trust.",
            style = CadenceType.small,
            color = CadenceColors.Fg3
        )

        if (!configured) {
            PrimaryBtn(
                text = "Choose sync folder",
                leadingIcon = null,
                onClick = { pickFolderLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            FolderRow(
                uri = syncFolderUri!!,
                onChange = { pickFolderLauncher.launch(null) }
            )

            DeviceLabelField(
                value = deviceLabel,
                onValueChange = { settings.setDeviceLabel(it) }
            )

            SyncStatusLine(status)

            val review = status as? SyncStatus.NeedsReview
            // If we're paused but the status hasn't been recomputed into a
            // NeedsReview yet (e.g. Settings opened before the launch sync
            // finished), kick a read-only assess so the counts populate.
            LaunchedEffect(paused, review == null) {
                if (paused && review == null) engine.syncNow()
            }

            if (paused || review != null) {
                // Backup safety: a suspicious reset paused syncing. Make the
                // rider choose before anything is written to the folder.
                SyncReviewBlock(
                    review = review,
                    busy = status is SyncStatus.Syncing,
                    onRestore = { scope.launch { engine.resolveRestoreFromBackup() } },
                    onKeepLocal = { scope.launch { engine.resolveKeepLocal() } }
                )
            } else {
                // "Sync now" is the action a rider reaches for 99% of the time;
                // give it the full row.
                PrimaryBtn(
                    text = if (status is SyncStatus.Syncing) "Syncing…" else "Sync now",
                    leadingIcon = null,
                    enabled = status !is SyncStatus.Syncing,
                    onClick = { scope.launch { engine.syncNow() } },
                    modifier = Modifier.fillMaxWidth()
                )
                // Pull the folder's backup onto this device (additive - never
                // deletes local rides). Handy after an update wiped app data.
                Text(
                    text = "Restore from backup",
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Brand500,
                    modifier = Modifier
                        .clickable(enabled = status !is SyncStatus.Syncing) {
                            scope.launch { engine.resolveRestoreFromBackup() }
                        }
                        .padding(top = 8.dp, bottom = 4.dp)
                )
            }

            Spacer(Modifier.height(Spacing.s2))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Disconnects this device from the folder. Local rides, settings, " +
                        "and library stay put — slices in the folder aren't deleted.",
                    style = CadenceType.tick,
                    color = CadenceColors.Fg4,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.s3))
                Text(
                    text = "Stop syncing",
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Danger,
                    modifier = Modifier
                        .clickable {
                            // Release the SAF grant before clearing the URI —
                            // keeps the system permission table tidy when
                            // riders bounce between providers.
                            syncFolderUri?.let { raw ->
                                try {
                                    val uri = Uri.parse(raw)
                                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    context.contentResolver.releasePersistableUriPermission(uri, flags)
                                } catch (_: Throwable) {
                                    // Best effort — a stale grant is harmless.
                                }
                            }
                            settings.setSyncFolderUri(null)
                        }
                        .padding(top = 6.dp, bottom = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SyncReviewBlock(
    review: SyncStatus.NeedsReview?,
    busy: Boolean,
    onRestore: () -> Unit,
    onKeepLocal: () -> Unit
) {
    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Warning.copy(alpha = 0.10f))
            .border(1.dp, CadenceColors.Warning.copy(alpha = 0.5f), shape)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Text(
            text = "⚠ Backup paused",
            style = CadenceType.bodyStrong,
            color = CadenceColors.Warning
        )
        val detail = if (review != null) {
            "This device has ${review.localSessions} ride(s) and ${review.localImports} " +
                "workout(s), but the backup has ${review.backupSessions} ride(s) and " +
                "${review.backupImports} workout(s). That looks like the app's data was reset. " +
                "Automatic backup is paused so it can't overwrite the folder. Choose what to do:"
        } else {
            "Your local data looks reset compared to the backup. Automatic backup is paused " +
                "so it can't overwrite the folder. Choose what to do:"
        }
        Text(text = detail, style = CadenceType.small, color = CadenceColors.Fg2)

        PrimaryBtn(
            text = if (busy) "Working…" else "Restore from backup",
            leadingIcon = null,
            enabled = !busy,
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Brings the folder's rides, workouts, and settings back onto this device. " +
                "Recommended if the data loss was accidental.",
            style = CadenceType.tick,
            color = CadenceColors.Fg3
        )

        SecondaryBtn(
            text = "Keep this device's data",
            onClick = { if (!busy) onKeepLocal() },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Use only if you meant to clear these rides - the backup will be updated to " +
                "match this device, discarding the extras it holds.",
            style = CadenceType.tick,
            color = CadenceColors.Fg3
        )
    }
}

@Composable
private fun FolderRow(uri: String, onChange: () -> Unit) {
    val context = LocalContext.current
    // SAF tree URIs are opaque blobs; the user-friendly name comes from the
    // DocumentFile root. `remember(uri)` so a folder swap re-resolves.
    val folderName = remember(uri) {
        try {
            DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name?.takeIf { it.isNotBlank() }
                ?: "Sync folder"
        } catch (_: Throwable) {
            "Sync folder"
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "FOLDER", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
            Text(
                text = folderName,
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Text(
            text = "Change",
            style = CadenceType.bodyStrong,
            color = CadenceColors.Brand500,
            modifier = Modifier
                .clickable(onClick = onChange)
                .padding(start = Spacing.s3, top = 6.dp, bottom = 6.dp)
        )
    }
}

@Composable
private fun DeviceLabelField(value: String, onValueChange: (String) -> Unit) {
    // Local edit buffer so each keystroke doesn't write through to DataStore +
    // re-flow back. Commit on focus loss / IME-Done — same pattern as the FTP
    // and weight rows.
    var draft by remember(value) { mutableStateOf(value) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "DEVICE NAME", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.sm))
                .background(CadenceColors.Bg2)
                .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.sm))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = CadenceType.body.copy(color = CadenceColors.Fg1),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(CadenceColors.Brand500),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        if (draft.isNotBlank() && draft != value) onValueChange(draft)
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focus ->
                        if (!focus.isFocused && draft.isNotBlank() && draft != value) {
                            onValueChange(draft)
                        }
                    }
            )
        }
        Text(
            text = "Used in the backup file so other devices can tell yours apart.",
            style = CadenceType.tick,
            color = CadenceColors.Fg4
        )
    }
}

@Composable
private fun SyncStatusLine(status: SyncStatus) {
    val (text, color) = when (status) {
        SyncStatus.Idle -> "Not synced yet · tap Sync now" to CadenceColors.Fg3
        SyncStatus.Syncing -> "Syncing…" to CadenceColors.Brand500
        is SyncStatus.Success -> {
            val ago = formatRelativeTime(status.syncedAtEpoch)
            val devices = "${status.devicesSeen} device" + if (status.devicesSeen == 1) "" else "s"
            // Surface unreadable slices when we hit them — without this, a
            // corrupt peer's data silently disappears from the merged state
            // and the rider sees an unqualified "Synced just now" while
            // some devices' rides are missing.
            if (status.slicesSkipped == 0) {
                "Synced $ago · $devices seen" to CadenceColors.Success
            } else {
                val skipped = "${status.slicesSkipped} unreadable slice" +
                    if (status.slicesSkipped == 1) "" else "s"
                "Synced $ago · $devices seen · ⚠ $skipped" to CadenceColors.Warning
            }
        }
        is SyncStatus.Failed -> "Couldn't sync: ${status.message}" to CadenceColors.Danger
        SyncStatus.NotConfigured -> "Not configured" to CadenceColors.Fg3
        is SyncStatus.NeedsReview ->
            "Paused - local data looks reset. Review below." to CadenceColors.Warning
    }
    Text(
        text = text,
        style = CadenceType.small,
        color = color,
        textAlign = TextAlign.Start
    )
}

/**
 * Cheap relative-time renderer for the sync status line. Computed once per
 * recomposition — the line refreshes whenever the user reopens Settings or
 * triggers another sync, which is good enough; we don't need a ticking
 * "1 min ago → 2 min ago" effect for what's essentially a debug breadcrumb.
 */
private fun formatRelativeTime(epochMs: Long): String {
    val diffSec = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        diffSec < 5 -> "just now"
        diffSec < 60 -> "${diffSec}s ago"
        diffSec < 3600 -> "${diffSec / 60} min ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        else -> "${diffSec / 86400}d ago"
    }
}
