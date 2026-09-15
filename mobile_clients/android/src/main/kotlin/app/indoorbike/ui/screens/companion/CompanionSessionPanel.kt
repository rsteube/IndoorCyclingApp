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

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.indoorbike.companion.CompanionHost.SessionPhase
import app.indoorbike.ride.SavedCompanionRide
import app.indoorbike.shared.companion.BrandPalette
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.ScreenshotStrip
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

/** The ride that was running when the companion left, as far as the phone knows it. */
data class RideInProgress(
    val name: String,
    val durationSeconds: Int,
    val distanceMeters: Double,
    /** When it started, so the screenshots taken on it can be shown with it. */
    val startTimeEpoch: Long = 0L,
)

/**
 * What a rider sees when the companion driving their session goes away.
 *
 * ## Why it sits on the ride screen
 *
 * The moment a game closes is the moment a rider most needs telling what just
 * happened, and they are looking at this screen when it does. Moving them to
 * another one would answer the question somewhere they were not looking, and
 * a HUD left up with a flat road and no explanation, which is how this used to
 * end, answers it nowhere at all.
 *
 * ## Two states, one panel
 *
 * While the companion is away the ride it was driving is paused, and the panel
 * says so in as many words: the ride is paused, the game has gone, the app is
 * waiting for it. There is no countdown. A game that dropped without finishing
 * has not ended the ride, and nothing about it going quiet says the rider is
 * done. It closes by itself when the game comes back, and the ride carries on.
 *
 * The rider's way out is End ride, which saves what they rode as an ordinary
 * completed ride. The same panel then becomes the ending: every ride the
 * session produced.
 *
 * It covers the paddles on purpose. A rider reaching for a gear on a session
 * that is over should hit the panel, not shift a trainer nothing is driving.
 */
@Composable
fun CompanionSessionPanel(
    phase: SessionPhase,
    companionName: String?,
    rides: List<SavedCompanionRide>,
    /** Present only while waiting, and only when a ride is underway, which it paused. */
    inProgress: RideInProgress?,
    /**
     * The companion's icon and colours, so the panel saying it has gone still
     * looks like the thing that left. The warning colour of "disconnected" is
     * the app's, not the companion's.
     */
    icon: ImageBitmap? = null,
    palette: BrandPalette? = null,
    onEndSession: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ended = phase == SessionPhase.ENDED
    val who = companionName ?: "The companion"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0.copy(alpha = 0.88f))
            // Swallow every touch that reaches the scrim. The paddles underneath
            // shift on touch-down and do not wait to be asked twice.
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
            }
            .padding(Spacing.s4),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .drawBehind {
                    // The companion's accent along the top edge, the same line
                    // that sits under its header on the ride screen.
                    if (palette != null) {
                        drawRect(
                            color = palette.accent.brandColor(),
                            size = androidx.compose.ui.geometry.Size(size.width, 4.dp.toPx()),
                        )
                    }
                }
                .padding(Spacing.s5),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (companionName != null) {
                    CompanionMark(name = companionName, icon = icon, palette = palette, size = 44.dp)
                }
                Text(
                    text = when {
                        ended -> "Session ended"
                        inProgress != null -> "Ride paused"
                        else -> "$who disconnected"
                    },
                    style = CadenceType.h2,
                    color = if (ended) CadenceColors.Fg1 else CadenceColors.Warning,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (ended) {
                    if (rides.isEmpty()) {
                        "No rides were recorded. A ride is kept once the game has started one."
                    } else {
                        "Every ride below is saved in History."
                    }
                } else if (inProgress != null) {
                    "$who disconnected before the ride finished. The trainer is off and " +
                        "nothing is being recorded. When it reconnects the ride carries on " +
                        "by itself; if it is not coming back, end the ride to save it."
                } else {
                    "Waiting for it to reconnect."
                },
                style = CadenceType.body,
                color = CadenceColors.Fg2,
            )

            if (rides.isNotEmpty() || inProgress != null) {
                Text(
                    text = "Rides this session",
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg3,
                    modifier = Modifier.padding(top = Spacing.s2),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    items(rides, key = { it.sessionId }) { ride ->
                        SessionRideRow(
                            name = ride.name,
                            detail = rideDetail(
                                ride.durationSeconds,
                                ride.distanceMeters,
                                ride.averagePowerWatts,
                            ),
                            note = null,
                            rideStartEpochMs = ride.startTimeEpoch,
                        )
                    }
                    if (inProgress != null) {
                        item(key = "in-progress") {
                            SessionRideRow(
                                name = inProgress.name,
                                detail = rideDetail(
                                    inProgress.durationSeconds,
                                    inProgress.distanceMeters,
                                    0,
                                ),
                                note = "Paused. Saved as a completed ride when you end it.",
                                live = true,
                                rideStartEpochMs = inProgress.startTimeEpoch,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s2),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
            ) {
                if (ended) {
                    PrimaryBtn(
                        text = "Done",
                        leadingIcon = null,
                        onClick = onDone,
                        modifier = Modifier.weight(1f),
                    )
                } else if (inProgress != null) {
                    // Primary: with the game gone this is the only thing there is
                    // to do, and it is not destructive. The ride is kept.
                    PrimaryBtn(
                        text = "End ride",
                        leadingIcon = null,
                        onClick = onEndSession,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    SecondaryBtn(
                        text = "End session",
                        onClick = onEndSession,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRideRow(
    name: String,
    detail: String,
    note: String?,
    live: Boolean = false,
    rideStartEpochMs: Long = 0L,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg2.copy(alpha = if (live) 0.5f else 0.8f))
            .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
    ) {
        Text(
            text = name,
            style = CadenceType.bodyStrong,
            color = CadenceColors.Fg1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = detail, style = CadenceType.small, color = CadenceColors.Fg2)
        if (note != null) {
            Text(
                text = note,
                style = CadenceType.small,
                color = if (live) CadenceColors.Fg3 else CadenceColors.Warning,
            )
        }
        if (rideStartEpochMs != 0L) ScreenshotStrip(rideStartEpochMs)
    }
}

/** Duration always; distance and power when there are any. */
private fun rideDetail(seconds: Int, distanceMeters: Double, avgPowerWatts: Int): String {
    val parts = mutableListOf(clock(seconds))
    if (distanceMeters >= 10.0) parts += "%.1f km".format(distanceMeters / 1000.0)
    if (avgPowerWatts > 0) parts += "$avgPowerWatts W avg"
    return parts.joinToString(" · ")
}

private fun clock(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
