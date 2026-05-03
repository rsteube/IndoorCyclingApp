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
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Spacing

@Composable
fun HistoryTab() {
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
    }
}
