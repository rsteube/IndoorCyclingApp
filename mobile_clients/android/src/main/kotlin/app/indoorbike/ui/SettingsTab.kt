package app.indoorbike.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.indoorbike.BuildConfig
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.components.TrainerStatusStrip
import app.indoorbike.ui.components.UnfinishedRideBanner
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

@Composable
fun SettingsTab(onNavigateToPairing: () -> Unit) {
    val container = LocalAppContainer.current
    val trainerManager = container.trainerManager
    val connectionState by trainerManager.connectionState.collectAsState()
    val connectedTrainerName by trainerManager.connectedTrainerName.collectAsState()
    val pairedTrainerId by trainerManager.pairedTrainerId.collectAsState()
    val isMockTrainer by trainerManager.isMockTrainer.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
    ) {
        AppBar(
            title = "Trainer Settings",
            eyebrow = "Preferences"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s5)
        ) {
            Spacer(modifier = Modifier.height(Spacing.s3))

            UnfinishedRideBanner()

            Spacer(modifier = Modifier.height(Spacing.s4))

            val isConnected = connectionState == TrainerConnectionState.Connected
            TrainerStatusStrip(
                deviceName = if (isConnected) (connectedTrainerName ?: "Unknown Trainer") else "No Trainer Connected",
                statusLine = when (connectionState) {
                    TrainerConnectionState.Scanning -> "Scanning for devices..."
                    TrainerConnectionState.Connecting -> "Connecting to trainer..."
                    TrainerConnectionState.Connected -> "Connected & Ready"
                    TrainerConnectionState.Disconnecting -> "Disconnecting..."
                    TrainerConnectionState.Disconnected -> "Disconnected"
                    TrainerConnectionState.Error -> "Error connecting. Try again."
                },
                icon = if (isConnected) Icons.Outlined.BluetoothConnected else Icons.Outlined.Bluetooth,
                connected = isConnected
            )

            Spacer(modifier = Modifier.height(Spacing.s5))

            Text(
                text = "USER PROFILE",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3,
                modifier = Modifier.padding(bottom = Spacing.s3)
            )

            FtpRow()

            Spacer(modifier = Modifier.height(Spacing.s3))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.md))
                    .background(CadenceColors.Bg1.copy(alpha = 0.6f))
                    .border(1.dp, CadenceColors.Line1, RoundedCornerShape(Radii.md))
                    .padding(14.dp)
            ) {
                Text(
                    text = "Don't know your FTP? Try a 20-minute all-out test ride and " +
                            "take 95 % of your average power as a starting estimate.",
                    style = CadenceType.small,
                    color = CadenceColors.Fg3
                )
            }

            Spacer(modifier = Modifier.height(Spacing.s6))

            // Action buttons
            when (connectionState) {
                TrainerConnectionState.Connected,
                TrainerConnectionState.Connecting,
                TrainerConnectionState.Disconnecting -> {
                    SecondaryBtn(
                        text = if (connectionState == TrainerConnectionState.Connecting) "Cancel Connection" else "Disconnect",
                        onClick = { trainerManager.disconnect() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    PrimaryBtn(
                        text = "Pair Trainer",
                        leadingIcon = Icons.AutoMirrored.Outlined.BluetoothSearching,
                        onClick = onNavigateToPairing,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (pairedTrainerId != null || isMockTrainer) {
                Spacer(modifier = Modifier.height(Spacing.s3))
                SecondaryBtn(
                    text = "Forget Trainer",
                    onClick = { trainerManager.forgetTrainer() },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(Spacing.s7))

            // About section
            Text(
                text = "ABOUT",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3,
                modifier = Modifier.padding(bottom = Spacing.s3)
            )
            AboutCard()

            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(modifier = Modifier.height(Spacing.s5))
        }
    }
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
            ftpInput = ftp.toString()  // revert to last good value
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
