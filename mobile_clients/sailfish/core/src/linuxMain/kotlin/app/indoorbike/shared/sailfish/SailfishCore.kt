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

import app.indoorbike.shared.ble.ControllerManager
import app.indoorbike.shared.ble.HeartRateMonitor
import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.util.DiagnosticsLog
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.installCrashLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement

/**
 * The Sailfish app's graph of shared objects, built once at start: the
 * counterpart of Android's `AppContainer` and of `IosAppContainer`, wired the
 * same way. Each feature puts its topics and commands on [bus] from a file of
 * its own (Trainer.kt and so on).
 *
 * Everything here runs on the core thread ([coreDispatcher]).
 */
internal class SailfishCore(
    /** Where the package's own read-only files are: /usr/share/harbour-indoorbike. */
    val packageDir: String,
    /** The app's version, as a ride file records it and a companion is told it. */
    val appVersion: String,
    /** A debug build, which offers the demo trainer. */
    val debug: Boolean,
    send: (topic: String, payload: JsonElement) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + coreDispatcher)
    val bus = Bus(scope, send)

    /** The radio: BlueZ, driven from the Qt side. See [BridgedCentral]. */
    val central = BridgedCentral(bus)
    val trainerManager = TrainerManager(central)
    val heartRateMonitor = HeartRateMonitor(central)
    val controllerManager = ControllerManager(central)

    fun start() {
        // Before the first log line, so the last run's log is rotated aside
        // where a bug report can find it.
        DiagnosticsLog.attachStorage()
        installCrashLogging()
        Log.i(TAG, "Core start, version $appVersion${if (debug) " (debug)" else ""}")
        central.install()
        installApp()
        installTrainer()
        installSensors()
    }

    fun stop() {
        trainerManager.dispose()
        heartRateMonitor.dispose()
        controllerManager.dispose()
        scope.cancel()
    }

    private companion object {
        const val TAG = "SailfishCore"
    }
}
