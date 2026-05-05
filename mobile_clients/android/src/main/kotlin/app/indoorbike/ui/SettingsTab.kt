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
import app.indoorbike.BuildConfig
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.ble.ControllerConnectionState
import app.indoorbike.shared.ble.HrmConnectionState
import app.indoorbike.shared.ble.TrainerConnectionState
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
            value = "Open source"
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
