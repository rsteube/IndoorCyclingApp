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
package app.indoorbike.ui.components

import android.graphics.BitmapFactory
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.indoorbike.LocalAppContainer
import app.indoorbike.ride.RideScreenshot
import app.indoorbike.share.ShareImageGenerator
import app.indoorbike.shared.workout.formatClock
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** A ride's screenshots, read again whenever one is added or removed. */
@Composable
fun rememberRideScreenshots(rideStartEpochMs: Long): List<RideScreenshot> {
    val store = LocalAppContainer.current.rideScreenshots
    val revision by store.revision.collectAsStateWithLifecycle()
    val shots by produceState(emptyList<RideScreenshot>(), rideStartEpochMs, revision) {
        value = withContext(Dispatchers.IO) { store.list(rideStartEpochMs) }
    }
    return shots
}

/**
 * A picture decoded no larger than it will be drawn.
 *
 * A 1080p frame decoded whole is 8 MB of pixels; a row of them as thumbnails
 * would be most of a phone's image budget for pictures a few dozen dp tall.
 *
 * Public because the share sheet decodes the same files at two more sizes: a
 * tile to choose from and the full width of the card it is chosen for.
 */
@Composable
fun rememberScreenshotBitmap(file: File, longestEdgePx: Int): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, file, longestEdgePx) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longestEdgePx) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
private fun Dp.px(): Int = with(LocalDensity.current) { this@px.roundToPx() }

@Composable
private fun Thumbnail(shot: RideScreenshot, height: Dp, onClick: () -> Unit) {
    val image = rememberScreenshotBitmap(shot.file, (height * 16 / 9).px())
    Box(
        modifier = Modifier
            .height(height)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg2)
            .clickable(onClick = onClick),
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Screenshot at " + formatClock(shot.rideSeconds),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The pictures taken on a ride, on its summary and in history.
 *
 * Nothing at all for a ride without any, which is almost every ride: an empty
 * "Screenshots" card would read as pictures that failed to load.
 */
@Composable
fun RideScreenshotsCard(rideStartEpochMs: Long, modifier: Modifier = Modifier) {
    val shots = rememberRideScreenshots(rideStartEpochMs)
    if (shots.isEmpty()) return
    var viewing by remember { mutableStateOf<Int?>(null) }
    Spacer(modifier = Modifier.height(Spacing.s3))
    SlateCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Screenshots", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
            Text(shots.size.toString(), style = CadenceType.small, color = CadenceColors.Fg3)
        }
        Spacer(modifier = Modifier.height(Spacing.s3))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            itemsIndexed(shots, key = { _, shot -> shot.file.name }) { index, shot ->
                Thumbnail(shot, height = 112.dp, onClick = { viewing = index })
            }
        }
    }
    viewing?.let { start -> ScreenshotViewer(shots, start, onDismiss = { viewing = null }) }
}

/**
 * A ride's pictures as a row small enough for a list of rides, or nothing.
 * Five at most, and a count for the rest.
 */
@Composable
fun ScreenshotStrip(rideStartEpochMs: Long, modifier: Modifier = Modifier) {
    val shots = rememberRideScreenshots(rideStartEpochMs)
    if (shots.isEmpty()) return
    var viewing by remember { mutableStateOf<Int?>(null) }
    Row(
        modifier = modifier.padding(top = Spacing.s2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shots.take(STRIP_MOST).forEachIndexed { index, shot ->
            Thumbnail(shot, height = 40.dp, onClick = { viewing = index })
        }
        if (shots.size > STRIP_MOST) {
            Text("+" + (shots.size - STRIP_MOST), style = CadenceType.small, color = CadenceColors.Fg3)
        }
    }
    viewing?.let { start -> ScreenshotViewer(shots, start, onDismiss = { viewing = null }) }
}

private const val STRIP_MOST = 5

/** The pictures full screen, one at a time, swiped between. */
@Composable
fun ScreenshotViewer(shots: List<RideScreenshot>, startIndex: Int, onDismiss: () -> Unit) {
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (shots.size - 1).coerceAtLeast(0))) { shots.size }
    val screenEdgePx = LocalConfiguration.current.let { maxOf(it.screenWidthDp, it.screenHeightDp) }.dp.px()
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val image = rememberScreenshotBitmap(shots[page].file, screenEdgePx)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = "Screenshot " + (page + 1) + " of " + shots.size,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(Spacing.s4),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shot = shots.getOrNull(pager.currentPage)
                Text(
                    text = (pager.currentPage + 1).toString() + " of " + shots.size +
                        (shot?.let { " · " + formatClock(it.rideSeconds) + " into the ride" } ?: ""),
                    style = CadenceType.small,
                    color = CadenceColors.Fg1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radii.md))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = Spacing.s3, vertical = Spacing.s2),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                    // Only the picture the rider is looking at, and only when
                    // they ask. The pictures sit on the phone until this.
                    if (shot != null) {
                        IconBtn(
                            icon = Icons.Outlined.Share,
                            contentDescription = "Share this picture",
                            onClick = { shareScreenshot(context, shot) },
                            background = Color.Black.copy(alpha = 0.55f),
                        )
                    }
                    IconBtn(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Close",
                        onClick = onDismiss,
                        background = Color.Black.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

/**
 * Hand one screenshot to the system share sheet.
 *
 * Named for the moment it was taken rather than for the file it came from, so
 * what lands in a chat or a photo roll says when it is from rather than a pair
 * of epoch numbers. Failures are swallowed on purpose: a share sheet that will
 * not open is not worth a crash in a full-screen viewer, and the rider can see
 * for themselves that nothing happened.
 */
private fun shareScreenshot(context: Context, shot: RideScreenshot) {
    runCatching {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(shot.takenAtEpochMs))
        val uri = ShareImageGenerator.stageFileForShare(
            context = context,
            file = shot.file,
            displayName = "indoorbike-$stamp",
        )
        ShareImageGenerator.launchShareChooser(
            context = context,
            uri = uri,
            chooserTitle = "Share this picture",
            mimeType = ShareImageGenerator.imageMimeType(shot.file),
        )
    }
}
