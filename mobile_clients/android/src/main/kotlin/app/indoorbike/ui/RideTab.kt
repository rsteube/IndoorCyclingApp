package app.indoorbike.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.Workout
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.ble.TrainerManager

@Composable
fun RideTab(workouts: List<Workout>) {
    val connectionState by TrainerManager.connectionState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Connection Banner
        val bannerColor = when (connectionState) {
            TrainerConnectionState.Connected -> Color(0xFF4CAF50)
            TrainerConnectionState.Scanning, TrainerConnectionState.Connecting -> Color(0xFFFF9800)
            else -> Color(0xFFF44336)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bannerColor)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Trainer Status: ${connectionState.name}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Indoor Cycling App\nLoaded ${workouts.size} workouts",
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
