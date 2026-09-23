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
package app.indoorbike.ui.screens.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.companion.BrandPalette
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * What the ride header shows in Companion mode.
 *
 * @param name the companion's own name for itself, or its `hello` name.
 * @param subtitle the course and world, and how many rides this session has kept.
 * @param icon the companion's icon, when it sent one.
 * @param palette the companion's colours after the app made them legible, or
 *   null for the app's own.
 * @param notice something the rider needs to see now, which takes the status
 *   line over from the ride's own state.
 */
data class CompanionHeaderStyle(
    val name: String,
    val subtitle: String?,
    val icon: ImageBitmap?,
    val palette: BrandPalette?,
    val notice: CompanionNotice?,
)

data class CompanionNotice(val text: String, val color: Color)

/** A palette colour as Compose wants it. */
fun Int.brandColor(): Color = Color(this)

/**
 * The header band: the companion's header colour behind the whole header, and
 * a line of its accent along the bottom edge.
 *
 * Nothing at all without a palette. A companion that sent no colours gets the
 * same header every other mode has, with its name and a monogram, rather than a
 * grey band that looks like a styling that failed to load.
 */
fun Modifier.companionBand(palette: BrandPalette?): Modifier {
    if (palette == null) return this
    val header = palette.header.brandColor()
    val accent = palette.accent.brandColor()
    return this
        .background(header)
        .drawBehind {
            val line = 3.dp.toPx()
            drawRect(accent, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
        }
}

/**
 * The companion's mark: its icon, or failing that the first letter of its name.
 *
 * The monogram is there so a companion that sends a name but no picture still
 * reads as a thing with an identity, and so the header does not change shape
 * when an icon arrives a moment after the name.
 */
@Composable
fun CompanionMark(
    name: String,
    icon: ImageBitmap?,
    palette: BrandPalette?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(size * 0.24f)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size).clip(shape),
        )
        return
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(palette?.accent?.brandColor() ?: CadenceColors.Bg2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            fontSize = (size.value * 0.46f).sp,
            fontWeight = FontWeight.Bold,
            color = palette?.onAccent?.brandColor() ?: CadenceColors.Fg1,
            maxLines = 1,
        )
    }
}

/** Mark, name and subtitle, as one block that ellipsises rather than wraps. */
@Composable
fun CompanionTitle(
    style: CompanionHeaderStyle,
    markSize: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompanionMark(name = style.name, icon = style.icon, palette = style.palette, size = markSize)
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = style.name,
                style = CadenceType.h3,
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (style.subtitle != null) {
                Text(
                    text = style.subtitle,
                    style = CadenceType.small,
                    color = CadenceColors.Fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * What the header's status line has to say about the companion, if anything.
 *
 * In order of how much it matters: a ride has just been written (for a few
 * seconds, by name, because nobody taps through a summary in this mode and this
 * is the only way a rider finds out their lap was kept); no companion is
 * driving; the companion has gone quiet. Otherwise null, and the line shows the
 * ride's own state.
 */
@Composable
fun rememberCompanionNotice(
    connectedName: String?,
    roadStale: Boolean,
    savedRideCount: Int,
    lastSavedRideName: String?,
    /**
     * The session panel is up and already saying the companion has gone.
     * Repeating it on the header, in amber, reads as a second alarm about the
     * same thing, and after a session the rider ended on purpose, as an error.
     */
    panelShowing: Boolean = false,
    /** The screenshot the rider last asked for, while there is something to say about it. */
    screenshot: app.indoorbike.shared.companion.CompanionHost.ScreenshotStatus? = null,
): CompanionNotice? {
    // Keyed on the count rather than the name, so two laps of the same course
    // still announce twice.
    var announcing by remember(savedRideCount) { mutableStateOf(savedRideCount > 0) }
    LaunchedEffect(savedRideCount) {
        if (savedRideCount == 0) return@LaunchedEffect
        delay(SAVED_ANNOUNCE_MS)
        announcing = false
    }
    return when {
        announcing -> CompanionNotice(
            text = if (lastSavedRideName != null) "Saved to history: $lastSavedRideName"
                else "Ride saved to history",
            color = CadenceColors.Success,
        )
        panelShowing -> null
        screenshot != null -> CompanionNotice(
            text = screenshot.message,
            color = when (screenshot.kind) {
                app.indoorbike.shared.companion.CompanionHost.ScreenshotStatus.Kind.WAITING -> CadenceColors.Fg2
                app.indoorbike.shared.companion.CompanionHost.ScreenshotStatus.Kind.SAVED -> CadenceColors.Success
                app.indoorbike.shared.companion.CompanionHost.ScreenshotStatus.Kind.FAILED -> CadenceColors.Warning
            },
        )
        connectedName == null -> CompanionNotice("No companion connected - flat road", CadenceColors.Warning)
        roadStale -> CompanionNotice("$connectedName has gone quiet - flattening the road", CadenceColors.Warning)
        else -> null
    }
}

/** Long enough to read a course name, short enough not to hide the ride's own state for long. */
private const val SAVED_ANNOUNCE_MS = 6_000L
