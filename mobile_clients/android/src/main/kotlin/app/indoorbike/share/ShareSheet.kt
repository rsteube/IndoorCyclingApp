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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
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
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = Spacing.s5)
        ) {
            Text(
                text = "Share ride",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3
            )
            Spacer(Modifier.height(Spacing.s2))
            Text(
                text = "Save the card to Photos or hand it to any sharing app.",
                style = CadenceType.body,
                color = CadenceColors.Fg2
            )

            Spacer(Modifier.height(Spacing.s5))

            // Visible thumbnail preview — drawn from the same Composable as the
            // capture target, scaled down to fit the sheet width.
            ThumbnailPreview {
                CaptureTarget(
                    route = route,
                    graphicsLayer = graphicsLayer,
                    session = session,
                    stats = stats,
                    workout = workout,
                    telemetry = telemetry,
                    ftp = ftp,
                    highlight = highlight
                )
            }

            Spacer(Modifier.height(Spacing.s6))

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

@Composable
private fun ThumbnailPreview(card: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
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
        ) {
            card()
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
    highlight: app.indoorbike.share.ShareHighlight
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
                route = route
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
