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
package app.indoorbike.shared.ble

import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.redactDeviceId
import com.juul.kable.logs.LogEngine
import com.juul.kable.logs.Logging

/**
 * Routes Kable's own logging into [Log], and from there into the diagnostics
 * buffer a bug report attaches.
 *
 * Why this exists: Kable swallows a whole class of failure without telling the
 * app. Enabling notifications on a characteristic is wrapped in a
 * "suppress connection exceptions" block, so when the phone's Bluetooth stack
 * refuses the CCCD write - which it does when the stack is wedged, the very
 * failure that leaves a heart rate strap connected but silent - the app sees a
 * healthy connection, an observation flow that never emits, and no error
 * anywhere. The only trace is Kable's own verbose "Suppressed failure" line.
 * Without this bridge that line goes to Logcat in debug builds and nowhere at
 * all in release, so bug reports about a silent sensor arrive with a diagnostic
 * section that says nothing.
 *
 * Two things are deliberately filtered out:
 *
 *  - **`debug` is dropped entirely.** Every per-operation GATT callback
 *    (`onCharacteristicChanged` and friends) logs at that level, which at one
 *    notification per second per characteristic would push the whole ring
 *    buffer out within a couple of minutes of riding. Everything Kable logs
 *    about the connection *lifecycle* - connect, disconnect, service
 *    discovery, observation setup, suppressed failures - is verbose or higher,
 *    so nothing diagnostic is lost.
 *  - **Payload bytes.** Kable only renders characteristic data at
 *    [Logging.Level.Data], and we never ask for that level. This matters:
 *    heart rate is a body metric, and these lines can leave the device in a
 *    report.
 *
 * Device identifiers are replaced with [redactDeviceId] output. Kable defaults
 * the log prefix to the platform identifier, which on Android is the hardware
 * address, so [peripheralLogging] must always be given the id to redact.
 */
internal object KableLogEngine : LogEngine {
    override fun verbose(throwable: Throwable?, tag: String, message: String) {
        Log.d(tag, message)
    }

    /** Dropped: this is the per-GATT-operation firehose. See the class doc. */
    override fun debug(throwable: Throwable?, tag: String, message: String) = Unit

    override fun info(throwable: Throwable?, tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun warn(throwable: Throwable?, tag: String, message: String) {
        Log.w(tag, message, throwable)
    }

    override fun error(throwable: Throwable?, tag: String, message: String) {
        Log.e(tag, message, throwable)
    }

    override fun assert(throwable: Throwable?, tag: String, message: String) {
        Log.e(tag, message, throwable)
    }
}

/**
 * Logging config for a peripheral connection. [Logging.Level.Events] is the
 * lowest level at which Kable emits its verbose lines, which is where the
 * observation-setup failures live.
 */
internal fun peripheralLogging(deviceId: String): Logging.() -> Unit = {
    engine = KableLogEngine
    level = Logging.Level.Events
    format = Logging.Format.Compact
    identifier = redactDeviceId(deviceId)
}

/**
 * Logging config for a scan. Warnings only: a scan logs a start and a stop line
 * at event level, and the reconnect loops open a scan every few seconds, so
 * event level here would be pure churn. Warnings and errors are emitted at any
 * level, which keeps the one line that matters - a scan the system refused to
 * start.
 */
internal fun scannerLogging(): Logging.() -> Unit = {
    engine = KableLogEngine
    level = Logging.Level.Warnings
    format = Logging.Format.Compact
}
