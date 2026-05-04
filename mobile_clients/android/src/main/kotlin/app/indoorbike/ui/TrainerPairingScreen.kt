package app.indoorbike.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.ble.TrainerCandidate
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

@Composable
fun TrainerPairingScreen(
    onBack: () -> Unit
) {
    val container = LocalAppContainer.current
    val trainerManager = container.trainerManager
    val connectionState by trainerManager.connectionState.collectAsState()
    val discoveredDevices by trainerManager.discoveredDevices.collectAsState()

    LaunchedEffect(connectionState) {
        if (connectionState == TrainerConnectionState.Connected) {
            // Persist the paired ID so the next launch auto-connects.
            val id = trainerManager.pairedTrainerId.value
            if (id != null && id != "mock") container.userSettings.setPairedTrainerId(id)
            onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (trainerManager.connectionState.value == TrainerConnectionState.Scanning) {
                trainerManager.stopScan()
            }
        }
    }

    var permissionError by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            permissionError = false
            trainerManager.startScan()
        } else {
            permissionError = true
        }
    }

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
    ) {
        AppBar(
            title = "Connect Trainer",
            eyebrow = "Pairing",
            leading = {
                IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
            }
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.s5)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(Spacing.s6))

            when (connectionState) {
                TrainerConnectionState.Disconnected, TrainerConnectionState.Error -> {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.BluetoothSearching,
                        contentDescription = null,
                        tint = CadenceColors.Brand500.copy(alpha = 0.5f),
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    Text(
                        text = "Find Your Trainer",
                        style = CadenceType.h2,
                        color = CadenceColors.Fg1,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    Text(
                        text = "Make sure your smart trainer is plugged in and awake.",
                        style = CadenceType.body,
                        color = CadenceColors.Fg3,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.s6))
                    
                    if (connectionState == TrainerConnectionState.Error) {
                        Text(
                            text = "Connection failed. Please try again.",
                            style = CadenceType.bodyStrong,
                            color = CadenceColors.Danger,
                            modifier = Modifier.padding(bottom = Spacing.s4)
                        )
                    }

                    PrimaryBtn(
                        text = "Scan for Devices",
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
                    Spacer(modifier = Modifier.height(Spacing.s4))
                    SecondaryBtn(
                        text = "Use Mock Trainer",
                        onClick = { trainerManager.connectMock() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    if (permissionError) {
                        Spacer(modifier = Modifier.height(Spacing.s3))
                        Text(
                            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                "Bluetooth permissions are required to scan."
                            } else {
                                "Location permissions are required for Bluetooth scanning on this Android version."
                            },
                            color = CadenceColors.Danger,
                            style = CadenceType.small,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(Spacing.s3))
                        SecondaryBtn(
                            text = "Open Android Settings",
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                TrainerConnectionState.Scanning -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
                    ) {
                        CircularProgressIndicator(
                            color = CadenceColors.Brand500,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = "Scanning · ${discoveredDevices.size} found",
                            style = CadenceType.h3,
                            color = CadenceColors.Fg1
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.s5))

                    if (discoveredDevices.isEmpty()) {
                        Text(
                            text = "Looking for nearby smart trainers...",
                            style = CadenceType.body,
                            color = CadenceColors.Fg3,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        DeviceList(discoveredDevices) { candidate ->
                            trainerManager.connectToCandidate(candidate.id)
                        }
                    }
                }
                
                TrainerConnectionState.Connecting -> {
                    CircularProgressIndicator(
                        color = CadenceColors.Brand500,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    Text(
                        text = "Connecting...",
                        style = CadenceType.h3,
                        color = CadenceColors.Fg1
                    )
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    Text(
                        text = "Establishing connection with your trainer.",
                        style = CadenceType.body,
                        color = CadenceColors.Fg3,
                        textAlign = TextAlign.Center
                    )
                }
                
                TrainerConnectionState.Disconnecting -> {
                    // Won't normally happen here unless manual disconnect triggered elsewhere
                    CircularProgressIndicator(
                        color = CadenceColors.Fg3,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    Text(
                        text = "Disconnecting...",
                        style = CadenceType.h3,
                        color = CadenceColors.Fg1
                    )
                }
                
                TrainerConnectionState.Connected -> {
                    // Handled by LaunchedEffect, but provide a state just in case
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.BluetoothSearching,
                        contentDescription = null,
                        tint = CadenceColors.Success,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    Text(
                        text = "Connected Successfully",
                        style = CadenceType.h3,
                        color = CadenceColors.Fg1
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceList(devices: List<TrainerCandidate>, onConnect: (TrainerCandidate) -> Unit) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(devices) { device ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.md))
                    .background(CadenceColors.Bg1)
                    .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
                    .clickable { onConnect(device) }
                    .padding(Spacing.s4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.BluetoothSearching,
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
                        text = device.id,
                        style = CadenceType.small,
                        color = CadenceColors.Fg3
                    )
                }
            }
        }
    }
}
