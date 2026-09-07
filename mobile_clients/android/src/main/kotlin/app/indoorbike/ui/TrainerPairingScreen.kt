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

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Search
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
import app.indoorbike.ui.components.CardDivider
import app.indoorbike.ui.components.ListCard
import app.indoorbike.ui.components.PairingDeviceRow
import app.indoorbike.ui.components.PairingProgress
import app.indoorbike.ui.components.ScanStatusCard
import app.indoorbike.ui.components.signalLabel
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.theme.SlateMotion
import app.indoorbike.ui.theme.enterReveal
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween

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
            title = "Connect a trainer",
            eyebrow = "Pairing",
            leading = {
                IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
            }
        )
        
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s5),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(Spacing.s5))

            when (connectionState) {
                TrainerConnectionState.Disconnected, TrainerConnectionState.Error -> {
                    ScanStatusCard(
                        icon = Icons.AutoMirrored.Outlined.BluetoothSearching,
                        title = "Find your trainer",
                        helper = "Make sure your smart trainer is plugged in and awake.",
                        scanning = false
                    )
                    if (connectionState == TrainerConnectionState.Error) {
                        Spacer(modifier = Modifier.height(Spacing.s4))
                        Text(
                            text = "Connection failed. Please try again.",
                            style = CadenceType.bodyStrong,
                            color = CadenceColors.Danger
                        )
                    }
                    if (permissionError) {
                        Spacer(modifier = Modifier.height(Spacing.s4))
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
                    }
                }

                TrainerConnectionState.Scanning -> {
                    ScanStatusCard(
                        icon = Icons.AutoMirrored.Outlined.BluetoothSearching,
                        title = "Scanning · ${discoveredDevices.size} found",
                        helper = "Looking for nearby smart trainers…",
                        scanning = true
                    )
                    if (discoveredDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.s3))
                        DeviceList(discoveredDevices) { candidate ->
                            trainerManager.connectToCandidate(candidate.id)
                        }
                    }
                }

                TrainerConnectionState.Connecting -> {
                    PairingProgress(
                        title = "Connecting…",
                        helper = "Establishing connection with your trainer."
                    )
                }

                TrainerConnectionState.Disconnecting -> {
                    // Won't normally happen here unless manual disconnect triggered elsewhere
                    PairingProgress(title = "Disconnecting…", helper = null)
                }

                TrainerConnectionState.Connected -> {
                    // Handled by LaunchedEffect, but provide a state just in case
                    ScanStatusCard(
                        icon = Icons.AutoMirrored.Outlined.BluetoothSearching,
                        title = "Connected",
                        helper = "Your trainer is ready to ride.",
                        scanning = false
                    )
                }
            }
        }

        // The actions never scroll away: this screen exists to be acted on, and
        // in landscape the status card alone fills the height.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s5)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(top = Spacing.s3, bottom = Spacing.s6),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            when (connectionState) {
                TrainerConnectionState.Disconnected, TrainerConnectionState.Error -> {
                    PrimaryBtn(
                        text = "Scan for devices",
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
                    // The mock trainer is a debug-only affordance — hidden in
                    // release so users never see (or accidentally tap into) a
                    // dev tool.
                    if (app.indoorbike.BuildConfig.DEBUG) {
                        Spacer(modifier = Modifier.height(Spacing.s3))
                        SecondaryBtn(
                            text = "Use mock trainer",
                            onClick = { trainerManager.connectMock() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (permissionError) {
                        Spacer(modifier = Modifier.height(Spacing.s3))
                        SecondaryBtn(
                            text = "Open Android settings",
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
                    SecondaryBtn(
                        text = "Stop scanning",
                        onClick = { trainerManager.stopScan() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun DeviceList(devices: List<TrainerCandidate>, onConnect: (TrainerCandidate) -> Unit) {
    // Strongest signal first: the nearest device is almost always the one in
    // the room with the rider, and it gets the white Connect pill to say so.
    val ordered = remember(devices) { devices.sortedByDescending { it.rssi ?: Int.MIN_VALUE } }
    // Devices are found one at a time, and each new row settles in rather
    // than snapping into a list that was a row shorter a moment ago.
    ListCard(
        modifier = Modifier.animateContentSize(
            animationSpec = tween(SlateMotion.Slow, easing = SlateMotion.Easing)
        )
    ) {
        ordered.forEachIndexed { index, device ->
            androidx.compose.foundation.layout.Box(Modifier.enterReveal(index = index)) {
                PairingDeviceRow(
                    icon = Icons.AutoMirrored.Outlined.BluetoothSearching,
                    name = device.name ?: "Unknown Trainer",
                    subtitle = signalLabel(device.rssi) ?: device.id,
                    primary = index == 0,
                    onConnect = { onConnect(device) }
                )
            }
            if (index != ordered.lastIndex) CardDivider()
        }
    }
}
