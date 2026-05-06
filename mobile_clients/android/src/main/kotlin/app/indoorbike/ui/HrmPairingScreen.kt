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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MonitorHeart
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
import app.indoorbike.shared.ble.HrmCandidate
import app.indoorbike.shared.ble.HrmConnectionState
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

@Composable
fun HrmPairingScreen(
    onBack: () -> Unit
) {
    val container = LocalAppContainer.current
    val hrm = container.heartRateMonitor
    val connectionState by hrm.connectionState.collectAsState()
    val discoveredDevices by hrm.discoveredDevices.collectAsState()

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
            title = "Connect HRM",
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
                .fillMaxSize()
                .padding(horizontal = Spacing.s5)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(Spacing.s6))

            when (connectionState) {
                HrmConnectionState.Disconnected, HrmConnectionState.Error -> {
                    Icon(
                        imageVector = Icons.Outlined.MonitorHeart,
                        contentDescription = null,
                        tint = CadenceColors.Danger.copy(alpha = 0.6f),
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    Text(
                        text = "Find Your HRM",
                        style = CadenceType.h2,
                        color = CadenceColors.Fg1,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    Text(
                        text = "Wake your heart-rate strap (often by snapping it on your chest) so it broadcasts over Bluetooth.",
                        style = CadenceType.body,
                        color = CadenceColors.Fg3,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.s6))

                    if (connectionState == HrmConnectionState.Error) {
                        Text(
                            text = "Connection failed. Please try again.",
                            style = CadenceType.bodyStrong,
                            color = CadenceColors.Danger,
                            modifier = Modifier.padding(bottom = Spacing.s4)
                        )
                    }

                    PrimaryBtn(
                        text = "Scan for HRMs",
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

                HrmConnectionState.Scanning -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
                    ) {
                        CircularProgressIndicator(
                            color = CadenceColors.Danger,
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
                            text = "Looking for nearby heart-rate monitors…",
                            style = CadenceType.body,
                            color = CadenceColors.Fg3,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        DeviceList(discoveredDevices) { c -> hrm.connectToCandidate(c.id) }
                    }
                }

                HrmConnectionState.Connecting -> {
                    CircularProgressIndicator(
                        color = CadenceColors.Danger,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    Text("Connecting…", style = CadenceType.h3, color = CadenceColors.Fg1)
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    Text(
                        text = "Establishing connection with your HRM.",
                        style = CadenceType.body,
                        color = CadenceColors.Fg3,
                        textAlign = TextAlign.Center
                    )
                }

                HrmConnectionState.Disconnecting -> {
                    CircularProgressIndicator(
                        color = CadenceColors.Fg3,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    Text("Disconnecting…", style = CadenceType.h3, color = CadenceColors.Fg1)
                }

                HrmConnectionState.Connected -> {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        tint = CadenceColors.Success,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    Text("Connected", style = CadenceType.h3, color = CadenceColors.Fg1)
                }
            }
        }
    }
}

@Composable
private fun DeviceList(devices: List<HrmCandidate>, onConnect: (HrmCandidate) -> Unit) {
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
                    imageVector = Icons.Outlined.MonitorHeart,
                    contentDescription = null,
                    tint = CadenceColors.Danger,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name ?: "Unknown HRM",
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
