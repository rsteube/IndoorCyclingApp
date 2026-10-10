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
package app.indoorbike.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.route.formatKm
import app.indoorbike.shared.simulation.RouteProgress
import app.indoorbike.shared.simulation.RouteStart
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.screens.simulation.ElevationProfile
import app.indoorbike.ui.screens.simulation.RouteMapView
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Where on a long route to start, chosen before the ride.
 *
 * The whole route on the map and its profile underneath, with a marker at the
 * start. A tap on the map puts the start at the nearest point of the road; a
 * tap or a drag along the profile puts it under the finger, which is the way
 * to tell apart two passes of the same place and to land on the foot of a
 * climb. Everything before the marker is drawn dimmed: it is the part being
 * skipped. The ride then starts there like any other, on the same path a
 * resumed ride takes.
 *
 * Shown for routes of [RouteStart.CHOOSE_FROM_METERS] or more, and in place of
 * the "pick up where you left off" question for them: the place the last ride
 * stopped is one of the choices here, and starts selected.
 */
@Composable
fun RouteStartScreen(
    route: RouteTrack,
    /** Where the last ride on this route stopped, if one did. */
    saved: RouteProgress?,
    onBack: () -> Unit,
    /** Start the ride at [startMeters], having already climbed [elevationGainMeters]. */
    onStart: (startMeters: Double, elevationGainMeters: Double) -> Unit,
) {
    val total = route.totalDistanceMeters
    var start by rememberSaveable(route.id) {
        mutableDoubleStateOf(saved?.let { RouteStart.startFor(route, it.distanceMeters, it) } ?: 0.0)
    }
    val position = remember(route, start) { route.positionAt(start) }
    val pick = { meters: Double -> start = RouteStart.startFor(route, meters, saved) }

    Column(
        Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
    ) {
        AppBar(
            title = route.name,
            eyebrow = "Choose where to start",
            leading = { IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack) },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                // The ride button is the last thing on the screen, so it must
                // clear the system's navigation bar.
                .navigationBarsPadding()
                .padding(horizontal = Spacing.s4)
                .padding(bottom = Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(Radii.lg)),
            ) {
                RouteMapView(
                    route = route,
                    position = position,
                    followRider = false,
                    modifier = Modifier.fillMaxSize(),
                    riddenColor = CadenceColors.Fg4,
                    onTap = { latitude, longitude -> pick(route.distanceNearest(latitude, longitude)) },
                )
                Text(
                    text = "Tap the map or the profile",
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.s2)
                        .clip(RoundedCornerShape(50))
                        .background(CadenceColors.Bg0.copy(alpha = 0.78f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                // The tiles' licence asks for the credit wherever they are shown.
                Text(
                    text = "© OpenStreetMap",
                    style = CadenceType.tick.copy(fontSize = 9.sp),
                    color = CadenceColors.Fg2,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(CadenceColors.Bg0.copy(alpha = 0.62f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }

            ElevationProfile(
                route = route,
                distanceMeters = start,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PROFILE_HEIGHT)
                    .clip(RoundedCornerShape(Radii.md))
                    .background(CadenceColors.Bg1),
                onPick = pick,
            )

            val toGo = (total - start).coerceAtLeast(0.0)
            val climbAhead = (route.totalAscentMeters - route.ascentTo(start)).coerceAtLeast(0.0)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s5)) {
                Figure(label = "Start at", value = if (start <= 0.0) "The start" else formatKm(start))
                Figure(label = "To ride", value = formatKm(toGo))
                Figure(label = "To climb", value = "${climbAhead.roundToInt()} m")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Always there, and only greyed at the start: a row that came
                // and went would resize the map under the rider's finger and
                // push the route out of its frame.
                SecondaryBtn(text = "From the start", onClick = { start = 0.0 }, enabled = start > 0.0)
                val mark = saved?.distanceMeters
                if (mark != null && mark != start && mark < total - RouteStart.MIN_LEFT_METERS) {
                    SecondaryBtn(
                        text = "Where you stopped, ${formatKm(mark)}",
                        onClick = { start = mark },
                    )
                }
            }

            PrimaryBtn(
                text = if (start <= 0.0) "Ride from the start" else "Ride from ${formatKm(start)}",
                onClick = { onStart(start, RouteStart.elevationGainAt(route, start, saved)) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
            )
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column {
        Text(text = label, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Text(text = value, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
    }
}

/** Tall enough to aim a finger at a climb on a 100 km route. */
private val PROFILE_HEIGHT = 120.dp
