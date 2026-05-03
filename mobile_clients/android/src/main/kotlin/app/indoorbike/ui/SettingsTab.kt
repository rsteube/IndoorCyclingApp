package app.indoorbike.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.components.TrainerStatusStrip
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

@Composable
fun SettingsTab(onNavigateToPairing: () -> Unit) {
    val connectionState by TrainerManager.connectionState.collectAsState()
    val connectedTrainerName by TrainerManager.connectedTrainerName.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
            .safeDrawingPadding()
    ) {
        AppBar(
            title = "Trainer Settings",
            eyebrow = "Preferences"
        )
        
        Spacer(modifier = Modifier.height(Spacing.s5))

        Column(modifier = Modifier.padding(horizontal = Spacing.s5).fillMaxWidth()) {
            
            // Current Status Panel
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

            // User Settings Panel
            Text(
                text = "USER PROFILE",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3,
                modifier = Modifier.padding(bottom = Spacing.s3)
            )
            
            val ftp by app.indoorbike.UserSettings.ftp.collectAsState()
            var ftpInput by remember(ftp) { mutableStateOf(ftp.toString()) }
            
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
                            val filtered = newValue.filter { it.isDigit() }.take(4)
                            ftpInput = filtered
                            val newFtp = filtered.toIntOrNull()
                            if (newFtp != null && newFtp > 0) {
                                app.indoorbike.UserSettings.setFtp(newFtp)
                            }
                        },
                        textStyle = CadenceType.h2.copy(color = CadenceColors.Brand500, textAlign = TextAlign.End),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
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
            
            Spacer(modifier = Modifier.height(Spacing.s6))

            // Action Buttons
            when (connectionState) {
                TrainerConnectionState.Connected, 
                TrainerConnectionState.Connecting, 
                TrainerConnectionState.Disconnecting -> {
                    SecondaryBtn(
                        text = if (connectionState == TrainerConnectionState.Connecting) "Cancel Connection" else "Disconnect",
                        onClick = { TrainerManager.disconnect() },
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
        }
    }
}
