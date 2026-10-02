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
package app.indoorbike.shared.feedback

/** Where "Report a problem" drafts its email to. */
const val SUPPORT_EMAIL = "juhani@snapp.technology"

/** The subject line of that email. */
fun bugReportSubject(appVersion: String): String = "Cycling app bug report - v$appVersion"

/**
 * The text of a bug report: room for the rider's own words at the top, then
 * diagnostics that carry no personal data. Laid out the same on both phones.
 *
 * [deviceInfo] is the platform's few lines about the app and the device, and
 * [exits] its record of how recent runs ended, where it keeps one (Android
 * does; iOS passes null and the section is left out).
 */
fun bugReportText(
    errorContext: String?,
    deviceInfo: String,
    exitsHeading: String?,
    exits: String?,
    previousRunLog: String,
    recentLog: String,
): String = buildString {
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
    appendLine(deviceInfo.trimEnd())
    appendLine()
    if (exitsHeading != null) {
        appendLine(exitsHeading)
        appendLine(exits ?: "(none recorded)")
        appendLine()
    }
    // The previous run's lines come first: when the app died, the "recent"
    // ones below can only cover the launch that followed it.
    appendLine("Logs from the previous run:")
    appendLine(previousRunLog.ifBlank { "(none captured)" })
    appendLine()
    appendLine("Recent logs:")
    appendLine(recentLog.ifBlank { "(none captured)" })
}
