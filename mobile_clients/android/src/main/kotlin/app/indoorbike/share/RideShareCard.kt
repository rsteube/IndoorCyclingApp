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
package app.indoorbike.share

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.ui.screens.simulation.RouteSummaryPanel
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.shared.progression.RideStats
import app.indoorbike.ui.theme.BodyFontFamily
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.DisplayFontFamily
import app.indoorbike.ui.theme.MonoFontFamily
import app.indoorbike.ui.zones.PowerZone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The fixed pixel size of the rendered share image. */
const val SHARE_CARD_SIZE_PX: Int = 1080

/**
 * Highlight that closes the card. Mirrors VertMatch's "Toughest Section" slot.
 * `peakPowerWatts` is the fallback when no workout structure is available.
 */
data class ShareHighlight(
    val title: String,           // e.g. "Toughest interval" / "Peak power"
    val name: String,            // e.g. "Z4 · 12:00"
    val statA: Pair<String, String>?, // label -> value, e.g. "% FTP" -> "98%"
    val statB: Pair<String, String>?,
    val accent: Color
)

/**
 * The 1080x1080 social-share card. Designed for direct capture via [captureShareCard]
 * which forces density to 1, so every dp/sp value below also equals one device pixel.
 */
@Composable
fun RideShareCard(
    session: WorkoutSession,
    stats: RideStats,
    workout: Workout?,
    telemetry: List<WorkoutTelemetry>,
    ftp: Int,
    highlight: ShareHighlight,
    /**
     * The road a simulated ride was on, or null for the two modes that had
     * none. When present it replaces the power-adherence chart: a route ride
     * had no plan to adhere to, and its shape and profile are what the ride
     * actually was.
     */
    route: RouteTrack? = null,
    /**
     * A picture from the ride, which takes the hero panel when there is one.
     *
     * A companion's screenshot is the only thing on this card that shows where
     * the rider actually was, and it is the half of the ride a chart cannot
     * draw. The numbers around it stay exactly as they are: the picture is the
     * face of the card, not the whole of it, and what it replaces is the panel
     * that was standing in for it.
     */
    picture: ImageBitmap? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(SHARE_CARD_SIZE_PX.dp)
            .background(CadenceColors.Bg0)
    ) {
        // A slate wash top-left, so the card has some depth without any
        // colour in it that does not mean a power zone.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            CadenceColors.Bg1,
                            Color.Transparent
                        ),
                        radius = 720f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 64.dp)
        ) {
            HeaderBlock(session = session)

            Spacer(Modifier.height(40.dp))

            KpiRow(stats = stats, durationSeconds = session.durationSeconds.toInt())

            Spacer(Modifier.height(40.dp))

            // Hero panel. A route ride shows the road it was on, labelled as
            // the simulation it was; everything else shows the planned zone
            // bars against the actual power line, mirroring the on-screen
            // "Power Adherence" view but sized for legibility in a feed.
            if (picture != null) {
                PicturePanel(
                    picture = picture,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else if (route != null) {
                RouteSummaryPanel(
                    route = route,
                    riddenMeters = session.routeDistanceMeters,
                    elevationGainMeters = session.routeElevationGainMeters,
                    startedAtMeters = session.routeStartMeters,
                    startedAtElevationGainMeters = session.routeStartElevationGainMeters,
                    titleSize = 26.sp,
                    statSize = 44.sp,
                    shapeHeight = 260.dp,
                    profileHeight = 110.dp,
                    strokeWidth = 11f,
                    background = CadenceColors.Bg1.copy(alpha = 0.6f),
                    fillHeight = true,
                    caption = "Simulated indoors on this route's gradient.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                ChartPanel(
                    workout = workout,
                    telemetry = telemetry,
                    ftp = ftp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            Spacer(Modifier.height(if (route != null || picture != null) 24.dp else 32.dp))

            // The route panel is the highlight on a route ride, and it needs
            // the room: squeezed in beside a callout, its shape collapses to a
            // smudge and the elevation profile falls off the bottom. A picture
            // is the highlight for the same reason, and more plainly: a photo
            // shrunk to make room for a caption about a hard interval is a
            // worse version of both.
            if (route == null && picture == null) {
                HighlightCallout(highlight)
                Spacer(Modifier.height(32.dp))
            }

            FooterBlock()
        }
    }
}

/**
 * The picture, filling the hero panel.
 *
 * Cropped rather than fitted. The frames a companion sends are whatever shape
 * its window is, and letterboxing each one would put a different amount of
 * empty card around every picture; cropping to the panel gives every ride the
 * same card with a different picture in it.
 */
@Composable
private fun PicturePanel(picture: ImageBitmap, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(CadenceColors.Bg1)
    ) {
        Image(
            bitmap = picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ---------------------------------------------------------------------------
//  Header
// ---------------------------------------------------------------------------

@Composable
private fun HeaderBlock(session: WorkoutSession) {
    Column {
        Text(
            text = formatDate(session.startTimeEpoch),
            style = TextStyle(
                fontFamily = BodyFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                letterSpacing = 0.18.em
            ),
            color = CadenceColors.Fg3
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = session.workoutName,
            style = TextStyle(
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 96.sp,
                lineHeight = 96.sp,
                letterSpacing = 0.02.em
            ),
            color = CadenceColors.Fg1,
            maxLines = 2
        )
    }
}

// ---------------------------------------------------------------------------
//  KPI tiles
// ---------------------------------------------------------------------------

@Composable
private fun KpiRow(stats: RideStats, durationSeconds: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Kpi(
            label = "Duration",
            value = formatDuration(durationSeconds),
            valueColor = CadenceColors.Fg1,
            modifier = Modifier.weight(1f)
        )
        Kpi(
            label = "Avg power",
            value = "${stats.avgPowerWatts}",
            unit = "W",
            valueColor = CadenceColors.Fg1,
            modifier = Modifier.weight(1f)
        )
        Kpi(
            label = "TSS",
            value = "${stats.tss}",
            valueColor = CadenceColors.Fg1,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Kpi(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    unit: String = ""
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(CadenceColors.Bg1)
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = BodyFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                letterSpacing = 0.18.em
            ),
            color = CadenceColors.Fg3
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = TextStyle(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 64.sp,
                    lineHeight = 64.sp,
                    letterSpacing = 0.01.em
                ),
                color = valueColor
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = " $unit",
                    style = TextStyle(
                        fontFamily = BodyFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 24.sp,
                        letterSpacing = 0.12.em
                    ),
                    color = valueColor.copy(alpha = 0.85f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Chart panel
// ---------------------------------------------------------------------------

@Composable
private fun ChartPanel(
    workout: Workout?,
    telemetry: List<WorkoutTelemetry>,
    ftp: Int,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(CadenceColors.Bg1)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Power",
                    style = TextStyle(
                        fontFamily = BodyFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        letterSpacing = 0.18.em
                    ),
                    color = CadenceColors.Fg3
                )
                if (telemetry.isNotEmpty() && ftp > 0) {
                    val peak = telemetry.maxOf { it.power }
                    Text(
                        text = "PEAK $peak W",
                        style = TextStyle(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 18.sp,
                            letterSpacing = 0.10.em
                        ),
                        color = CadenceColors.Fg2
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ChartBody(workout = workout, telemetry = telemetry, ftp = ftp)
            }
        }
    }
}

@Composable
private fun ChartBody(
    workout: Workout?,
    telemetry: List<WorkoutTelemetry>,
    ftp: Int
) {
    val targetMaxPct = (workout?.segments?.maxOfOrNull { it.powerPercentFTP } ?: 100).coerceAtLeast(60)
    val telemetryMaxPct = if (ftp > 0) {
        ((telemetry.maxOfOrNull { it.power } ?: 0L).toFloat() / ftp * 100f).toInt()
    } else 0
    val maxChartPct = maxOf(targetMaxPct, telemetryMaxPct).coerceAtLeast(60)

    // When a planned workout is present, the background zone bars span the full
    // planned duration. The actual power curve must be plotted on that same time
    // axis (elapsed time / planned total) rather than being stretched across the
    // whole width - otherwise a ride that ends early leaves the curve and the
    // background on different scales.
    val plannedTotalSec = workout?.segments?.sumOf { it.lengthInSeconds.coerceAtLeast(1) } ?: 0
    val firstTs = telemetry.firstOrNull()?.timestampEpoch ?: 0L

    Box(modifier = Modifier.fillMaxSize()) {
        if (workout != null && workout.segments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Bottom
            ) {
                workout.segments.forEach { seg ->
                    val zone = PowerZone.fromFtpPercent(seg.powerPercentFTP)
                    val h = (seg.powerPercentFTP.toFloat() / maxChartPct).coerceIn(0.04f, 1f)
                    Box(
                        modifier = Modifier
                            .weight(seg.lengthInSeconds.toFloat().coerceAtLeast(1f))
                            .fillMaxHeight(h)
                            .background(zone.color.copy(alpha = 0.30f))
                    )
                }
            }
        } else if (telemetry.isNotEmpty() && ftp > 0) {
            // Fall back to a soft area chart of the actual power telemetry.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val n = telemetry.size
                if (n < 2) return@Canvas
                val area = Path().apply {
                    moveTo(0f, h)
                    telemetry.forEachIndexed { i, p ->
                        val x = (i.toFloat() / (n - 1)) * w
                        val pct = (p.power.toFloat() / ftp * 100f).coerceAtLeast(0f)
                        val y = (h - (pct / maxChartPct) * h).coerceIn(0f, h)
                        lineTo(x, y)
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(path = area, color = CadenceColors.Fg1.copy(alpha = 0.18f))
            }
        }

        // Actual power line overlay.
        if (telemetry.size > 1 && ftp > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val n = telemetry.size
                val path = Path()
                telemetry.forEachIndexed { i, p ->
                    val x = if (plannedTotalSec > 0) {
                        val elapsedSec = (p.timestampEpoch - firstTs) / 1000f
                        ((elapsedSec / plannedTotalSec) * w).coerceIn(0f, w)
                    } else {
                        (i.toFloat() / (n - 1)) * w
                    }
                    val pct = (p.power.toFloat() / ftp * 100f).coerceAtLeast(0f)
                    val y = (h - (pct / maxChartPct) * h).coerceIn(0f, h)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = CadenceColors.Fg1,
                    style = Stroke(width = 6f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Highlight callout
// ---------------------------------------------------------------------------

@Composable
private fun HighlightCallout(h: ShareHighlight) {
    val shape = RoundedCornerShape(40.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Zone-coloured swatch
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(h.accent.copy(alpha = 0.18f))
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(h.accent)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = h.title,
                style = TextStyle(
                    fontFamily = BodyFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    letterSpacing = 0.18.em
                ),
                color = h.accent
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = h.name,
                style = TextStyle(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 44.sp,
                    lineHeight = 44.sp,
                    letterSpacing = 0.02.em
                ),
                color = CadenceColors.Fg1
            )
        }

        listOfNotNull(h.statA, h.statB).forEach { (label, value) ->
            MiniStat(label = label, value = value)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = BodyFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                letterSpacing = 0.18.em
            ),
            color = CadenceColors.Fg3
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = TextStyle(
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 36.sp,
                lineHeight = 36.sp,
                letterSpacing = 0.01.em
            ),
            color = CadenceColors.Fg1
        )
    }
}

// ---------------------------------------------------------------------------
//  Footer
// ---------------------------------------------------------------------------

@Composable
private fun FooterBlock() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Wordmark()
        Text(
            text = "indoorbike.app",
            style = TextStyle(
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                letterSpacing = 0.10.em
            ),
            color = CadenceColors.Fg3
        )
    }
}

@Composable
private fun Wordmark() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // The brand mark, drawn from the same 64-unit grid as the launcher icon
        // and the site's logo-mark.svg: ridge over a bike whose rear wheel is
        // the flywheel of a direct-drive trainer. Drawn rather than loaded
        // because this card is rendered off-screen into a bitmap, and a vector
        // drawable would be one more thing to keep in step with the SVG.
        Canvas(modifier = Modifier.size(44.dp)) {
            val u = size.minDimension / 64f
            fun p(vararg pts: Float) = Path().apply {
                moveTo(pts[0] * u, pts[1] * u)
                for (i in 2 until pts.size step 2) lineTo(pts[i] * u, pts[i + 1] * u)
            }
            val stroke = Stroke(
                width = 3f * u,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            drawPath(
                path = p(4f, 26f, 14f, 16f, 22f, 22f, 32f, 10f, 44f, 20f, 54f, 14f, 60f, 20f),
                color = CadenceColors.Fg3,
                style = stroke
            )
            drawCircle(
                color = CadenceColors.Fg1,
                radius = 8f * u,
                center = Offset(48f * u, 48f * u),
                style = stroke
            )
            drawPath(
                path = p(32f, 48f, 28f, 32f, 44f, 30f, 44f, 38f, 48f, 48f),
                color = CadenceColors.Fg1,
                style = stroke
            )
            drawPath(path = p(32f, 48f, 16f, 48f, 28f, 32f), color = CadenceColors.Fg1, style = stroke)
            drawPath(path = p(24f, 32f, 32f, 32f), color = CadenceColors.Fg1, style = stroke)
            drawCircle(
                color = CadenceColors.Fg1,
                radius = 7f * u,
                center = Offset(16f * u, 48f * u)
            )
            drawPath(path = p(16f, 55f, 16f, 58f), color = CadenceColors.Fg1, style = stroke)
            drawPath(path = p(6f, 58f, 26f, 58f), color = CadenceColors.Fg1, style = stroke)
        }
        Text(
            text = "Indoor",
            style = TextStyle(
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 32.sp,
                letterSpacing = (-0.02).em
            ),
            color = CadenceColors.Fg2
        )
        Text(
            text = "Bike",
            style = TextStyle(
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
                letterSpacing = (-0.02).em
            ),
            color = CadenceColors.Fg1
        )
    }
}

// ---------------------------------------------------------------------------
//  Helpers
// ---------------------------------------------------------------------------

/**
 * Picks the longest segment at Z4 or higher as the "toughest interval".
 * Falls back to the highest-power segment overall, then to a peak-power
 * highlight derived from telemetry when there is no workout structure.
 */
fun deriveHighlight(
    workout: Workout?,
    telemetry: List<WorkoutTelemetry>
): ShareHighlight {
    if (workout != null && workout.segments.isNotEmpty()) {
        val candidate = workout.segments
            .filter { PowerZone.fromFtpPercent(it.powerPercentFTP).number >= 4 }
            .maxByOrNull { it.lengthInSeconds.toLong() * it.powerPercentFTP }
            ?: workout.segments.maxBy { it.powerPercentFTP }

        val zone = PowerZone.fromFtpPercent(candidate.powerPercentFTP)
        return ShareHighlight(
            title = "Toughest interval",
            name = "Z${zone.number} · ${formatDuration(candidate.lengthInSeconds)}",
            statA = "% FTP" to "${candidate.powerPercentFTP}%",
            statB = "Zone" to zone.label,
            accent = zone.color
        )
    }
    val peak = telemetry.maxOfOrNull { it.power } ?: 0L
    return ShareHighlight(
        title = "Peak power",
        name = "$peak W",
        statA = null,
        statB = null,
        accent = CadenceColors.Fg1
    )
}

private fun formatDate(epochMs: Long): String {
    val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return fmt.format(Date(epochMs))
}

private fun formatDuration(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        else -> "%d:%02d".format(m, s)
    }
}
