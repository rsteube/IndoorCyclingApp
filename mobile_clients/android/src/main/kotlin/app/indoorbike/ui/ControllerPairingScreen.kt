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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsRemote
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
import app.indoorbike.shared.ble.ControllerCandidate
import app.indoorbike.shared.ble.ControllerConnectionState
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

@Composable
fun ControllerPairingScreen(
    onBack: () -> Unit
) {
    val container = LocalAppContainer.current
    val controller = container.controllerManager
    val connectionState by controller.connectionState.collectAsState()
    val discoveredDevices by controller.discoveredDevices.collectAsState()

    LaunchedEffect(connectionState) {
        if (connectionState == ControllerConnectionState.Connected) {
            container.userSettings.setPairedControllerId(controller.pairedDeviceId.value)
            onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (controller.connectionState.value == ControllerConnectionState.Scanning) controller.stopScan()
        }
    }

    var permissionError by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            permissionError = false
            controller.startScan()
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
            title = "Connect Controller",
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
                ControllerConnectionState.Disconnected, ControllerConnectionState.Error -> {
                    Icon(
                        imageVector = Icons.Outlined.SettingsRemote,
                        contentDescription = null,
                        tint = CadenceColors.Brand500.copy(alpha = 0.6f),
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    Text(
                        text = "Find Your Controller",
                        style = CadenceType.h2,
                        color = CadenceColors.Fg1,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    Text(
                        // Click v1 wakes on the first button press; some firmware
                        // also wakes when battery is inserted. The user usually
                        // just needs to press a button to make it advertise.
                        text = "Wake your Zwift Click by pressing one of its buttons so it broadcasts over Bluetooth.",
                        style = CadenceType.body,
                        color = CadenceColors.Fg3,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.s6))

                    if (connectionState == ControllerConnectionState.Error) {
                        Text(
                            text = "Connection failed. Please try again.",
                            style = CadenceType.bodyStrong,
                            color = CadenceColors.Danger,
                            modifier = Modifier.padding(bottom = Spacing.s4)
                        )
                    }

                    PrimaryBtn(
                        text = "Scan for Controllers",
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

                    Spacer(modifier = Modifier.height(Spacing.s4))
                    Text(
                        // Most useful info on this screen besides the scan button.
                        text = "Supported: Zwift Click v1 (BC1). Encrypted Zwift Play / Ride controllers are not yet supported.",
                        style = CadenceType.small,
                        color = CadenceColors.Fg4,
                        textAlign = TextAlign.Center
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

                ControllerConnectionState.Scanning -> {
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
                            text = "Looking for nearby controllers… press a button on your Click to wake it.",
                            style = CadenceType.body,
                            color = CadenceColors.Fg3,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        DeviceList(discoveredDevices) { c -> controller.connectToCandidate(c.id) }
                    }
                }

                ControllerConnectionState.Connecting -> {
                    CircularProgressIndicator(
                        color = CadenceColors.Brand500,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    Text("Connecting…", style = CadenceType.h3, color = CadenceColors.Fg1)
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    Text(
                        text = "Establishing connection with your controller.",
                        style = CadenceType.body,
                        color = CadenceColors.Fg3,
                        textAlign = TextAlign.Center
                    )
                }

                ControllerConnectionState.Disconnecting -> {
                    CircularProgressIndicator(
                        color = CadenceColors.Fg3,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    Text("Disconnecting…", style = CadenceType.h3, color = CadenceColors.Fg1)
                }

                ControllerConnectionState.Connected -> {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
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
private fun DeviceList(devices: List<ControllerCandidate>, onConnect: (ControllerCandidate) -> Unit) {
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
                    imageVector = Icons.Outlined.SettingsRemote,
                    contentDescription = null,
                    tint = CadenceColors.Brand500,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name ?: "Unknown Controller",
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
