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
package app.indoorbike.shared.sailfish

import app.indoorbike.shared.feedback.SUPPORT_EMAIL
import app.indoorbike.shared.feedback.bugReportSubject
import app.indoorbike.shared.feedback.bugReportText
import app.indoorbike.shared.util.DiagnosticsLog
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.PosixFiles
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The app itself: its version and build on the `app` topic, QML's log lines
 * into the diagnostics log, and a self-test the Qt side can run headless.
 */
internal fun SailfishCore.installApp() {
    bus.publish("app", flowOf(Unit)) {
        buildJsonObject {
            put("version", appVersion)
            put("debug", debug)
            put("dataDir", SailfishPaths.dataDir)
        }
    }

    /** A line from QML, so it reaches the same log a bug report attaches. */
    bus.command("log") { args ->
        val tag = args.string("tag") ?: "QML"
        val message = args.requireString("message")
        when (args.string("level")) {
            "w" -> Log.w(tag, message)
            "e" -> Log.e(tag, message)
            else -> Log.i(tag, message)
        }
        JsonNull
    }

    bus.command("app.diagnostics") { JsonPrimitive(DiagnosticsLog.snapshot()) }

    /**
     * A bug report, laid out as the Android app lays it out: the address, the
     * subject, and the text with this run's log and the last run's.
     */
    bus.command("app.bugReport") { args ->
        val sailfish = PosixFiles.readText("/etc/os-release")?.lineSequence()
            ?.firstOrNull { it.startsWith("VERSION_ID=") }?.substringAfter('=')?.trim('"') ?: "unknown"
        buildJsonObject {
            put("address", SUPPORT_EMAIL)
            put("subject", bugReportSubject(appVersion))
            put("text", bugReportText(
                errorContext = args.string("context"),
                deviceInfo = "IndoorBike $appVersion for Sailfish OS\n${SailfishCore.deviceName()}, Sailfish OS $sailfish",
                exitsHeading = null,
                exits = null,
                previousRunLog = DiagnosticsLog.previousRunLog(),
                recentLog = DiagnosticsLog.snapshot(),
            ))
        }
    }
}
