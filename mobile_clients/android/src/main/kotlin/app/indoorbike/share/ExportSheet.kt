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
package app.indoorbike.share

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet that lets the rider pick an export format (FIT / TCX) for a
 * completed ride, then encodes it off the main thread and hands the file to
 * the OS share sheet via [ActivityExporter].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    session: WorkoutSession,
    telemetry: List<WorkoutTelemetry>,
    workout: Workout?,
    ftp: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CadenceColors.Bg1,
        contentColor = CadenceColors.Fg1,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s5)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = Spacing.s5),
        ) {
            Text(text = "EXPORT FILE", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
            Spacer(Modifier.height(Spacing.s2))
            Text(
                text = "Save or share this ride as a standard activity file. Pick a format:",
                style = CadenceType.body,
                color = CadenceColors.Fg2,
            )
            Spacer(Modifier.height(Spacing.s5))

            ActivityExporter.Format.entries.forEach { format ->
                FormatRow(
                    format = format,
                    enabled = !busy,
                    onClick = {
                        if (busy || telemetry.isEmpty()) return@FormatRow
                        busy = true
                        scope.launch {
                            try {
                                val export = withContext(Dispatchers.IO) {
                                    ActivityExporter.build(format, session, telemetry, workout, ftp)
                                }
                                val uri = ActivityExporter.stageForShare(context, export)
                                ActivityExporter.launchShareChooser(context, uri, export.mime)
                                onDismiss()
                            } catch (t: Throwable) {
                                Toast.makeText(context, "Export failed: ${t.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
                Spacer(Modifier.height(Spacing.s3))
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = CadenceColors.Brand500,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.s2))
                    Text(text = "Encoding…", style = CadenceType.tick, color = CadenceColors.Fg3)
                }
            }
        }
    }
}

@Composable
private fun FormatRow(
    format: ActivityExporter.Format,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg2)
            .border(1.dp, CadenceColors.Line2, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(Spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CadenceColors.Brand500.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(Spacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "${format.label} (.${format.ext})", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
            Text(text = format.blurb, style = CadenceType.tick, color = CadenceColors.Fg3)
        }
    }
}
