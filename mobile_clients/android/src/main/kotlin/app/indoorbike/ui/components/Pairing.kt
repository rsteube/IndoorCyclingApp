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
package app.indoorbike.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.theme.rememberReducedMotion

/**
 * Pairing chrome, shared by the trainer, heart-rate and controller screens.
 *
 * All three do the same thing in the same order - say what state the radio is
 * in, then list what it found - so they say it in the same shape. Only the
 * glyph and the wording differ.
 */

/**
 * The state of the scan, as one card: a disc holding the device's own glyph,
 * with a ring turning around it while the radio is listening.
 */
@Composable
fun ScanStatusCard(
    icon: ImageVector,
    title: String,
    helper: String,
    scanning: Boolean,
    modifier: Modifier = Modifier,
) {
    SlateCard(
        modifier = modifier,
        padding = PaddingValues(horizontal = Spacing.s6, vertical = 28.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(96.dp)
                .clip(CircleShape)
                .background(CadenceColors.Bg2),
            contentAlignment = Alignment.Center
        ) {
            if (scanning) ScanRing()
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CadenceColors.Fg1,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(Spacing.s5))
        Text(
            text = title,
            style = CadenceType.h3,
            color = CadenceColors.Fg1,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = helper,
            style = CadenceType.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = CadenceColors.Fg3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * A 72 dp ring with one bright quarter, turning once every 1.2 s. Held still
 * when the device asks for reduced motion: a scan that has not finished is
 * already stated in words above it, so the spin is reassurance, not
 * information.
 */
@Composable
private fun ScanRing() {
    val reduceMotion = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "scanRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "scanRingAngle"
    )
    Canvas(
        modifier = Modifier
            .size(72.dp)
            .rotate(if (reduceMotion) 0f else angle)
    ) {
        val stroke = 3.dp.toPx()
        drawCircle(
            color = CadenceColors.Bg3,
            radius = (size.minDimension - stroke) / 2f,
            style = Stroke(width = stroke)
        )
        drawArc(
            color = CadenceColors.Fg1,
            startAngle = -90f,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/**
 * The in-between states - connecting, disconnecting - which have nothing to
 * show but a spinner and a sentence.
 */
@Composable
fun PairingProgress(title: String, helper: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(Spacing.s6))
        CircularProgressIndicator(
            color = CadenceColors.Fg1,
            modifier = Modifier.size(56.dp),
            strokeWidth = 3.dp
        )
        Spacer(Modifier.height(Spacing.s5))
        Text(text = title, style = CadenceType.h3, color = CadenceColors.Fg1)
        if (helper != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = helper,
                style = CadenceType.body.copy(fontSize = 14.sp),
                color = CadenceColors.Fg3,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * One discovered device. The strongest signal gets the white Connect pill and
 * everything else gets the quiet one, so a list of six candidates still has an
 * obvious first choice.
 */
@Composable
fun PairingDeviceRow(
    icon: ImageVector,
    name: String,
    subtitle: String,
    primary: Boolean,
    onConnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect)
            .padding(horizontal = Spacing.s5, vertical = Spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CadenceColors.Fg2,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = CadenceType.small,
                color = CadenceColors.Fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "Connect",
            style = CadenceType.small.copy(fontWeight = FontWeight.Bold),
            color = if (primary) CadenceColors.OnBrand else CadenceColors.Fg1,
            modifier = Modifier
                .clip(CircleShape)
                .background(if (primary) CadenceColors.Fg1 else CadenceColors.Bg2)
                .clickable(onClick = onConnect)
                .padding(horizontal = Spacing.s4, vertical = Spacing.s2)
        )
    }
}

/** Coarse on purpose: a rider only needs to know which one is nearest. */
fun signalLabel(rssi: Int?): String? = when {
    rssi == null -> null
    rssi >= -60 -> "Strong signal"
    rssi >= -80 -> "Good signal"
    else -> "Weak signal"
}
