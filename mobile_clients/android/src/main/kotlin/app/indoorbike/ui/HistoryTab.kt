package app.indoorbike.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.workout.formatHHMM
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryTab(
    historyRepository: HistoryRepository,
    onNavigateToOverview: (Long) -> Unit
) {
    val sessions by historyRepository.getAllSessions().collectAsState(initial = emptyList())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
            .safeDrawingPadding()
    ) {
        AppBar(
            title = "Ride History",
            eyebrow = "Past Activity"
        )
        
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.s5),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = CadenceColors.Brand500.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s4))
                    Text(
                        text = "No rides yet",
                        style = CadenceType.h2,
                        color = CadenceColors.Fg1,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    Text(
                        text = "Your past workouts will appear here\nonce you complete them.",
                        style = CadenceType.body,
                        color = CadenceColors.Fg3,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.s5, vertical = Spacing.s4),
                verticalArrangement = Arrangement.spacedBy(Spacing.s3)
            ) {
                items(sessions) { session ->
                    val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                    val dateStr = fmt.format(Date(session.startTimeEpoch))
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CadenceColors.Bg1)
                            .clickable { onNavigateToOverview(session.id) }
                            .padding(16.dp)
                    ) {
                        Text(text = dateStr, style = CadenceType.small, color = CadenceColors.Brand500)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = session.workoutName, style = CadenceType.h3, color = CadenceColors.Fg1)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(text = formatHHMM(session.durationSeconds.toInt()), style = CadenceType.bodyStrong, color = CadenceColors.Fg2)
                            Text(text = "${session.avgPower} W", style = CadenceType.bodyStrong, color = CadenceColors.Fg2)
                        }
                    }
                }
            }
        }
    }
}
