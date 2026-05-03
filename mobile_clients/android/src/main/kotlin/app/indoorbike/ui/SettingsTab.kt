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
import androidx.compose.material.icons.outlined.BluetoothSearching
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
fun SettingsTab() {
    val connectionState by TrainerManager.connectionState.collectAsState()
    val discoveredDevices by TrainerManager.discoveredDevices.collectAsState()

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
                deviceName = if (isConnected) "Wahoo Kickr Core" else "No Trainer Connected",
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

            // Action Buttons
            var permissionError by remember { mutableStateOf(false) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val allGranted = permissions.entries.all { it.value }
                if (allGranted) {
                    permissionError = false
                    TrainerManager.startScan()
                } else {
                    permissionError = true
                }
            }

            when (connectionState) {
                TrainerConnectionState.Disconnected, TrainerConnectionState.Error -> {
                    PrimaryBtn(
                        text = "Scan for Trainers",
                        leadingIcon = Icons.Outlined.Search,
                        onClick = {
                            permissionError = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    )
                                )
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    if (permissionError) {
                        Text(
                            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                "Bluetooth permissions are required to scan. Please enable them in Android Settings."
                            } else {
                                "Location permission is required to scan on older Android versions. Please enable it in Android Settings."
                            },
                            style = CadenceType.small,
                            color = CadenceColors.Danger,
                            modifier = Modifier.padding(top = Spacing.s3).fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    } else if (connectionState == TrainerConnectionState.Error) {
                        Text(
                            text = "Failed to start scan. Ensure Bluetooth & Location are turned ON.",
                            style = CadenceType.small,
                            color = CadenceColors.Danger,
                            modifier = Modifier.padding(top = Spacing.s3).fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                TrainerConnectionState.Scanning -> {
                    SecondaryBtn(
                        text = "Stop Scanning",
                        onClick = { TrainerManager.stopScan() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    SecondaryBtn(
                        text = "Disconnect",
                        onClick = { TrainerManager.disconnect() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.s6))

            // Scanning UI / Device List
            if (connectionState == TrainerConnectionState.Scanning) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.s4)
                ) {
                    CircularProgressIndicator(color = CadenceColors.Brand500)
                    Text(
                        text = "Actively scanning for smart trainers...",
                        style = CadenceType.body,
                        color = CadenceColors.Fg2,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.s5))
            }

            if (discoveredDevices.isNotEmpty()) {
                Text(
                    text = "DISCOVERED DEVICES",
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg3,
                    modifier = Modifier.padding(bottom = Spacing.s3)
                )
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Spacing.s3),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(discoveredDevices) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radii.md))
                                .background(CadenceColors.Bg1)
                                .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
                                .clickable {
                                    TrainerManager.connectToDevice(device)
                                }
                                .padding(Spacing.s4),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.BluetoothSearching,
                                contentDescription = null,
                                tint = CadenceColors.Brand500,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.name ?: "Unknown Trainer",
                                    style = CadenceType.bodyStrong,
                                    color = CadenceColors.Fg1
                                )
                                Text(
                                    text = device.identifier,
                                    style = CadenceType.small,
                                    color = CadenceColors.Fg3
                                )
                            }
                        }
                    }
                }
            } else if (connectionState == TrainerConnectionState.Scanning) {
                Text(
                    text = "Ensure your trainer is plugged in and awake.",
                    style = CadenceType.small,
                    color = CadenceColors.Fg4,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s4)
                )
            }
        }
    }
}
