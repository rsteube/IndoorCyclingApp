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
package app.indoorbike.feedback

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import app.indoorbike.BuildConfig
import app.indoorbike.shared.util.DiagnosticsLog
import app.indoorbike.shared.util.ProcessExitReasons
import java.io.File

/**
 * Composes a bug-report email for the user to review and send. Nothing is sent
 * automatically: this only opens the user's email app with a pre-filled draft
 * (recipient, subject, body, and a diagnostics attachment). The user reads it
 * and decides whether to press send.
 *
 * The report contains device info (model, Android version, app version), the
 * diagnostic log for this run and the previous one ([DiagnosticsLog]), and
 * Android's record of how recent processes ended ([ProcessExitReasons]). No
 * personal data is gathered — the app logs none (see the logging audit /
 * [DiagnosticsLog] docs).
 */
object BugReport {

    const val SUPPORT_EMAIL = "juhani@snapp.technology"

    private const val SHARED_DIR = "shared_files"

    /**
     * Open an email draft addressed to the developer. [errorContext], when
     * present, is the specific in-app error the user tapped "Report" on, and is
     * surfaced at the top of the report so it's easy to spot.
     *
     * Returns false (and shows a toast) if no email app is available.
     */
    fun launchEmailDraft(context: Context, errorContext: String? = null): Boolean {
        val report = buildReport(context, errorContext)
        val subject = "Cycling app bug report - v${BuildConfig.VERSION_NAME}"

        val intent = Intent(Intent.ACTION_SEND).apply {
            // message/rfc822 biases the chooser toward email apps; EXTRA_EMAIL
            // pre-fills the recipient for those that honor it.
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, report)
            // Attach the same report as a .txt so it survives clients that
            // truncate long bodies, and is easy to forward.
            stageAttachment(context, report)?.let { uri ->
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Some clients read ClipData rather than EXTRA_STREAM.
                clipData = ClipData.newRawUri(null, uri)
            }
        }

        return try {
            val chooser = Intent.createChooser(intent, "Report a problem").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                "No email app found. Please email $SUPPORT_EMAIL directly.",
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    /** The full report text: a place for the user to write, then collected data. */
    fun buildReport(context: Context, errorContext: String?): String = buildString {
        appendLine("Describe what happened (optional):")
        appendLine()
        appendLine()
        appendLine("--------------------------------")
        appendLine("Diagnostics (no personal data):")
        appendLine()
        if (!errorContext.isNullOrBlank()) {
            appendLine("Reported error: $errorContext")
            appendLine()
        }
        appendLine(deviceInfo())
        appendLine()
        // Android's own record of how previous processes ended. This is the
        // only section that can describe a crash from the outside, so it leads:
        // it distinguishes an exception from an out-of-memory kill from the
        // user swiping the app away, none of which our own logs can tell apart.
        appendLine("Previous process exits (reported by Android):")
        appendLine(ProcessExitReasons.describeRecentExits(context) ?: "(none recorded)")
        appendLine()
        // The run that died. After a crash this is the section that matters;
        // "Recent logs" below can only cover the launch that followed it.
        appendLine("Logs from the previous run:")
        val previous = DiagnosticsLog.previousRunLog()
        appendLine(if (previous.isBlank()) "(none captured)" else previous)
        appendLine()
        appendLine("Recent logs:")
        val logs = DiagnosticsLog.snapshot()
        appendLine(if (logs.isBlank()) "(none captured)" else logs)
    }

    /** Device/app facts useful for debugging. Nothing here identifies a person. */
    private fun deviceInfo(): String = buildString {
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
            if (BuildConfig.DEBUG) " debug" else "")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    private fun stageAttachment(context: Context, report: String): Uri? = try {
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        val file = File(dir, "indoorbike-bug-report.txt")
        file.writeText(report)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        // Body still carries the full report; the attachment is a nicety.
        null
    }
}
