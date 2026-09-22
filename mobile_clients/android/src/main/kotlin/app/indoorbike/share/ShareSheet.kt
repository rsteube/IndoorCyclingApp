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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import app.indoorbike.ride.RideScreenshot
import app.indoorbike.ride.RideBrand
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.workout.formatClock
import app.indoorbike.ui.components.rememberRideScreenshots
import app.indoorbike.ui.components.rememberScreenshotBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.indoorbike.ride.rideRoute
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.shared.progression.RideStats
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.components.SheetHandle
import app.indoorbike.ui.components.SheetScrim
import app.indoorbike.ui.components.SheetShape
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Bottom sheet that previews the [RideShareCard] thumbnail and offers
 * Save-to-Photos and Share actions. The full-size 1080×1080 card is also
 * laid out (alpha 0) so [rememberGraphicsLayer] has real pixels to capture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    session: WorkoutSession,
    stats: RideStats,
    workout: Workout?,
    telemetry: List<WorkoutTelemetry>,
    ftp: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val highlight = remember(workout, telemetry) { deriveHighlight(workout, telemetry) }
    // The road the ride was on, from the route catalogue for a simulated ride
    // and from the ride's own record for a companion one. Null for a workout
    // or a free ride, and for a route since removed from the catalogue - in
    // which case the card falls back to the power chart rather than showing a
    // blank panel.
    val container = app.indoorbike.LocalAppContainer.current
    val route = remember(session.id) { rideRoute(container, session) }
    val graphicsLayer = rememberGraphicsLayer()

    var busy by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

    // The pictures a companion sent during this ride, found by the ride's start
    // the same way the summary above finds them. Almost every ride has none, and
    // for those this sheet is exactly what it was.
    val shots = rememberRideScreenshots(session.startTimeEpoch)
    // The newest picture to hand, rather than the chart, when the ride has
    // any: a rider who has pictures of the road almost always means one of
    // those, and the chart is one tap away on the row below the preview.
    var picked by remember(shots.size) { mutableStateOf(shots.firstOrNull()) }
    val pickedShot = picked
    // Decoded at the card's own size, not the screen's: this one goes into a
    // 1080-pixel bitmap, and a thumbnail stretched to fill it would be the
    // first thing anyone noticed about the card.
    val picture = if (pickedShot != null) {
        rememberScreenshotBitmap(pickedShot.file, SHARE_CARD_SIZE_PX)
    } else null

    // Who the rider rode with, for a ride that was driven by a companion. Read
    // from the ride rather than from the socket, because the socket closed when
    // the ride ended and this sheet opens days later from history.
    val credit = rememberRideCredit(session.startTimeEpoch)

    // Pre-Q: ask for WRITE_EXTERNAL_STORAGE before saving. Q+ skips this.
    val needsLegacyPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    val storagePermissionGranted = remember {
        mutableStateOf(
            !needsLegacyPerm ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        storagePermissionGranted.value = granted
        if (granted) {
            performSave(
                context = context,
                graphicsLayer = graphicsLayer,
                session = session,
                onBusy = { busy = it },
                scope = scope
            )
        } else {
            Toast.makeText(
                context,
                "Storage permission needed to save to Photos",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CadenceColors.Bg1,
        contentColor = CadenceColors.Fg1,
        shape = SheetShape,
        scrimColor = SheetScrim,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s5)
                // Both bars, not just the bottom one. Once the content is tall
                // enough to want the whole screen, the sheet takes the whole
                // screen, and the first line of it was landing behind the
                // clock. `windowInsetsPadding` is position-aware, so on a sheet
                // that stops short of the top this adds nothing.
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = Spacing.s5)
        ) {
            // Everything above the buttons scrolls, and the buttons are not in
            // it.
            //
            // A square preview is as tall as the sheet is wide, and with a
            // picker under it the sheet wanted more height than a phone has,
            // or than a tablet has in landscape. A bottom sheet does not
            // scroll what it is given: it clips it. Save and Share were simply
            // off the bottom of the screen, on the one screen whose whole
            // purpose is those two buttons.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Share ride",
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg3
                )
                Spacer(Modifier.height(Spacing.s2))
                Text(
                    text = if (shots.isEmpty())
                        "Save the card to Photos or hand it to any sharing app."
                    else "Pick what the card shows, then save it or hand it to any sharing app.",
                    style = CadenceType.body,
                    color = CadenceColors.Fg2
                )

                Spacer(Modifier.height(Spacing.s5))

                // Visible thumbnail preview, drawn from the same Composable as
                // the capture target, scaled down to fit.
                ThumbnailPreview {
                    CaptureTarget(
                        route = route,
                        graphicsLayer = graphicsLayer,
                        session = session,
                        stats = stats,
                        workout = workout,
                        telemetry = telemetry,
                        ftp = ftp,
                        highlight = highlight,
                        picture = picture,
                        companion = credit
                    )
                }

                if (shots.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.s4))
                    PictureChooser(
                        shots = shots,
                        selected = pickedShot,
                        onSelect = { picked = it },
                    )
                }
            }

            Spacer(Modifier.height(Spacing.s5))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                SecondaryBtn(
                    text = if (busy && pendingAction == PendingAction.Save) "Saving…" else "Save",
                    leadingIcon = Icons.Outlined.Download,
                    onClick = {
                        if (busy) return@SecondaryBtn
                        pendingAction = PendingAction.Save
                        if (storagePermissionGranted.value) {
                            performSave(context, graphicsLayer, session, { busy = it }, scope)
                        } else {
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                PrimaryBtn(
                    text = if (busy && pendingAction == PendingAction.Share) "Preparing…" else "Share…",
                    leadingIcon = Icons.Outlined.Share,
                    onClick = {
                        if (busy) return@PrimaryBtn
                        pendingAction = PendingAction.Share
                        performShare(context, graphicsLayer, session, { busy = it }, scope)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            if (busy) {
                Spacer(Modifier.height(Spacing.s3))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = CadenceColors.Fg1,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(Spacing.s2))
                    Text(
                        text = "Rendering 1080×1080…",
                        style = CadenceType.tick,
                        color = CadenceColors.Fg3
                    )
                }
            }
        }
    }
}

private enum class PendingAction { Save, Share }

/**
 * The companion a ride was ridden with, with its icon decoded, or null.
 *
 * Decoded small: it is drawn at 40 of the card's 1080 pixels, and a game's
 * icon can be 512 square.
 */
@Composable
private fun rememberRideCredit(rideStartEpochMs: Long): RideCredit? {
    val store = LocalAppContainer.current.rideBrands
    val revision by store.revision.collectAsState()
    val brand by produceState<RideBrand?>(null, rideStartEpochMs, revision) {
        value = withContext(Dispatchers.IO) { store.find(rideStartEpochMs) }
    }
    val held = brand ?: return null
    val icon = held.icon?.let { rememberScreenshotBitmap(it, CREDIT_ICON_PX) }
    return RideCredit(name = held.name, icon = icon)
}

private const val CREDIT_ICON_PX = 128

/**
 * What goes on the card: its own chart or road, or one of the ride's pictures.
 *
 * Every choice is a tile of the same size, the picture ones showing the picture,
 * because the question is which image to send and a list of timestamps does not
 * answer it. A handful of pictures all fit on the row. More than that and the
 * row stops being a row anyone can read, so it keeps the first few and offers
 * the rest as a grid, which is the shape a phone's own photo picker uses for
 * the same reason.
 */
@Composable
private fun PictureChooser(
    shots: List<RideScreenshot>,
    selected: RideScreenshot?,
    onSelect: (RideScreenshot?) -> Unit,
) {
    var browsing by remember { mutableStateOf(false) }
    val inline = shots.take(INLINE_MOST)
    val rest = shots.size - inline.size

    Text(text = "On the card", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
    Spacer(Modifier.height(Spacing.s2))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
        item(key = "card") {
            ChoiceTile(selected = selected == null, onClick = { onSelect(null) }) {
                Box(
                    modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Ride card",
                        style = CadenceType.small,
                        color = CadenceColors.Fg2,
                    )
                }
            }
        }
        items(inline, key = { it.file.name }) { shot ->
            ChoiceTile(selected = selected == shot, onClick = { onSelect(shot) }) {
                PictureTile(shot)
            }
        }
        if (rest > 0) {
            item(key = "more") {
                // The chosen picture is always on the row, even when it came
                // from the grid: a selection the rider cannot see is one they
                // cannot undo without opening the grid again to look.
                val hidden = selected != null && selected !in inline
                ChoiceTile(selected = hidden, onClick = { browsing = true }) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (hidden) PictureTile(selected)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(CadenceColors.Bg0.copy(alpha = if (hidden) 0.6f else 1f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (hidden) "Change" else "+" + rest + " more",
                                style = CadenceType.small,
                                color = CadenceColors.Fg2,
                            )
                        }
                    }
                }
            }
        }
    }

    if (browsing) {
        PictureGrid(
            shots = shots,
            selected = selected,
            onPick = {
                onSelect(it)
                browsing = false
            },
            onDismiss = { browsing = false },
        )
    }
}

/** How many pictures the row shows before it hands over to the grid. */
private const val INLINE_MOST = 3

private val TileHeight = 64.dp

@Composable
private fun ChoiceTile(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .height(TileHeight)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(CadenceColors.Bg2)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) CadenceColors.Fg1 else CadenceColors.Line2,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
    ) {
        content()
    }
}

@Composable
private fun PictureTile(shot: RideScreenshot) {
    val image = rememberScreenshotBitmap(shot.file, THUMB_EDGE_PX)
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "Picture taken " + formatClock(shot.rideSeconds) + " into the ride",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val THUMB_EDGE_PX = 320

/** The rest of the pictures, laid out the way a photo picker lays them out. */
@Composable
private fun PictureGrid(
    shots: List<RideScreenshot>,
    selected: RideScreenshot?,
    onPick: (RideScreenshot) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(SheetShape)
                .background(CadenceColors.Bg1)
                .padding(Spacing.s4),
        ) {
            Text(
                text = "Pick a picture",
                style = CadenceType.h3,
                color = CadenceColors.Fg1,
            )
            Text(
                text = shots.size.toString() + " from this ride",
                style = CadenceType.small,
                color = CadenceColors.Fg3,
            )
            Spacer(Modifier.height(Spacing.s3))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                items(shots, key = { it.file.name }) { shot ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CadenceColors.Bg2)
                            .border(
                                width = if (shot == selected) 2.dp else 1.dp,
                                color = if (shot == selected) CadenceColors.Fg1 else CadenceColors.Line2,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable { onPick(shot) },
                    ) {
                        PictureTile(shot)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.s3))
            SecondaryBtn(
                text = "Close",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}


@Composable
private fun ThumbnailPreview(card: @Composable () -> Unit) {
    // Never taller than about half the window. The card is square, so on a
    // phone it would otherwise be as tall as the sheet is wide and leave the
    // picker and the buttons fighting over what is left; in landscape it would
    // be taller than the screen. Capped, it is still the biggest thing on the
    // sheet, and what it gives up is height nothing else had.
    val maxPreview = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            modifier = Modifier
                .heightIn(max = maxPreview)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(CadenceColors.Bg0)
        ) {
            // The capture target lays out at SHARE_CARD_SIZE_PX dp under a forced
            // density of 1, so it occupies SHARE_CARD_SIZE_PX device pixels. We then
            // visually scale that down to fit our box width with a graphicsLayer
            // transform; the scale is irrelevant to what GraphicsLayer captures —
            // it always records the unscaled (1080×1080) drawing.
            val outerWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val scale = (outerWidthPx / SHARE_CARD_SIZE_PX).coerceAtLeast(0.05f)
            Box(
                modifier = Modifier
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    )
                    // Measured as if the sheet were not there, and pinned to the
                    // corner the scale shrinks towards.
                    //
                    // Without this the preview lied. `requiredSize` lays the card
                    // out at its full 1080 whatever the parent says, but the parent
                    // still reports the width it was allowed, and a child bigger
                    // than its box is centred in it: the card sat at a negative x,
                    // and the scale, computed from the box, put its first 60-odd
                    // pixels off the left edge. What the rider saw was a card with
                    // its margins cropped and its title cut, which is not the card
                    // that gets saved.
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true)
            ) {
                card()
            }
        }
    }
}

/**
 * Off-screen capture host. Drawn at full 1080×1080 logical size with a forced
 * density of 1 so dp/sp/px are 1:1 in the rendered bitmap. Wrapped in alpha 0
 * inside the parent ThumbnailPreview, so it's laid out + drawn (required for
 * GraphicsLayer.toImageBitmap to have content) without showing twice.
 */
@Composable
private fun CaptureTarget(
    route: app.indoorbike.shared.route.RouteTrack?,
    graphicsLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    session: WorkoutSession,
    stats: RideStats,
    workout: Workout?,
    telemetry: List<WorkoutTelemetry>,
    ftp: Int,
    highlight: app.indoorbike.share.ShareHighlight,
    picture: androidx.compose.ui.graphics.ImageBitmap?,
    companion: RideCredit?
) {
    CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
        Box(
            modifier = Modifier
                // requiredSize, not size: size() coerces into the incoming
                // constraints, and the incoming constraints here are the bottom
                // sheet's width. That clamped the card to the sheet instead of
                // 1080, so its internal layout was squeezed (labels wrapping
                // mid-word) and the captured bitmap was never the size the
                // rest of this file assumes. requiredSize ignores the parent
                // and lays out at exactly 1080; the thumbnail's graphicsLayer
                // scale is what makes it fit on screen.
                .requiredSize(SHARE_CARD_SIZE_PX.dp)
                .drawWithContent {
                    graphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(graphicsLayer)
                }
        ) {
            RideShareCard(
                session = session,
                stats = stats,
                workout = workout,
                telemetry = telemetry,
                ftp = ftp,
                highlight = highlight,
                route = route,
                picture = picture,
                companion = companion
            )
        }
    }
}

private fun performSave(
    context: android.content.Context,
    graphicsLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    session: WorkoutSession,
    onBusy: (Boolean) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    onBusy(true)
    scope.launch {
        try {
            val bitmap = ShareImageGenerator.captureLayer(graphicsLayer)
            val uri = ShareImageGenerator.saveToGallery(
                context = context,
                bitmap = bitmap,
                displayName = displayNameFor(session)
            )
            val msg = if (uri != null) "Saved to Photos · Indoor Bike" else "Couldn't save image"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(context, "Save failed: ${t.message}", Toast.LENGTH_SHORT).show()
        } finally {
            onBusy(false)
        }
    }
}

private fun performShare(
    context: android.content.Context,
    graphicsLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    session: WorkoutSession,
    onBusy: (Boolean) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    onBusy(true)
    scope.launch {
        try {
            val bitmap = ShareImageGenerator.captureLayer(graphicsLayer)
            val uri = ShareImageGenerator.stageForShare(
                context = context,
                bitmap = bitmap,
                displayName = displayNameFor(session)
            )
            ShareImageGenerator.launchShareChooser(context, uri)
        } catch (t: Throwable) {
            Toast.makeText(context, "Share failed: ${t.message}", Toast.LENGTH_SHORT).show()
        } finally {
            onBusy(false)
        }
    }
}

private fun displayNameFor(session: WorkoutSession): String {
    val safe = session.workoutName
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "ride" }
    return "indoorbike-$safe-${session.id}"
}
