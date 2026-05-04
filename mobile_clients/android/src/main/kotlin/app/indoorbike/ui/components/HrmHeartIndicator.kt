package app.indoorbike.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.ble.HrmConnectionState
import app.indoorbike.ui.theme.CadenceColors

/**
 * Compact HRM connection status icon for AppBars.
 *  - Connected: solid filled heart in danger-red.
 *  - Connecting / Scanning: outline heart pulsing alpha.
 *  - Disconnected / Error: dim outline heart.
 *
 * Pure visual indicator — no click handling here. Wrap in a clickable parent
 * if the user should be able to tap through to pairing.
 */
@Composable
fun HrmHeartIndicator(
    state: HrmConnectionState,
    modifier: Modifier = Modifier,
) {
    val isConnecting = state == HrmConnectionState.Connecting || state == HrmConnectionState.Scanning
    val isConnected = state == HrmConnectionState.Connected

    val pulseAlpha = if (isConnecting) {
        val transition = rememberInfiniteTransition(label = "hrmPulse")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "hrmPulseAlpha"
        )
        a
    } else 1f

    val (icon, tint) = when {
        isConnected -> Icons.Filled.Favorite to CadenceColors.Danger
        isConnecting -> Icons.Outlined.FavoriteBorder to CadenceColors.Danger
        else -> Icons.Outlined.FavoriteBorder to CadenceColors.Fg4
    }

    Icon(
        imageVector = icon,
        contentDescription = when {
            isConnected -> "Heart rate monitor connected"
            isConnecting -> "Heart rate monitor connecting"
            else -> "Heart rate monitor not connected"
        },
        tint = tint,
        modifier = modifier
            .alpha(pulseAlpha)
            .size(22.dp)
    )
}
