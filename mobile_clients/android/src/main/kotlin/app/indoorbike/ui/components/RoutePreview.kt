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

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import app.indoorbike.shared.route.RoutePolyline
import app.indoorbike.ui.theme.CadenceColors
import kotlin.math.cos
import kotlin.math.max

/**
 * The two thumbnails a route card draws before the GPX exists on the device.
 *
 * [app.indoorbike.ui.screens.simulation.ElevationProfile] and
 * [app.indoorbike.ui.screens.simulation.RouteShape] both take a parsed
 * [app.indoorbike.shared.route.RouteTrack], which during a browse is exactly
 * what the app does not have and is trying to avoid downloading. These draw
 * from the listing instead: the coarse profile and encoded shape a
 * [app.indoorbike.shared.repo.RouteEntry] carries for the purpose.
 *
 * Both are decorative. They are a few hundred bytes of hint at card size, not
 * a map, and neither is ever used to work out what the rider is made to feel.
 */

/**
 * Smallest elevation range a profile is drawn against, in metres. Mirrored in
 * `website/assets/routelib.js` as `PROFILE_MIN_SPAN_METERS` so a route looks
 * the same on a card and in the publishing tool.
 */
private const val PROFILE_MIN_SPAN_METERS = 30.0

/** Elevation sparkline from a listing profile. Draws nothing for an absent one. */
@Composable
fun RouteProfileSpark(
    profile: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = CadenceColors.Brand500,
) {
    if (profile.size < 2) return
    Canvas(modifier) {
        val low = profile.min()
        val high = profile.max()
        // Floor the vertical range rather than always fitting min..max. A
        // sparkline that autoscales draws four metres of drift over twenty
        // kilometres as a mountain range, so the flattest route in the list
        // looks like the hilliest. Below this much relief a route is drawn
        // small, which is what flat should look like.
        val span = max(high - low, PROFILE_MIN_SPAN_METERS)
        val stepX = size.width / (profile.size - 1)

        val path = Path()
        profile.forEachIndexed { index, elevation ->
            val x = index * stepX
            val y = size.height - ((elevation - low) / span * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Filled under the line, so a card reads as terrain at a glance rather
        // than as a chart the rider is supposed to study.
        val filled = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(filled, color = color.copy(alpha = 0.18f))
        drawPath(
            path,
            color = color,
            style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * The shape of the road from an encoded polyline, scaled to fit and centred.
 *
 * Longitude is squeezed by cos(latitude) before fitting, because a degree of
 * longitude is shorter than a degree of latitude everywhere but the equator.
 * Without it every route drawn north of the tropics comes out stretched
 * sideways and stops being recognisable as the road it is.
 */
@Composable
fun RouteShapeThumb(
    shape: String?,
    modifier: Modifier = Modifier,
    color: Color = CadenceColors.Fg3,
) {
    val points = remember(shape) {
        if (shape.isNullOrBlank()) emptyList() else RoutePolyline.decode(shape)
    }
    if (points.size < 2) return

    Canvas(modifier) {
        val latitudes = points.map { it.first }
        val longitudes = points.map { it.second }
        val minLat = latitudes.min()
        val maxLat = latitudes.max()
        val minLon = longitudes.min()
        val maxLon = longitudes.max()

        val squeeze = cos(Math.toRadians((minLat + maxLat) / 2.0))
        val spanLat = max(maxLat - minLat, 1e-9)
        val spanLon = max((maxLon - minLon) * squeeze, 1e-9)

        // One scale for both axes keeps the route the shape it really is; the
        // leftover space becomes the centring offset.
        val inset = 4f
        val scale = minOf(
            (size.width - inset * 2) / spanLon,
            (size.height - inset * 2) / spanLat,
        ).toFloat()
        val offsetX = (size.width - (spanLon * scale).toFloat()) / 2f
        val offsetY = (size.height - (spanLat * scale).toFloat()) / 2f

        val path = Path()
        points.forEachIndexed { index, (latitude, longitude) ->
            val x = offsetX + ((longitude - minLon) * squeeze * scale).toFloat()
            // Screen y grows downward, north does not.
            val y = size.height - offsetY - ((latitude - minLat) * scale).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            color = color,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Mark the start so a loop is not a shape with no way in.
        val first = points.first()
        drawCircle(
            color = CadenceColors.Brand500,
            radius = 3f,
            center = Offset(
                offsetX + ((first.second - minLon) * squeeze * scale).toFloat(),
                size.height - offsetY - ((first.first - minLat) * scale).toFloat(),
            ),
        )
    }
}
