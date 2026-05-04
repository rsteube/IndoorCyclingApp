package app.indoorbike.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.Workout
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.zones.PowerZone

/**
 * Replaces the old block-rectangle ActiveWorkoutGraph with a continuous
 * elevation-profile ridge: the workout drawn as terrain seen from above. Each
 * segment gets a zone-tinted vertical gradient fill; a bright crest line
 * traces the top; a brand-colored vertical cursor + dot marks current position
 * along the ridge.
 */
@Composable
fun RidgeTimeline(
    workout: Workout,
    currentSegIdx: Int,
    elapsedInSeg: Int,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
) {
    Box(modifier = modifier.fillMaxWidth().height(height)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val segments = workout.segments
            val totalSeconds = segments.sumOf { it.lengthInSeconds }.coerceAtLeast(1)
            val maxPct = (segments.maxOfOrNull { it.powerPercentFTP } ?: 100).coerceAtLeast(60)

            // Pre-compute per-segment x range and y at peak.
            data class Seg(val x0: Float, val x1: Float, val y: Float, val color: Color)
            val segs = mutableListOf<Seg>()
            var cumSec = 0
            for (seg in segments) {
                val x0 = (cumSec.toFloat() / totalSeconds) * w
                cumSec += seg.lengthInSeconds
                val x1 = (cumSec.toFloat() / totalSeconds) * w
                val ratio = (seg.powerPercentFTP.toFloat() / maxPct).coerceIn(0.18f, 1f)
                val y = h - ratio * (h - 6f) // leave 6px headroom for cursor visibility
                segs += Seg(x0, x1, y, PowerZone.fromFtpPercent(seg.powerPercentFTP).color)
            }

            // Per-segment fills with vertical gradient.
            segs.forEachIndexed { i, seg ->
                val isPast = i < currentSegIdx
                val isCurrent = i == currentSegIdx
                val fillAlpha = when {
                    isCurrent -> 0.85f
                    isPast -> 0.45f
                    else -> 0.22f
                }
                val crestAlpha = when {
                    isCurrent -> 1f
                    isPast -> 0.7f
                    else -> 0.45f
                }
                val path = Path().apply {
                    moveTo(seg.x0, h)
                    lineTo(seg.x0, seg.y)
                    lineTo(seg.x1, seg.y)
                    lineTo(seg.x1, h)
                    close()
                }
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            seg.color.copy(alpha = fillAlpha),
                            seg.color.copy(alpha = fillAlpha * 0.15f)
                        ),
                        startY = seg.y,
                        endY = h
                    )
                )
                // Crest line on top
                drawLine(
                    color = seg.color.copy(alpha = crestAlpha),
                    start = Offset(seg.x0, seg.y),
                    end = Offset(seg.x1, seg.y),
                    strokeWidth = 1.8f
                )
                // Vertical step risers between adjacent segments — thin so the ridge feels connected.
                if (i > 0) {
                    val prev = segs[i - 1]
                    drawLine(
                        color = seg.color.copy(alpha = crestAlpha * 0.55f),
                        start = Offset(seg.x0, prev.y),
                        end = Offset(seg.x0, seg.y),
                        strokeWidth = 1.2f
                    )
                }
            }

            // Baseline hairline.
            drawLine(
                color = CadenceColors.Line2,
                start = Offset(0f, h - 0.5f),
                end = Offset(w, h - 0.5f),
                strokeWidth = 1f
            )

            // Progress cursor — a thin brand line plus a dot anchored to the ridge.
            if (segs.isNotEmpty()) {
                val elapsedTotal = workout.segments.take(currentSegIdx).sumOf { it.lengthInSeconds } + elapsedInSeg
                val progress = (elapsedTotal.toFloat() / totalSeconds).coerceIn(0f, 1f)
                val x = progress * w
                drawLine(
                    color = CadenceColors.Brand500,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 2f
                )
                val cursorY = segs.getOrNull(currentSegIdx)?.y ?: (h - 6f)
                drawCircle(
                    color = CadenceColors.Brand500,
                    radius = 4.5f,
                    center = Offset(x, cursorY)
                )
                drawCircle(
                    color = CadenceColors.Bg0,
                    radius = 2.2f,
                    center = Offset(x, cursorY)
                )
            }
        }
    }
}
