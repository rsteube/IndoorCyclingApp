package app.indoorbike.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.zones.PowerZone

@Composable
fun AppBar(
    title: String,
    eyebrow: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .background(CadenceColors.Bg0)
            .fillMaxWidth()
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(horizontal = Spacing.s5, vertical = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        if (leading != null) leading()
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    text = eyebrow.uppercase(),
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg3
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = title.uppercase(),
                style = CadenceType.h2,
                color = CadenceColors.Fg1
            )
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun IconBtn(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit = {},
    active: Boolean = false,
    tint: Color = CadenceColors.Fg1
) {
    val shape = RoundedCornerShape(Radii.md)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(if (active) CadenceColors.Bg2 else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (active) CadenceColors.Line2 else Color.Transparent,
                shape = shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun PrimaryBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    leadingIcon: ImageVector? = Icons.Outlined.PlayArrow
) {
    val shape = RoundedCornerShape(14.dp)
    val borderModifier = if (glow) Modifier.border(1.dp, CadenceColors.Brand500.copy(alpha = 0.5f), shape) else Modifier
    Row(
        modifier = modifier
            .clip(shape)
            .background(CadenceColors.Brand500)
            .then(borderModifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2, Alignment.CenterHorizontally)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = CadenceColors.OnBrand,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(text = text, style = CadenceType.bodyStrong, color = CadenceColors.OnBrand)
    }
}

@Composable
fun SecondaryBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(CadenceColors.Bg2)
            .border(1.dp, CadenceColors.Line2, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2, Alignment.CenterHorizontally)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = CadenceColors.Fg1,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(text = text, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
    }
}

private val ZoneBadgeText = CadenceType.eyebrow.copy(fontSize = 10.sp, letterSpacing = 0.10.em)

@Composable
fun ZoneBadge(zone: PowerZone, includeLabel: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(zone.bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(zone.color)
        )
        Text(
            text = "Z${zone.number}" + if (includeLabel) " · ${zone.label.uppercase()}" else "",
            style = ZoneBadgeText,
            color = zone.color
        )
    }
}

@Composable
fun MetaPill(text: String, color: Color = CadenceColors.Fg2) {
    Surface(
        shape = CircleShape,
        color = CadenceColors.Bg1,
        border = BorderStroke(1.dp, CadenceColors.Line2)
    ) {
        Text(
            text = text,
            style = CadenceType.tick,
            color = color,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
        )
    }
}

data class ZoneSegment(val zone: PowerZone, val weight: Float)

@Composable
fun ZoneBar(
    segments: List<ZoneSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    progress: Float? = null
) {
    if (segments.isEmpty()) return
    val total = segments.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.0001f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(CadenceColors.Bg0)
    ) {
        var cum = 0f
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            segments.forEach { seg ->
                val before = cum
                cum += seg.weight
                val opacity = when {
                    progress == null -> 1f
                    (before + seg.weight) / total <= progress -> 1f
                    else -> 0.35f
                }
                Box(
                    modifier = Modifier
                        .weight(seg.weight)
                        .height(height)
                        .background(seg.zone.color.copy(alpha = opacity))
                )
            }
        }
        if (progress != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(height)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(2.dp)
                        .height(height + 4.dp)
                        .background(CadenceColors.Brand500)
                )
            }
        }
    }
}

data class TabItem(val id: String, val icon: ImageVector, val label: String)

@Composable
fun TabBar(active: String, tabs: List<TabItem>, onTabSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CadenceColors.Bg1)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        tabs.forEach { tab ->
            val activeNow = tab.id == active
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radii.md))
                    .clickable { onTabSelected(tab.id) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (activeNow) CadenceColors.Brand500 else CadenceColors.Fg3,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = tab.label,
                    style = CadenceType.eyebrow.copy(fontSize = 10.sp),
                    color = if (activeNow) CadenceColors.Brand500 else CadenceColors.Fg3
                )
            }
        }
    }
}

@Composable
fun TrainerStatusStrip(
    deviceName: String,
    statusLine: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    connected: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CadenceColors.Brand500.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = deviceName, style = CadenceType.bodyStrong.copy(fontSize = 14.sp), color = CadenceColors.Fg1)
            Text(text = statusLine, style = CadenceType.tick, color = CadenceColors.Fg3)
        }
        val dotColor = if (connected) PowerZone.Z3.color else CadenceColors.Fg4
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) CadenceColors.Brand500 else CadenceColors.Bg2)
            .border(
                1.dp,
                if (selected) Color.Transparent else CadenceColors.Line2,
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = CadenceType.bodyStrong.copy(fontSize = 12.sp),
            color = if (selected) CadenceColors.OnBrand else CadenceColors.Fg2
        )
    }
}
