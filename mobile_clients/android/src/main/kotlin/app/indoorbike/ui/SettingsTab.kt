package app.indoorbike.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.ble.TrainerManager

@Composable
fun SettingsTab() {
    val connectionState by TrainerManager.connectionState.collectAsState()
    val discoveredDevices by TrainerManager.discoveredDevices.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            TrainerManager.startScan()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Trainer Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Text("Status: ${connectionState.name}", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            if (connectionState == TrainerConnectionState.Disconnected || connectionState == TrainerConnectionState.Error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    )
                } else {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    )
                }
            } else {
                TrainerManager.disconnect()
                TrainerManager.stopScan()
            }
        }) {
            Text(
                when (connectionState) {
                    TrainerConnectionState.Disconnected, TrainerConnectionState.Error -> "Scan for Trainers"
                    TrainerConnectionState.Scanning -> "Stop Scanning"
                    else -> "Disconnect"
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (discoveredDevices.isNotEmpty()) {
            Text("Found Devices:", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(discoveredDevices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                TrainerManager.connectToDevice(device)
                            }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(device.name ?: "Unknown Device", style = MaterialTheme.typography.bodyLarge)
                            Text(device.identifier, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
