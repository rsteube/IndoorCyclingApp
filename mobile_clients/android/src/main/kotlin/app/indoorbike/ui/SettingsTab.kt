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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudUpload
import android.widget.Toast
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Terrain
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import app.indoorbike.BuildConfig
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.ble.CONTROLLER_HELPER
import app.indoorbike.shared.ble.hrmHelper
import app.indoorbike.shared.ble.trainerTitle
import app.indoorbike.shared.ble.trainerStatusLine
import app.indoorbike.shared.ble.trainerHelper
import app.indoorbike.shared.ble.hrmTitle
import app.indoorbike.shared.ble.hrmStatusLine
import app.indoorbike.shared.ble.controllerTitle
import app.indoorbike.shared.ble.controllerStatusLine
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.feedback.ReportProblemDialog
import app.indoorbike.shared.ble.ControllerConnectionState
import app.indoorbike.shared.ble.HrmConnectionState
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.sync.SyncStatus
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.marginRidges
import app.indoorbike.ui.components.ContentMaxWidth
import app.indoorbike.ui.components.tabRootBottomPadding
import app.indoorbike.ui.components.rememberCollapsingHeaderState
import app.indoorbike.ui.components.CardDivider
import app.indoorbike.ui.components.ListCard
import app.indoorbike.ui.components.NavRow
import app.indoorbike.ui.components.SectionLabel
import app.indoorbike.ui.components.SlateCard
import app.indoorbike.ui.components.DeviceStatusCard
import app.indoorbike.ui.components.controllerStatusColor
import app.indoorbike.ui.components.hrmStatusColor
import app.indoorbike.ui.components.trainerStatusColor
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.components.UnfinishedRideBanner
import app.indoorbike.ui.components.SheetHandle
import app.indoorbike.ui.components.SheetScrim
import app.indoorbike.ui.components.SheetShape
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.theme.SlateMotion
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class DeviceSheetTarget { Trainer, Hrm, Controller }

@Composable
fun SettingsTab(
    onNavigateToPairing: () -> Unit,
    onNavigateToHrmPairing: () -> Unit,
    onNavigateToControllerPairing: () -> Unit,
    onNavigateToConnections: () -> Unit,
    onNavigateToSimulationSettings: () -> Unit = {},
) {
    var sheetTarget by remember { mutableStateOf<DeviceSheetTarget?>(null) }

    val header = rememberCollapsingHeaderState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
            .marginRidges()
            .nestedScroll(header.nestedScrollConnection)
    ) {
        AppBar(title = "Settings", eyebrow = "Preferences", header = header)

        Column(
            modifier = Modifier
                // Height only. fillMaxSize would fix the width too and the cap
                // below it would do nothing, which is the trap this screen and
                // three others fell into.
                .fillMaxHeight()
                .widthIn(max = ContentMaxWidth)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s5)
        ) {
            Spacer(modifier = Modifier.height(Spacing.s4))

            UnfinishedRideBanner()

            SectionHeader("Profile")
            // One card, three rows: the fields form a single logical unit, so
            // they share a surface instead of floating as three separate boxes.
            ListCard {
                FtpRow()
                CardDivider()
                WeightRow()
                CardDivider()
                MaxHrRow()
            }

            SectionHeader("Devices")
            DeviceStatusCard(
                onTrainerClick = { sheetTarget = DeviceSheetTarget.Trainer },
                onHrmClick = { sheetTarget = DeviceSheetTarget.Hrm },
                onControllerClick = { sheetTarget = DeviceSheetTarget.Controller },
                showChevrons = true
            )

            SectionHeader("More")
            // Everything that has a page of its own. The services a ride can be
            // sent to only grow in number, and the bike is the machine rather
            // than the rider, so neither belongs inline on a screen about the
            // person on the pedals.
            ListCard {
                NavRow(
                    icon = Icons.Outlined.CloudUpload,
                    title = "Connections",
                    subtitle = connectionsSummary(),
                    onClick = onNavigateToConnections
                )
                CardDivider()
                NavRow(
                    icon = Icons.Outlined.Terrain,
                    title = "Bike & gearing",
                    subtitle = simulationSummary(),
                    onClick = onNavigateToSimulationSettings
                )
            }
            Spacer(modifier = Modifier.height(Spacing.s3))
            BackupSyncCard()

            SectionHeader("About")
            AboutCard()

            SectionHeader("Feedback")
            ReportProblemCard()

            // The scroll runs under the floating tab bar, so the tail has to
            // clear it rather than stopping above it.
            Spacer(modifier = Modifier.height(tabRootBottomPadding()))
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

/** Bike weight and drivetrain, so the row says something without being opened. */
@Composable
private fun simulationSummary(): String {
    val settings = LocalAppContainer.current.userSettings
    val bikeWeight by settings.bikeWeightKg.collectAsStateWithLifecycle()
    val drivetrainId by settings.drivetrainId.collectAsStateWithLifecycle()
    val drivetrain = Drivetrain.byId(drivetrainId)
    return "${drivetrain.name} · ${app.indoorbike.ui.screens.simulation.formatOneDecimal(bikeWeight.toDouble())} kg bike"
}

/** One-line summary of what's configured, so Settings still says something. */
@Composable
private fun connectionsSummary(): String {
    val settings = LocalAppContainer.current.userSettings
    val athlete by settings.intervalsAthleteId.collectAsStateWithLifecycle()
    val key by settings.intervalsApiKey.collectAsStateWithLifecycle()
    val intervalsToken by settings.intervalsAccessToken.collectAsStateWithLifecycle()
    val ftToken by settings.fitTrackeeAccessToken.collectAsStateWithLifecycle()
    val names = buildList {
        if (!intervalsToken.isNullOrBlank() || (!athlete.isNullOrBlank() && !key.isNullOrBlank())) {
            add("Intervals.icu")
        }
        if (!ftToken.isNullOrBlank()) add("FitTrackee")
    }
    return if (names.isEmpty()) "Nothing connected" else names.joinToString(" · ")
}

@Composable
private fun SectionHeader(text: String) {
    SectionLabel(
        text = text,
        modifier = Modifier.padding(top = Spacing.s5, bottom = Spacing.s3)
    )
}

/**
 * Card with a permanent summary row and disclosure body. Settings used to
 * show every card fully expanded - two credential fields, four paragraphs of
 * sync prose - whether or not the rider ever uses those features. The
 * summary row keeps the status visible ("Configured", "Synced 3 min ago");
 * everything else waits behind a tap.
 */
@Composable
internal fun ExpandableCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    statusText: String,
    statusColor: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chevron by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "chevron"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1)
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
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
                Text(text = title, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = statusText, style = CadenceType.small, color = statusColor)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = CadenceColors.Fg3,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationZ = chevron }
            )
        }
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(
                    start = Spacing.s5, end = Spacing.s5,
                    top = Spacing.s2, bottom = Spacing.s4
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.s3),
                content = content
            )
        }
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
        shape = SheetShape,
        scrimColor = SheetScrim,
        dragHandle = { SheetHandle() },
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
    val state by trainerManager.connectionState.collectAsStateWithLifecycle()
    val name by trainerManager.connectedTrainerName.collectAsStateWithLifecycle()
    val pairedId by trainerManager.pairedTrainerId.collectAsStateWithLifecycle()
    val isMock by trainerManager.isMockTrainer.collectAsStateWithLifecycle()
    val control by trainerManager.control.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    SheetBody(
        icon = Icons.Outlined.Bluetooth,
        iconTint = CadenceColors.Fg1,
        title = trainerTitle(state, name, isMock),
        statusLine = trainerStatusLine(state, isMock),
        helper = trainerHelper(state, isMock, control),
    ) {
        when (state) {
            TrainerConnectionState.Connected,
            TrainerConnectionState.Connecting,
            TrainerConnectionState.Disconnecting -> {
                SecondaryBtn(
                    text = if (state == TrainerConnectionState.Connecting) "Cancel connection" else "Disconnect",
                    onClick = {
                        scope.launch { trainerManager.disconnect() }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                PrimaryBtn(
                    text = "Pair trainer",
                    leadingIcon = Icons.AutoMirrored.Outlined.BluetoothSearching,
                    onClick = onPair,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (pairedId != null || isMock) {
            Spacer(modifier = Modifier.height(Spacing.s3))
            SecondaryBtn(
                text = "Forget trainer",
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
    val state by hrm.connectionState.collectAsStateWithLifecycle()
    val name by hrm.connectedDeviceName.collectAsStateWithLifecycle()
    val pairedId by hrm.pairedDeviceId.collectAsStateWithLifecycle()
    val heartRate by hrm.heartRate.collectAsStateWithLifecycle()
    val sensorSilent by hrm.sensorSilent.collectAsStateWithLifecycle()

    SheetBody(
        icon = Icons.Outlined.MonitorHeart,
        iconTint = CadenceColors.Fg1,
        title = hrmTitle(state, name),
        statusLine = hrmStatusLine(state, heartRate, sensorSilent),
        helper = hrmHelper(sensorSilent),
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
                    text = "Pair heart rate monitor",
                    leadingIcon = Icons.Outlined.MonitorHeart,
                    onClick = onPair,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (pairedId != null) {
            Spacer(modifier = Modifier.height(Spacing.s3))
            SecondaryBtn(
                text = "Forget monitor",
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
    val state by controller.connectionState.collectAsStateWithLifecycle()
    val name by controller.connectedDeviceName.collectAsStateWithLifecycle()
    val pairedId by controller.pairedDeviceId.collectAsStateWithLifecycle()
    val keypadSilent by controller.keypadSilent.collectAsStateWithLifecycle()

    SheetBody(
        icon = Icons.Outlined.SettingsRemote,
        iconTint = CadenceColors.Fg1,
        title = controllerTitle(state, name),
        statusLine = controllerStatusLine(state, keypadSilent),
        // Plus increases the workout's target power, minus decreases it. Same
        // ±5 % step that the in-app stepper uses, so users feel the same beat
        // whether or not the Click is paired.
        helper = CONTROLLER_HELPER,
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
                    text = "Pair controller",
                    leadingIcon = Icons.Outlined.SettingsRemote,
                    onClick = onPair,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (pairedId != null) {
            Spacer(modifier = Modifier.height(Spacing.s3))
            SecondaryBtn(
                text = "Forget controller",
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
                    .clip(CircleShape)
                    .background(CadenceColors.Bg2),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = CadenceColors.Fg1,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = CadenceType.h3, color = CadenceColors.Fg1)
                Spacer(modifier = Modifier.height(2.dp))
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

// ─── Profile rows (unchanged) ─────────────────────────────────────────────────

@Composable
private fun WeightRow() {
    val container = LocalAppContainer.current
    val userSettings = container.userSettings
    val weight by userSettings.weightKg.collectAsStateWithLifecycle()

    // Unkeyed on purpose. Keying the buffer on the stored value re-created it
    // every time we wrote through, which yanks the cursor to the end mid-edit.
    // Instead the field re-syncs from storage only while the rider isn't typing
    // in it, which is the only time an external change (onboarding, a restore)
    // should be allowed to move it.
    var weightField by remember { mutableStateOf(TextFieldValue(formatWeightForInput(weight))) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(weight, focused) {
        if (!focused) weightField = TextFieldValue(formatWeightForInput(weight))
    }
    // Select the whole value once focus has settled, so typing replaces it.
    // Done from an effect rather than inside onFocusChanged because the IME
    // sets its own selection immediately after focus is granted and wins the
    // race; by the next frame it has finished and this sticks. Without it the
    // caret lands inside the existing number, so a rider typing 250 into a
    // field showing 265 gets 2250 - over the limit, and thrown away on commit
    // with no explanation.
    LaunchedEffect(focused) {
        if (focused) {
            weightField = weightField.copy(selection = TextRange(0, weightField.text.length))
        }
    }

    fun commit() {
        val parsed = weightField.text.replace(',', '.').toFloatOrNull()
        if (parsed == null ||
            parsed !in app.indoorbike.shared.settings.MIN_WEIGHT_KG..app.indoorbike.shared.settings.MAX_WEIGHT_KG
        ) {
            weightField = TextFieldValue(formatWeightForInput(weight))
        } else if (parsed != weight) {
            userSettings.setWeightKg(parsed)
        }
    }

    // Persist as soon as the typed value is usable, rather than waiting for a
    // commit point. Every way of leaving this screen used to be its own chance
    // to lose the edit: IME-Done and focus-loss were handled, switching tabs
    // disposed the field without a focus event, and the process being killed
    // mid-edit lost it whatever we did on the way out. Writing on the keystroke
    // makes all of those paths the same path.
    fun persistIfValid(text: String) {
        val parsed = text.replace(',', '.').toFloatOrNull() ?: return
        if (parsed in app.indoorbike.shared.settings.MIN_WEIGHT_KG..app.indoorbike.shared.settings.MAX_WEIGHT_KG && parsed != weight) {
            userSettings.setWeightKg(parsed)
        }
    }

    // Commit on the way out, too. onFocusChanged only fires when the field is
    // genuinely unfocused; leaving Settings with the keyboard still up disposes
    // this composable without any focus event, so the edit was being dropped
    // silently. rememberUpdatedState keeps the lambda pointing at the latest
    // draft rather than the one captured at first composition.
    val commitOnExit by rememberUpdatedState { commit() }
    DisposableEffect(Unit) { onDispose { commitOnExit() } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Body weight", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = "For W/kg and XP", style = CadenceType.small, color = CadenceColors.Fg3)
            }

            Spacer(modifier = Modifier.width(16.dp))

            androidx.compose.foundation.text.BasicTextField(
                value = weightField,
                onValueChange = { newValue ->
                    val filtered = buildString {
                        var seenDot = false
                        for (c in newValue.text.take(6)) {
                            if (c.isDigit()) append(c)
                            else if ((c == '.' || c == ',') && !seenDot) {
                                append('.')
                                seenDot = true
                            }
                        }
                    }
                    weightField =
                        if (filtered == newValue.text) newValue
                        else TextFieldValue(filtered, TextRange(filtered.length))
                    persistIfValid(filtered)
                },
                textStyle = CadenceType.bodyStrong.copy(
                    fontSize = 18.sp,
                    color = CadenceColors.Fg1,
                    textAlign = TextAlign.End
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { commit() }
                ),
                singleLine = true,
                modifier = Modifier
                    .width(112.dp)
                    .onFocusChanged { state ->
                        focused = state.isFocused
                        if (!state.isFocused) commit()
                    },
                decorationBox = { innerTextField ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.md))
                            .background(CadenceColors.Bg2)
                            .border(2.dp, focusRingColor(focused), RoundedCornerShape(Radii.md))
                            .padding(horizontal = Spacing.s4, vertical = 10.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            innerTextField()
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "kg",
                            style = CadenceType.body.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = CadenceColors.Fg3
                        )
                    }
                }
            )
        }
    }
}

/**
 * The 2 dp focus ring, faded in and out rather than switched: the ring
 * arriving is what tells the rider the field took the tap, and a fade reads
 * as the field lighting up where a snap reads as a redraw.
 */
@Composable
private fun focusRingColor(focused: Boolean): Color {
    val color by animateColorAsState(
        targetValue = if (focused) CadenceColors.Fg1 else Color.Transparent,
        animationSpec = tween(SlateMotion.Fast, easing = SlateMotion.Easing),
        label = "focusRing",
    )
    return color
}

private fun formatWeightForInput(kg: Float): String {
    val rounded = kotlin.math.round(kg * 10f) / 10f
    return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
}

@Composable
private fun FtpRow() {
    val container = LocalAppContainer.current
    val userSettings = container.userSettings
    val ftp by userSettings.ftp.collectAsStateWithLifecycle()

    // Unkeyed on purpose. Keying the buffer on the stored value re-created it
    // every time we wrote through, which yanks the cursor to the end mid-edit.
    // Instead the field re-syncs from storage only while the rider isn't typing
    // in it, which is the only time an external change (onboarding, a restore)
    // should be allowed to move it.
    var ftpField by remember { mutableStateOf(TextFieldValue(ftp.toString())) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(ftp, focused) { if (!focused) ftpField = TextFieldValue(ftp.toString()) }
    // Select the whole value once focus has settled, so typing replaces it.
    // Done from an effect rather than inside onFocusChanged because the IME
    // sets its own selection immediately after focus is granted and wins the
    // race; by the next frame it has finished and this sticks. Without it the
    // caret lands inside the existing number, so a rider typing 250 into a
    // field showing 265 gets 2250 - over the limit, and thrown away on commit
    // with no explanation.
    LaunchedEffect(focused) {
        if (focused) {
            ftpField = ftpField.copy(selection = TextRange(0, ftpField.text.length))
        }
    }

    fun commit() {
        val parsed = ftpField.text.toIntOrNull()
        if (parsed == null || parsed !in app.indoorbike.shared.settings.MIN_FTP..app.indoorbike.shared.settings.MAX_FTP) {
            ftpField = TextFieldValue(ftp.toString())
        } else if (parsed != ftp) {
            userSettings.setFtp(parsed)
        }
    }

    /** See [WeightRow]: persist on the keystroke so no exit path can lose it. */
    fun persistIfValid(text: String) {
        val parsed = text.toIntOrNull() ?: return
        if (parsed in app.indoorbike.shared.settings.MIN_FTP..app.indoorbike.shared.settings.MAX_FTP && parsed != ftp) {
            userSettings.setFtp(parsed)
        }
    }

    // Commit on the way out, too. onFocusChanged only fires when the field is
    // genuinely unfocused; leaving Settings with the keyboard still up disposes
    // this composable without any focus event, so the edit was being dropped
    // silently. rememberUpdatedState keeps the lambda pointing at the latest
    // draft rather than the one captured at first composition.
    val commitOnExit by rememberUpdatedState { commit() }
    DisposableEffect(Unit) { onDispose { commitOnExit() } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "FTP", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = "Sets your power zones", style = CadenceType.small, color = CadenceColors.Fg3)
            }

            Spacer(modifier = Modifier.width(16.dp))

            androidx.compose.foundation.text.BasicTextField(
                value = ftpField,
                onValueChange = { newValue ->
                    val cleaned = newValue.text.filter { it.isDigit() }.take(4)
                    ftpField =
                        if (cleaned == newValue.text) newValue
                        else TextFieldValue(cleaned, TextRange(cleaned.length))
                    persistIfValid(cleaned)
                },
                textStyle = CadenceType.bodyStrong.copy(
                    fontSize = 18.sp,
                    color = CadenceColors.Fg1,
                    textAlign = TextAlign.End
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { commit() }
                ),
                singleLine = true,
                modifier = Modifier
                    .width(112.dp)
                    .onFocusChanged { state ->
                        focused = state.isFocused
                        if (!state.isFocused) commit()
                    },
                decorationBox = { innerTextField ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.md))
                            .background(CadenceColors.Bg2)
                            .border(2.dp, focusRingColor(focused), RoundedCornerShape(Radii.md))
                            .padding(horizontal = Spacing.s4, vertical = 10.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            innerTextField()
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "W",
                            style = CadenceType.body.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = CadenceColors.Fg3
                        )
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
    val maxHr by userSettings.maxHr.collectAsStateWithLifecycle()

    // Unkeyed on purpose. Keying the buffer on the stored value re-created it
    // every time we wrote through, which yanks the cursor to the end mid-edit.
    // Instead the field re-syncs from storage only while the rider isn't typing
    // in it, which is the only time an external change (onboarding, a restore)
    // should be allowed to move it.
    var hrField by remember { mutableStateOf(TextFieldValue(maxHr.toString())) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(maxHr, focused) { if (!focused) hrField = TextFieldValue(maxHr.toString()) }
    // Select the whole value once focus has settled, so typing replaces it.
    // Done from an effect rather than inside onFocusChanged because the IME
    // sets its own selection immediately after focus is granted and wins the
    // race; by the next frame it has finished and this sticks. Without it the
    // caret lands inside the existing number, so a rider typing 250 into a
    // field showing 265 gets 2250 - over the limit, and thrown away on commit
    // with no explanation.
    LaunchedEffect(focused) {
        if (focused) {
            hrField = hrField.copy(selection = TextRange(0, hrField.text.length))
        }
    }

    fun commit() {
        val parsed = hrField.text.toIntOrNull()
        if (parsed == null || parsed !in app.indoorbike.shared.settings.MIN_MAX_HR..app.indoorbike.shared.settings.MAX_MAX_HR) {
            hrField = TextFieldValue(maxHr.toString())
        } else if (parsed != maxHr) {
            userSettings.setMaxHr(parsed)
        }
    }

    /** See [WeightRow]: persist on the keystroke so no exit path can lose it. */
    fun persistIfValid(text: String) {
        val parsed = text.toIntOrNull() ?: return
        if (parsed in app.indoorbike.shared.settings.MIN_MAX_HR..app.indoorbike.shared.settings.MAX_MAX_HR && parsed != maxHr) {
            userSettings.setMaxHr(parsed)
        }
    }

    // Commit on the way out, too. onFocusChanged only fires when the field is
    // genuinely unfocused; leaving Settings with the keyboard still up disposes
    // this composable without any focus event, so the edit was being dropped
    // silently. rememberUpdatedState keeps the lambda pointing at the latest
    // draft rather than the one captured at first composition.
    val commitOnExit by rememberUpdatedState { commit() }
    DisposableEffect(Unit) { onDispose { commitOnExit() } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Max heart rate", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Colours the live HR by zone",
                    style = CadenceType.small,
                    color = CadenceColors.Fg3
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            androidx.compose.foundation.text.BasicTextField(
                value = hrField,
                onValueChange = { newValue ->
                    val cleaned = newValue.text.filter { it.isDigit() }.take(3)
                    hrField =
                        if (cleaned == newValue.text) newValue
                        else TextFieldValue(cleaned, TextRange(cleaned.length))
                    persistIfValid(cleaned)
                },
                textStyle = CadenceType.bodyStrong.copy(
                    fontSize = 18.sp,
                    color = CadenceColors.Fg1,
                    textAlign = TextAlign.End
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(CadenceColors.Fg1),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
                singleLine = true,
                modifier = Modifier
                    .width(112.dp)
                    .onFocusChanged { state ->
                        focused = state.isFocused
                        if (!state.isFocused) commit()
                    },
                decorationBox = { innerTextField ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.md))
                            .background(CadenceColors.Bg2)
                            .border(2.dp, focusRingColor(focused), RoundedCornerShape(Radii.md))
                            .padding(horizontal = Spacing.s4, vertical = 10.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            innerTextField()
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "bpm",
                            style = CadenceType.body.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = CadenceColors.Fg3
                        )
                    }
                }
            )
        }
    }
}

// ─── About ────────────────────────────────────────────────────────────────────

@Composable
private fun AboutCard() {
    SlateCard(padding = PaddingValues(Spacing.s5)) {
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = CadenceType.body, color = CadenceColors.Fg2)
        Text(text = value, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
    }
}

// ─── Feedback · report a problem ──────────────────────────────────────────────

/**
 * Drafts a bug-report email to the developer. The card text discloses up front
 * what's collected (device info + diagnostic logs, no personal data) and that
 * nothing is sent automatically; the confirmation dialog repeats it before the
 * email app actually opens.
 */
@Composable
private fun ReportProblemCard() {
    var showDialog by remember { mutableStateOf(false) }

    // One tappable row. The what-gets-collected disclosure lives in the
    // confirmation dialog, right before anything happens - which is where a
    // rider actually reads it. A three-paragraph card plus a full-width
    // button was Settings' single biggest block of text.
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .clickable { showDialog = true }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CadenceColors.Bg2),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.BugReport,
                contentDescription = null,
                tint = CadenceColors.Fg1,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Report a problem", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
            Text(
                text = "Drafts an email with diagnostics · no personal data",
                style = CadenceType.small,
                color = CadenceColors.Fg3
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = CadenceColors.Fg4,
            modifier = Modifier.size(20.dp)
        )
    }

    if (showDialog) {
        ReportProblemDialog(
            errorContext = null,
            onDismiss = { showDialog = false }
        )
    }
}

// ─── Cloud Sync · Intervals.icu ──────────────────────────────────────────────

/**
 * Intervals.icu: connect in the browser, or, in a build that cannot, paste an
 * API key.
 *
 * Connecting is the normal way in. The rider approves Indoor Bike on
 * Intervals.icu's own page, the app gets a token that can upload rides and
 * nothing else, and the rider can take it back from either end. Intervals.icu
 * asks apps used by more than one person to work this way. The API key is kept
 * for builds from source, which do not carry the OAuth client (see
 * [app.indoorbike.shared.sync.IntervalsIcuOAuth]), and for a rider who set one
 * up before connecting existed: their fields stay visible until they connect,
 * which replaces the key.
 *
 * Nothing here contacts Intervals.icu by existing. Connecting, disconnecting and
 * uploading do, and each is something the rider did.
 */
@Composable
internal fun IntervalsIcuCard() {
    val container = LocalAppContainer.current
    val settings = container.userSettings
    val uploader = container.intervalsIcuUploader
    val context = LocalContext.current

    val athleteId by settings.intervalsAthleteId.collectAsStateWithLifecycle()
    val apiKey by settings.intervalsApiKey.collectAsStateWithLifecycle()
    val autoUpload by settings.intervalsAutoUpload.collectAsStateWithLifecycle()
    val token by settings.intervalsAccessToken.collectAsStateWithLifecycle()
    val athleteName by settings.intervalsAthleteName.collectAsStateWithLifecycle()
    val signedIn = !token.isNullOrBlank()
    val keyConfigured = !athleteId.isNullOrBlank() && !apiKey.isNullOrBlank()
    val configured = signedIn || keyConfigured
    val signInAvailable = uploader.signInAvailable

    var showKey by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // A key already saved stays in view, or a rider who has one could not see
    // or clear it. Otherwise a build that can sign in does not offer the key at
    // all: Intervals.icu asks apps used by more than one person to use OAuth,
    // and a second way in that nobody needs is a second way to get it wrong.
    val keyFieldsVisible = !signedIn && (!signInAvailable || keyConfigured)

    ExpandableCard(
        icon = Icons.Outlined.CloudUpload,
        iconTint = CadenceColors.Fg1,
        title = "Intervals.icu",
        statusText = when {
            signedIn && autoUpload -> "Connected · auto-upload on"
            signedIn -> "Connected · manual upload"
            keyConfigured && autoUpload -> "API key · auto-upload on"
            keyConfigured -> "API key · manual upload"
            else -> "Not connected · optional"
        },
        statusColor = if (configured) CadenceColors.Success else CadenceColors.Fg3,
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        Text(
            text = "Send your finished rides to Intervals.icu. Nothing is contacted until you " +
                "connect, and after that only when a ride is uploaded.",
            style = CadenceType.small,
            color = CadenceColors.Fg3
        )

        if (signedIn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = CadenceColors.Success,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = athleteName?.let { "Connected as $it" } ?: "Connected",
                    style = CadenceType.tick,
                    color = CadenceColors.Success
                )
            }
        } else if (signInAvailable) {
            Text(
                text = "You approve Indoor Bike on Intervals.icu in your browser, so your password " +
                    "and API key never reach this app. It can upload rides and nothing else, and " +
                    "you can disconnect it here or in your Intervals.icu settings.",
                style = CadenceType.tick,
                color = CadenceColors.Fg3
            )
            PrimaryBtn(
                text = "Connect Intervals.icu",
                leadingIcon = null,
                onClick = {
                    val authorize = uploader.authorizeUrl() ?: return@PrimaryBtn
                    try {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(authorize)
                            )
                        )
                    } catch (t: Throwable) {
                        Toast.makeText(context, "No browser to open", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (keyConfigured) {
                Text(
                    text = "Connecting replaces the API key saved on this phone.",
                    style = CadenceType.tick,
                    color = CadenceColors.Fg3
                )
            }
        }

        if (keyFieldsVisible) {
            IntervalsTextField(
                label = "Athlete ID",
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
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Auto-upload finished rides", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Text(
                    text = when {
                        configured -> "Each completed ride is sent immediately."
                        signInAvailable -> "Connect above to enable."
                        else -> "Add credentials above to enable."
                    },
                    style = CadenceType.small,
                    color = CadenceColors.Fg3
                )
            }
            Switch(
                // Always operable, and showing the stored value rather than one
                // masked by `configured`. Gating this on having credentials
                // meant a rider who had auto-upload on and then cleared (or
                // never finished entering) their details was left with a switch
                // that said off, was greyed out, and could not be turned off.
                checked = autoUpload,
                onCheckedChange = { settings.setIntervalsAutoUpload(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CadenceColors.OnBrand,
                    checkedTrackColor = CadenceColors.Fg1,
                    uncheckedThumbColor = CadenceColors.Fg3,
                    uncheckedTrackColor = CadenceColors.Bg2,
                    uncheckedBorderColor = CadenceColors.Line2,
                )
            )
        }

        if (signedIn) {
            SecondaryBtn(
                text = "Disconnect",
                onClick = {
                    // On the app's scope: leaving the screen must not stop
                    // the revoke half way.
                    val appContext = context.applicationContext
                    container.appScope.launch {
                        val revoked = uploader.disconnect()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                appContext,
                                if (revoked) "Disconnected from Intervals.icu"
                                else "Disconnected on this phone. Intervals.icu couldn't be reached, so " +
                                    "remove Indoor Bike in your Intervals.icu settings as well.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun IntervalsTextField(
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = CadenceType.body.copy(color = CadenceColors.Fg1),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(CadenceColors.Fg1),
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
 * Nothing here triggers a network call from the app itself: the rider's
 * chosen storage provider does any uploading on the SAF side. No accounts,
 * no servers, no telemetry.
 */
@Composable
private fun BackupSyncCard() {
    val container = LocalAppContainer.current
    val settings = container.userSettings
    val engine = container.syncEngine
    val context = LocalContext.current

    val syncFolderUri by settings.syncFolderUri.collectAsStateWithLifecycle()
    val deviceLabel by settings.deviceLabel.collectAsStateWithLifecycle()
    val status by engine.status.collectAsStateWithLifecycle()
    val paused by settings.syncPaused.collectAsStateWithLifecycle()
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
        //
        // Sync, restore and keep-local all run on the app's scope. Only the
        // start tab and the current one are composed, so on this screen's
        // scope a tab switch cancelled a sync part way, left a restore half
        // applied, and reported "Couldn't sync".
        container.appScope.launch { engine.syncNow() }
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    // A paused / failed sync needs the rider's attention - surface the body
    // instead of hiding the review controls behind a collapsed row.
    LaunchedEffect(paused, status) {
        if (paused || status is SyncStatus.NeedsReview || status is SyncStatus.Failed) {
            expanded = true
        }
    }
    val statusNow = status
    val (headerStatus, headerColor) = when {
        !configured -> "Not configured · optional" to CadenceColors.Fg3
        paused || statusNow is SyncStatus.NeedsReview ->
            "Paused · needs review" to CadenceColors.Warning
        statusNow is SyncStatus.Syncing -> "Syncing…" to CadenceColors.Fg1
        statusNow is SyncStatus.Failed -> "Couldn't sync" to CadenceColors.Danger
        statusNow is SyncStatus.Success ->
            "Synced ${formatRelativeTime(statusNow.syncedAtEpoch)}" to CadenceColors.Success
        else -> "Configured" to CadenceColors.Success
    }

    ExpandableCard(
        icon = Icons.Outlined.Folder,
        iconTint = CadenceColors.Fg1,
        title = "Backup & sync",
        statusText = headerStatus,
        statusColor = headerColor,
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        Text(
            text = "Pick any folder on your device or in cloud storage - Drive, Dropbox, " +
                "Nextcloud, Syncthing, OneDrive, anything that exposes itself as a folder. " +
                "Each device writes one small JSON file there; other devices pointed at the " +
                "same folder share your rides, library, and settings. No accounts, no servers " +
                "- just a file in a folder you already trust.",
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
                    onRestore = { container.appScope.launch { engine.resolveRestoreFromBackup() } },
                    onKeepLocal = { container.appScope.launch { engine.resolveKeepLocal() } }
                )
            } else {
                // "Sync now" is the action a rider reaches for 99% of the time;
                // give it the full row.
                PrimaryBtn(
                    text = if (status is SyncStatus.Syncing) "Syncing…" else "Sync now",
                    leadingIcon = null,
                    enabled = status !is SyncStatus.Syncing,
                    onClick = { container.appScope.launch { engine.syncNow() } },
                    modifier = Modifier.fillMaxWidth()
                )
                // Pull the folder's backup onto this device (additive - never
                // deletes local rides). Handy after an update wiped app data.
                Text(
                    text = "Restore from backup",
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Fg1,
                    modifier = Modifier
                        .clickable(enabled = status !is SyncStatus.Syncing) {
                            container.appScope.launch { engine.resolveRestoreFromBackup() }
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
                        "and library stay put - slices in the folder aren't deleted.",
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
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Text(
            text = "⚠ Backup paused",
            style = CadenceType.bodyStrong,
            color = CadenceColors.Warning
        )
        // Either side can be the one that lost data: this device, reset, or
        // another device whose deletes would remove most of the rides here.
        val detail = if (review != null) {
            "This device has ${review.localSessions} ride(s) and ${review.localImports} " +
                "workout(s); the backup has ${review.backupSessions} ride(s) and " +
                "${review.backupImports} workout(s). They disagree about too many at once, which " +
                "usually means one device's data was reset. Backup is paused so nothing is deleted " +
                "by mistake. Choose what to do:"
        } else {
            "This device and the backup disagree about too many rides at once. Backup is paused " +
                "so nothing is deleted by mistake. Choose what to do:"
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
            text = "Makes this device match the backup: rides, workouts and settings missing here " +
                "come back, and anything the backup has removed is removed here too. Recommended " +
                "if this device's data was lost by accident.",
            style = CadenceType.tick,
            color = CadenceColors.Fg3
        )

        SecondaryBtn(
            text = "Keep this device's data",
            onClick = { if (!busy) onKeepLocal() },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            // It used to say only that the backup would change. It also
            // reaches every other device: rides this one does not have are
            // removed from them at their next sync.
            text = "Keeps every ride and workout on this device as it is and updates the backup to " +
                "match. Rides this device doesn't have are removed from the backup, and from your " +
                "other devices when they next sync. Use only if that is what you want.",
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
            Text(text = "Folder", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
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
            color = CadenceColors.Fg1,
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

    // Same commit-on-dispose guard as the profile rows: a name typed and then
    // navigated away from was being lost.
    val commitOnExit by rememberUpdatedState {
        if (draft.isNotBlank() && draft != value) onValueChange(draft)
    }
    DisposableEffect(Unit) { onDispose { commitOnExit() } }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Device name", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.sm))
                .background(CadenceColors.Bg2)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    // Same reasoning as the profile rows: persist immediately
                    // so leaving the screen can't discard the new name.
                    if (it.isNotBlank() && it != value) onValueChange(it)
                },
                singleLine = true,
                textStyle = CadenceType.body.copy(color = CadenceColors.Fg1),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(CadenceColors.Fg1),
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
        SyncStatus.Syncing -> "Syncing…" to CadenceColors.Fg1
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
