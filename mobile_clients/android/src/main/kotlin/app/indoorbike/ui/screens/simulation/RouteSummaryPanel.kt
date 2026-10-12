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
package app.indoorbike.ui.screens.simulation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.simulation.RouteRideFigures
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * The road a finished ride was on: its shape, its profile, and how much of it
 * the rider got through.
 *
 * Shared by the post-ride summary and the share image, because they are
 * answering the same question and should not answer it two different ways. The
 * one thing both are careful about is honesty: a ride on a trainer did not
 * happen on this road, and the panel says so rather than letting a shape and an
 * elevation profile quietly imply that it did.
 */
@Composable
fun RouteSummaryPanel(
    route: RouteTrack,
    /** Where the ride ended on the route: a position, not a distance. */
    riddenMeters: Double,
    elevationGainMeters: Double,
    modifier: Modifier = Modifier,
    /**
     * Where the ride began on the route. Non-zero when the rider carried on
     * from a previous sitting, and the difference between the two is what this
     * ride actually did. Reading the position as the distance is what made a
     * ride resumed at 42 km report 48 km of riding after six.
     */
    startedAtMeters: Double = 0.0,
    startedAtElevationGainMeters: Double = 0.0,
    /** Scales the whole panel: the share image is drawn much larger. */
    titleSize: TextUnit = 13.sp,
    statSize: TextUnit = 20.sp,
    shapeHeight: Dp = 150.dp,
    profileHeight: Dp = 72.dp,
    strokeWidth: Float = 6f,
    background: Color = CadenceColors.Bg1,
    /**
     * Fill the height given rather than sizing to [shapeHeight].
     *
     * The summary screen is a scrolling column, which has no height to fill, so
     * the panel picks its own. The share card hands it a fixed slot in a
     * 1080-square image, where sizing to content overflows and silently drops
     * whatever is at the bottom - which was the elevation profile, the half of
     * the panel that says what the ride was actually like.
     */
    fillHeight: Boolean = false,
    /** The honesty line. Shortened where space is tight, never dropped. */
    caption: String = "Simulated indoors. Gradient and distance from the route, " +
        "speed from the rider's own power.",
    /**
     * Appended when the ride began part way along, so the picture is
     * explained. It says nothing about why: the start is stored, but whether
     * it was picked or carried on from an earlier sitting is not, and "ridden
     * last time" over a stretch the rider chose to skip would be a claim the
     * panel cannot back.
     */
    continuedCaption: String = "Started part way along; the faint part of the " +
        "road is before the start.",
) {
    val completed = riddenMeters >= route.totalDistanceMeters - 5.0
    val continued = startedAtMeters > 1.0
    // What this sitting covered, which is the number the rest of the summary
    // is about. Counted from the start and never negative, the same way the
    // ride screen counts it.
    val figures = RouteRideFigures.of(
        route = route,
        distanceMeters = riddenMeters,
        elevationGainMeters = elevationGainMeters,
        startedAtMeters = startedAtMeters,
        startedAtElevationGainMeters = startedAtElevationGainMeters,
    )
    val thisRideMeters = figures.riddenMeters
    val thisRideClimb = figures.climbedMeters
    Column(
        modifier = modifier
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(Spacing.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Virtual route",
                style = CadenceType.eyebrow.copy(fontSize = titleSize * 0.72f),
                color = CadenceColors.Fg1,
                modifier = Modifier.weight(1f),
            )
            if (completed) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(CadenceColors.Success.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "Completed",
                        style = CadenceType.eyebrow.copy(fontSize = titleSize * 0.66f),
                        color = CadenceColors.Success,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = route.name,
            style = CadenceType.h3.copy(fontSize = titleSize * 1.35f),
            color = CadenceColors.Fg1,
        )

        Spacer(Modifier.height(Spacing.s3))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // A floor as well as a weight: with a tall profile below it the
                // flexible row can otherwise be squeezed to nothing, and a
                // route shape a few pixels high says less than no shape at all.
                .heightIn(min = shapeHeight * 0.6f)
                .then(if (fillHeight) Modifier.weight(1f) else Modifier.height(shapeHeight)),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s4),
        ) {
            RouteShape(
                route = route,
                riddenMeters = riddenMeters,
                startedAtMeters = startedAtMeters,
                strokeWidth = strokeWidth,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                // Today's numbers, with the road's own total underneath. On a
                // continued ride the detail says where that leaves the rider
                // rather than repeating the route length, because "6.5 km of
                // 169 km" invites the reading that 162 km are still to go when
                // in fact most of them are behind.
                RouteStat(
                    label = if (continued) "Ridden today" else "Ridden",
                    value = formatDistance(thisRideMeters),
                    detail = if (continued) {
                        "${formatDistance(riddenMeters)} of ${formatDistance(route.totalDistanceMeters)}"
                    } else {
                        "of ${formatDistance(route.totalDistanceMeters)}"
                    },
                    size = statSize,
                )
                Spacer(Modifier.height(Spacing.s3))
                RouteStat(
                    label = if (continued) "Climbed today" else "Climbed",
                    value = "${thisRideClimb.roundToInt()} m",
                    detail = if (continued) {
                        "${elevationGainMeters.roundToInt()} of ${route.totalAscentMeters.roundToInt()} m"
                    } else {
                        "of ${route.totalAscentMeters.roundToInt()} m"
                    },
                    size = statSize,
                )
            }
        }

        Spacer(Modifier.height(Spacing.s3))
        ElevationProfile(
            route = route,
            distanceMeters = riddenMeters,
            startedAtMeters = startedAtMeters,
            modifier = Modifier.fillMaxWidth().height(profileHeight),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // The claim this panel must not make by implication.
            text = if (continued) "$caption $continuedCaption" else caption,
            style = CadenceType.tick.copy(fontSize = titleSize * 0.7f),
            color = CadenceColors.Fg3,
        )
    }
}

@Composable
private fun RouteStat(label: String, value: String, detail: String, size: TextUnit) {
    Column {
        Text(
            text = label,
            style = CadenceType.eyebrow.copy(fontSize = size * 0.45f),
            color = CadenceColors.Fg3,
        )
        Text(
            text = value,
            style = CadenceType.h2.copy(fontSize = size),
            color = CadenceColors.Fg1,
            // One line each, always. The share card draws this into a fixed
            // slot in a 1080-square image, where a wrapped line does not push
            // the layout down, it falls off the bottom of the picture.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = detail,
            style = CadenceType.tick.copy(fontSize = size * 0.5f),
            color = CadenceColors.Fg3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
