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
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MonitorHeart
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
import app.indoorbike.shared.ble.HrmCandidate
import app.indoorbike.shared.ble.HrmConnectionState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HrmPairingScreen(
    onBack: () -> Unit
) {
    val container = LocalAppContainer.current
    val hrm = container.heartRateMonitor
    val connectionState by hrm.connectionState.collectAsStateWithLifecycle()
    val discoveredDevices by hrm.discoveredDevices.collectAsStateWithLifecycle()

    LaunchedEffect(connectionState) {
        if (connectionState == HrmConnectionState.Connected) {
            container.userSettings.setPairedHrmId(hrm.pairedDeviceId.value)
            onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (hrm.connectionState.value == HrmConnectionState.Scanning) hrm.stopScan()
        }
    }

    var permissionError by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            permissionError = false
            hrm.startScan()
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
            title = "Connect a monitor",
            eyebrow = "Pairing",
            leading = {
                IconBtn(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack
                )
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
                HrmConnectionState.Disconnected, HrmConnectionState.Error -> {
                    ScanStatusCard(
                        icon = Icons.Outlined.MonitorHeart,
                        title = "Find your monitor",
                        helper = "Wake your heart-rate strap (often by snapping it on " +
                            "your chest) so it broadcasts over Bluetooth.",
                        scanning = false
                    )
                    if (connectionState == HrmConnectionState.Error) {
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
                                "Location permission is required for Bluetooth scanning on this Android version."
                            },
                            color = CadenceColors.Danger,
                            style = CadenceType.small,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                HrmConnectionState.Scanning -> {
                    ScanStatusCard(
                        icon = Icons.Outlined.MonitorHeart,
                        title = "Scanning · ${discoveredDevices.size} found",
                        helper = "Looking for nearby heart-rate monitors…",
                        scanning = true
                    )
                    if (discoveredDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.s3))
                        DeviceList(discoveredDevices) { c -> hrm.connectToCandidate(c.id) }
                    }
                }

                HrmConnectionState.Connecting -> {
                    PairingProgress(
                        title = "Connecting…",
                        helper = "Establishing connection with your HRM."
                    )
                }

                HrmConnectionState.Disconnecting -> {
                    PairingProgress(title = "Disconnecting…", helper = null)
                }

                HrmConnectionState.Connected -> {
                    ScanStatusCard(
                        icon = Icons.Outlined.FavoriteBorder,
                        title = "Connected",
                        helper = "Your monitor is sending heart rate.",
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
                HrmConnectionState.Disconnected, HrmConnectionState.Error -> {
                    PrimaryBtn(
                        text = "Scan for monitors",
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
                                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
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

                HrmConnectionState.Scanning -> {
                    SecondaryBtn(
                        text = "Stop scanning",
                        onClick = { hrm.stopScan() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun DeviceList(devices: List<HrmCandidate>, onConnect: (HrmCandidate) -> Unit) {
    // Strongest signal first: the nearest device is almost always the one in
    // the room with the rider, and it gets the white Connect pill to say so.
    val ordered = remember(devices) { devices.sortedByDescending { it.rssi ?: Int.MIN_VALUE } }
    ListCard {
        ordered.forEachIndexed { index, device ->
            PairingDeviceRow(
                icon = Icons.Outlined.MonitorHeart,
                name = device.name ?: "Unknown monitor",
                subtitle = signalLabel(device.rssi) ?: device.id,
                primary = index == 0,
                onConnect = { onConnect(device) }
            )
            if (index != ordered.lastIndex) CardDivider()
        }
    }
}
