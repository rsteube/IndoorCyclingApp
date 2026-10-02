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

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.indoorbike.BuildConfig
import app.indoorbike.shared.activity.exportFileName
import app.indoorbike.shared.Workout
import app.indoorbike.shared.activity.TcxBuilder
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.shared.fit.RideFitBuilder
import app.indoorbike.shared.fit.VirtualWorld
import java.io.File

/**
 * Exports a completed ride as a standard activity file and hands it to the
 * OS share sheet. Reuses the in-repo encoders - [RideFitBuilder] (FIT) and
 * [TcxBuilder] (TCX) - so there are no third-party dependencies and nothing
 * leaves the device except through the share intent the user picks.
 *
 * The file is staged in `cacheDir/shared_files/` and exposed via the same
 * [FileProvider] authority used for the ride-share image, so receivers get a
 * grant-scoped `content://` URI rather than a `file://` path.
 */
object ActivityExporter {

    private const val SHARED_DIR = "shared_files"

    enum class Format(
        val ext: String,
        val mime: String,
        val label: String,
        val blurb: String,
    ) {
        FIT(
            ext = "fit",
            mime = "application/octet-stream",
            label = "FIT",
            blurb = "Garmin's binary format. The richest, best for Strava, Garmin Connect, intervals.icu.",
        ),
        TCX(
            ext = "tcx",
            mime = "application/vnd.garmin.tcx+xml",
            label = "TCX",
            blurb = "Readable XML. Widely supported by training platforms and spreadsheets.",
        ),
    }

    /** A built export ready to share: raw bytes plus a suggested filename. */
    data class Export(val bytes: ByteArray, val filename: String, val mime: String)

    /**
     * Encode [session] + [telemetry] in the requested [format]. Pure CPU work -
     * call off the main thread.
     */
    fun build(
        format: Format,
        session: WorkoutSession,
        telemetry: List<WorkoutTelemetry>,
        workout: Workout?,
        ftp: Int,
        /** Rider body mass, for the ride's estimated speed and distance. */
        riderKg: Float = 0f,
        /** The game that drove the ride, or null. Written into a FIT file only. */
        world: VirtualWorld? = null,
    ): Export {
        val bytes = when (format) {
            Format.FIT -> RideFitBuilder.build(
                session, telemetry, workout, ftp, riderKg,
                appVersion = BuildConfig.VERSION_NAME,
                world = world,
            )
            Format.TCX -> TcxBuilder.build(session, telemetry, workout, ftp, riderKg)
                .toByteArray(Charsets.UTF_8)
        }
        return Export(bytes, fileName(session, format), format.mime)
    }

    /** Stage [export] in the shared-files cache and return a FileProvider URI. */
    fun stageForShare(context: Context, export: Export): Uri {
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        val file = File(dir, export.filename)
        file.writeBytes(export.bytes)
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    /** Launch the system share-sheet chooser for the staged activity file. */
    fun launchShareChooser(context: Context, uri: Uri, mime: String, chooserTitle: String = "Export ride") {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Some receivers (Drive, Files, Gmail) read ClipData, not EXTRA_STREAM.
            clipData = android.content.ClipData.newRawUri(null, uri)
        }
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun fileName(session: WorkoutSession, format: Format): String =
        exportFileName(session, format.ext)
}
